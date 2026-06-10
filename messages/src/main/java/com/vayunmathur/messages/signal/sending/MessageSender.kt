package com.vayunmathur.messages.signal.sending

import android.util.Base64
import android.util.Log
import com.vayunmathur.messages.signal.proto.SignalServiceProtos
import com.vayunmathur.messages.signal.store.SignalProtocolStoreImpl
import com.vayunmathur.messages.signal.web.SignalWebSocket
import org.json.JSONArray
import org.json.JSONObject
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyStore
import org.signal.libsignal.protocol.state.PreKeyStore
import org.signal.libsignal.protocol.state.SessionStore
import org.signal.libsignal.protocol.state.SignedPreKeyStore
import org.signal.libsignal.protocol.groups.state.SenderKeyStore

class MessageSender(
    private val ws: SignalWebSocket,
    private val sessionStore: SessionStore,
    private val identityKeyStore: IdentityKeyStore,
    private val preKeyStore: PreKeyStore,
    private val signedPreKeyStore: SignedPreKeyStore,
    private val kyberPreKeyStore: KyberPreKeyStore,
    private val senderKeyStore: SenderKeyStore,
    private val selfAci: String,
    private val selfDeviceId: Int,
    private val deviceManager: DeviceManager,
    private val recipientStore: com.vayunmathur.messages.signal.store.SignalRecipientStore? = null,
    private var unauthedWs: SignalWebSocket? = null,
) {
    private val protocolStore = SignalProtocolStoreImpl(
        sessionStore, identityKeyStore, preKeyStore, signedPreKeyStore, kyberPreKeyStore, senderKeyStore
    )

    @Volatile
    private var cachedSenderCertificate: org.signal.libsignal.metadata.certificate.SenderCertificate? = null
    private var senderCertExpiry: Long = 0

    class RecipientUnregisteredException(aci: String) : Exception("Recipient not registered (404): $aci")

    suspend fun sendMessage(
        recipientAci: String,
        content: SignalServiceProtos.Content,
        timestamp: Long,
    ): SendResult {
        val contentWithProfileKey = if (content.hasDataMessage() && !content.dataMessage.hasProfileKey()) {
            val profileKey = recipientStore?.getRecipient(selfAci)?.profileKey
            if (profileKey != null) {
                val dm = content.dataMessage.toBuilder()
                    .setProfileKey(com.google.protobuf.ByteString.copyFrom(profileKey))
                    .build()
                content.toBuilder().setDataMessage(dm).build()
            } else content
        } else content

        val isDeliveryReceipt = contentWithProfileKey.hasReceiptMessage() &&
            contentWithProfileKey.receiptMessage.type == SignalServiceProtos.ReceiptMessage.Type.DELIVERY
        if (recipientAci == selfAci && !isDeliveryReceipt) {
            if (contentWithProfileKey.hasDataMessage() || contentWithProfileKey.hasEditMessage()) {
                sendSyncMessage(null, contentWithProfileKey, timestamp)
            }
            return SendResult(success = true)
        }

        val paddedContent = padContent(contentWithProfileKey.toByteArray())
        return try {
            val sentUnidentified = sendToRecipient(recipientAci, paddedContent, timestamp, isUrgent(contentWithProfileKey))
            if (contentWithProfileKey.hasDataMessage() || contentWithProfileKey.hasEditMessage()) {
                sendSyncMessage(recipientAci, contentWithProfileKey, timestamp, unidentified = sentUnidentified)
            }
            SendResult(success = true, unidentified = sentUnidentified)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send message to $recipientAci", e)
            SendResult(success = false, error = e.message)
        }
    }

    suspend fun sendReadReceipt(recipientAci: String, timestamps: List<Long>) {
        try {
            val content = ContentBuilders.readReceipt(timestamps)
            sendMessage(recipientAci, content, System.currentTimeMillis())
            val syncContent = ContentBuilders.syncReadMessage(recipientAci, timestamps)
            val paddedSync = padContent(syncContent.toByteArray())
            val selfDevices = deviceManager.getDeviceIds(selfAci)
            for (deviceId in selfDevices) {
                if (deviceId == selfDeviceId) continue
                try {
                    deviceManager.ensureSession(selfAci, deviceId)
                    val address = SignalProtocolAddress(selfAci, deviceId)
                    val encrypted = encryptFor(address, paddedSync)
                    val messages = JSONArray().put(JSONObject().apply {
                        put("type", encrypted.first)
                        put("destinationDeviceId", deviceId)
                        put("destinationRegistrationId", encrypted.second)
                        put("content", encrypted.third)
                    })
                    val payload = JSONObject().apply {
                        put("timestamp", System.currentTimeMillis())
                        put("online", false)
                        put("urgent", false)
                        put("messages", messages)
                    }
                    ws.sendRequest(
                        "PUT",
                        "/v1/messages/$selfAci",
                        payload.toString().toByteArray(),
                        mapOf("Content-Type" to "application/json")
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send read receipt sync to device $deviceId", e)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send read receipt to $recipientAci", e)
        }
    }

    suspend fun sendGroupMessage(
        groupId: String,
        memberAcis: List<String>,
        content: SignalServiceProtos.Content,
        timestamp: Long,
    ): List<SendResult> {
        val paddedContent = padContent(content.toByteArray())
        val results = memberAcis.map { aci ->
            if (aci == selfAci) return@map SendResult(success = true)
            try {
                sendToRecipient(aci, paddedContent, timestamp, isUrgent(content))
                SendResult(success = true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send group message to $aci", e)
                SendResult(success = false, error = e.message)
            }
        }
        if (content.hasDataMessage() || content.hasEditMessage()) {
            try {
                sendSyncMessage(null, content, timestamp, groupId, memberAcis)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send group sync message", e)
            }
        }
        return results
    }

    private suspend fun sendToRecipient(
        recipientAci: String,
        paddedContent: ByteArray,
        timestamp: Long,
        urgent: Boolean = true,
        retryCount: Int = 0,
        useSealedSender: Boolean = true,
    ): Boolean {
        if (retryCount > 3) throw IllegalStateException("Too many retries sending to $recipientAci")

        val deviceIds = deviceManager.getDeviceIds(recipientAci)
        val messages = JSONArray()

        for (deviceId in deviceIds) {
            deviceManager.ensureSession(recipientAci, deviceId)
            val address = SignalProtocolAddress(recipientAci, deviceId)
            val encrypted = encryptFor(address, paddedContent)
            messages.put(JSONObject().apply {
                put("type", encrypted.first)
                put("destinationDeviceId", deviceId)
                put("destinationRegistrationId", encrypted.second)
                put("content", encrypted.third)
            })
        }

        val payload = JSONObject().apply {
            put("timestamp", timestamp)
            put("online", false)
            put("urgent", urgent)
            put("messages", messages)
        }

        var sentUnidentified = false
        val response = if (useSealedSender && unauthedWs != null && recipientAci != selfAci) {
            val profileKey = recipientStore?.getRecipient(recipientAci)?.profileKey
            if (profileKey != null) {
                sentUnidentified = true
                val accessKey = deriveAccessKey(profileKey)
                unauthedWs!!.sendRequest(
                    "PUT",
                    "/v1/messages/$recipientAci",
                    payload.toString().toByteArray(),
                    mapOf(
                        "Content-Type" to "application/json",
                        "Unidentified-Access-Key" to Base64.encodeToString(accessKey, Base64.NO_WRAP),
                    )
                )
            } else {
                ws.sendRequest(
                    "PUT",
                    "/v1/messages/$recipientAci",
                    payload.toString().toByteArray(),
                    mapOf("Content-Type" to "application/json")
                )
            }
        } else {
            ws.sendRequest(
                "PUT",
                "/v1/messages/$recipientAci",
                payload.toString().toByteArray(),
                mapOf("Content-Type" to "application/json")
            )
        }

        when (response.status) {
            200 -> return sentUnidentified
            409, 410 -> {
                Log.w(TAG, "Device mismatch (${response.status}) for $recipientAci")
                handleDeviceMismatch(recipientAci, response.body.toByteArray())
                return sendToRecipient(recipientAci, paddedContent, timestamp, urgent, retryCount + 1, useSealedSender)
            }
            428 -> {
                val retryAfter = try {
                    val body = String(response.body.toByteArray())
                    val json = JSONObject(body)
                    json.optLong("retry_after", 0)
                } catch (_: Exception) { 0L }
                Log.w(TAG, "Rate limited (428) for $recipientAci, retry after ${retryAfter}s")
                throw IllegalStateException("Got 428 rate limit error, retry after ${retryAfter}s")
            }
            401 -> {
                if (useSealedSender) {
                    Log.w(TAG, "Unauthorized (401) for $recipientAci, retrying without sealed sender")
                    return sendToRecipient(recipientAci, paddedContent, timestamp, urgent, retryCount + 1, useSealedSender = false)
                } else {
                    throw IllegalStateException("Send failed with status 401")
                }
            }
            404 -> {
                Log.w(TAG, "Recipient not found (404): $recipientAci, removing all sessions")
                val subDevices = sessionStore.getSubDeviceSessions(recipientAci)
                sessionStore.deleteSession(SignalProtocolAddress(recipientAci, 1))
                for (devId in subDevices) {
                    sessionStore.deleteSession(SignalProtocolAddress(recipientAci, devId))
                }
                recipientStore?.markUnregistered(recipientAci, true)
                throw RecipientUnregisteredException(recipientAci)
            }
            500, 503 -> {
                Log.w(TAG, "Server error (${response.status}) for $recipientAci, retrying")
                return sendToRecipient(recipientAci, paddedContent, timestamp, urgent, retryCount + 1, useSealedSender)
            }
            else -> throw IllegalStateException("Send failed with status ${response.status}")
        }
    }

    private fun encryptFor(
        address: SignalProtocolAddress,
        paddedContent: ByteArray,
    ): Triple<Int, Int, String> {
        val cipher = SessionCipher(protocolStore, address)
        val ciphertext = cipher.encrypt(paddedContent)
        val type = when (ciphertext.type) {
            CiphertextMessage.PREKEY_TYPE -> 3
            CiphertextMessage.WHISPER_TYPE -> 1
            CiphertextMessage.PLAINTEXT_CONTENT_TYPE -> 8
            else -> 0
        }
        val regId = protocolStore.loadSession(address).remoteRegistrationId
        return Triple(type, regId, Base64.encodeToString(ciphertext.serialize(), Base64.NO_WRAP))
    }

    private suspend fun sendSyncMessage(
        recipientAci: String?,
        content: SignalServiceProtos.Content,
        timestamp: Long,
        groupId: String? = null,
        memberAcis: List<String>? = null,
        unidentified: Boolean = false,
    ) {
        val sentBuilder = SignalServiceProtos.SyncMessage.Sent.newBuilder()
            .setTimestamp(timestamp)
            .setExpirationStartTimestamp(System.currentTimeMillis())

        if (content.hasDataMessage()) {
            sentBuilder.setMessage(content.dataMessage)
        }

        if (content.hasEditMessage()) {
            sentBuilder.setEditMessage(content.editMessage)
        }

        if (recipientAci != null) {
            sentBuilder.setDestinationServiceId(recipientAci)
            sentBuilder.addUnidentifiedStatus(
                SignalServiceProtos.SyncMessage.Sent.UnidentifiedDeliveryStatus.newBuilder()
                    .setDestinationServiceId(recipientAci)
                    .setUnidentified(unidentified)
                    .build()
            )
        }

        val rng = java.security.SecureRandom()
        val syncPadding = ByteArray(rng.nextInt(511) + 1)
        rng.nextBytes(syncPadding)

        val syncContent = SignalServiceProtos.Content.newBuilder()
            .setSyncMessage(
                SignalServiceProtos.SyncMessage.newBuilder()
                    .setSent(sentBuilder.build())
                    .setPadding(com.google.protobuf.ByteString.copyFrom(syncPadding))
                    .build()
            ).build()

        val paddedSync = padContent(syncContent.toByteArray())
        val selfDevices = deviceManager.getDeviceIds(selfAci)
        for (deviceId in selfDevices) {
            if (deviceId == selfDeviceId) continue
            try {
                deviceManager.ensureSession(selfAci, deviceId)
                val address = SignalProtocolAddress(selfAci, deviceId)
                val encrypted = encryptFor(address, paddedSync)
                val messages = JSONArray().put(JSONObject().apply {
                    put("type", encrypted.first)
                    put("destinationDeviceId", deviceId)
                    put("destinationRegistrationId", encrypted.second)
                    put("content", encrypted.third)
                })
                val payload = JSONObject().apply {
                    put("timestamp", timestamp)
                    put("online", false)
                    put("urgent", isSyncMessageUrgent(syncContent))
                    put("messages", messages)
                }
                ws.sendRequest(
                    "PUT",
                    "/v1/messages/$selfAci",
                    payload.toString().toByteArray(),
                    mapOf("Content-Type" to "application/json")
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send sync to device $deviceId", e)
            }
        }
    }

    private suspend fun handleDeviceMismatch(recipientAci: String, responseBody: ByteArray?) {
        if (responseBody == null) {
            deviceManager.refreshDevices(recipientAci)
            return
        }
        try {
            val json = JSONObject(String(responseBody))
            val staleDevices = json.optJSONArray("staleDevices")
            val missingDevices = json.optJSONArray("missingDevices")
            val extraDevices = json.optJSONArray("extraDevices")
            if (staleDevices != null) {
                for (i in 0 until staleDevices.length()) {
                    val deviceId = staleDevices.getInt(i)
                    val address = SignalProtocolAddress(recipientAci, deviceId)
                    sessionStore.deleteSession(address)
                    deviceManager.ensureSession(recipientAci, deviceId)
                }
            }
            if (missingDevices != null) {
                for (i in 0 until missingDevices.length()) {
                    deviceManager.ensureSession(recipientAci, missingDevices.getInt(i))
                }
            }
            if (extraDevices != null) {
                for (i in 0 until extraDevices.length()) {
                    val address = SignalProtocolAddress(recipientAci, extraDevices.getInt(i))
                    sessionStore.deleteSession(address)
                }
            }
            deviceManager.refreshDevices(recipientAci)
        } catch (e: Exception) {
            Log.w(TAG, "Error handling device mismatch response", e)
            deviceManager.refreshDevices(recipientAci)
        }
    }

    private fun deriveAccessKey(profileKey: ByteArray): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(profileKey, "HmacSHA256"))
        val full = mac.doFinal(ByteArray(32))
        return full.copyOfRange(0, 16)
    }

    suspend fun sendDeliveryReceipt(recipientAci: String, timestamps: List<Long>) {
        try {
            val content = ContentBuilders.deliveryReceipt(timestamps)
            sendMessage(recipientAci, content, System.currentTimeMillis())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send delivery receipt to $recipientAci", e)
        }
    }

    companion object {
        private const val TAG = "SignalSender"

        fun padContent(content: ByteArray): ByteArray {
            val messageLengthWithTerminator = content.size + 1
            val messagePartCount = (messageLengthWithTerminator + 159) / 160
            val paddedLength = messagePartCount * 160
            val padded = ByteArray(paddedLength)
            content.copyInto(padded)
            padded[content.size] = 0x80.toByte()
            return padded
        }

        private fun isUrgent(content: SignalServiceProtos.Content): Boolean {
            return when {
                content.hasDataMessage() -> true
                content.hasEditMessage() -> true
                content.hasCallMessage() -> true
                content.hasStoryMessage() -> true
                content.hasSyncMessage() -> isSyncMessageUrgent(content)
                else -> false
            }
        }

        private fun isSyncMessageUrgent(content: SignalServiceProtos.Content): Boolean {
            if (!content.hasSyncMessage()) return false
            val sync = content.syncMessage
            return sync.hasSent() || sync.hasRequest()
        }
    }
}
    data class SendResult(val success: Boolean, val error: String? = null, val unidentified: Boolean = false)
