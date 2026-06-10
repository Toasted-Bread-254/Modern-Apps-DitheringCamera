package com.vayunmathur.messages.signal.receiving

import android.util.Base64
import android.util.Log
import com.vayunmathur.messages.signal.proto.SignalServiceProtos

data class DecryptedMessage(
    val senderAci: String,
    val senderDeviceId: Int,
    val timestamp: Long,
    val serverTimestamp: Long,
    val content: MessageContent,
)

sealed interface MessageContent {
    data class TextMessage(val body: String, val timestamp: Long, val groupId: String? = null, val expireTimer: Int = 0, val isViewOnce: Boolean = false) : MessageContent
    data class Reaction(val emoji: String, val targetTimestamp: Long, val remove: Boolean, val targetAuthorAci: String? = null, val groupId: String? = null) : MessageContent
    data class Delete(val targetTimestamp: Long, val groupId: String? = null) : MessageContent
    data class Edit(val targetTimestamp: Long, val newBody: String, val groupId: String? = null) : MessageContent
    data class ReadReceipt(val timestamps: List<Long>) : MessageContent
    data class ViewedReceipt(val timestamps: List<Long>) : MessageContent
    data class DeliveryReceipt(val timestamps: List<Long>) : MessageContent
    data class Typing(val isTyping: Boolean, val groupId: String? = null) : MessageContent
    data class SyncSent(val destinationAci: String?, val message: MessageContent?, val timestamp: Long) : MessageContent
    data class SyncRead(val messages: List<Pair<String, Long>>) : MessageContent
    data class SyncKeys(val masterKey: ByteArray?, val accountEntropyPool: String?) : MessageContent
    data class SyncFetchLatest(val type: String) : MessageContent
    data class SyncDeleteForMe(val timestamp: Long) : MessageContent
    data class SyncMessageRequestResponse(val threadAci: String?, val groupId: ByteArray?, val type: String) : MessageContent
    data class Call(val isRinging: Boolean, val groupId: String? = null) : MessageContent
    data class GroupCallUpdate(val eraId: String?, val groupId: String?) : MessageContent
    data class Attachment(val body: String?, val attachments: List<AttachmentPointer>, val groupId: String? = null, val expireTimer: Int = 0, val isViewOnce: Boolean = false) : MessageContent
    data class Sticker(val packId: ByteArray, val packKey: ByteArray, val stickerId: Int, val emoji: String?, val groupId: String? = null) : MessageContent
    data class ProfileKeyUpdate(val profileKey: ByteArray, val senderAci: String) : MessageContent
    data class Unknown(val description: String) : MessageContent
}

data class AttachmentPointer(
    val cdnId: Long,
    val cdnKey: String,
    val contentType: String,
    val key: ByteArray,
    val digest: ByteArray,
    val size: Int,
    val fileName: String?,
)

object ContentDispatcher {

    private const val TAG = "SignalReceiver"

    fun dispatch(
        senderAci: String,
        senderDeviceId: Int,
        content: SignalServiceProtos.Content,
        timestamp: Long,
        serverTimestamp: Long,
        selfAci: String? = null,
    ): DecryptedMessage? {
        if (content.hasDataMessage() && content.dataMessage.profileKey.size() > 0) {
            // Profile key updates are handled by the caller
        }

        val messageContent = when {
            content.hasSyncMessage() -> {
                if (selfAci != null && senderAci != selfAci) {
                    Log.w(TAG, "Ignoring sync message from non-self sender $senderAci")
                    null
                } else {
                    dispatchSync(content.syncMessage, timestamp)
                }
            }
            content.hasDataMessage() -> dispatchData(content.dataMessage, timestamp, senderAci)
            content.hasEditMessage() -> dispatchEdit(content.editMessage)
            content.hasReceiptMessage() -> dispatchReceipt(content.receiptMessage)
            content.hasTypingMessage() -> dispatchTyping(content.typingMessage)
            content.hasCallMessage() -> dispatchCall(content.callMessage)
            content.hasStoryMessage() -> null
            content.hasNullMessage() -> null
            else -> MessageContent.Unknown("Unrecognized content")
        } ?: return null

        return DecryptedMessage(
            senderAci = senderAci,
            senderDeviceId = senderDeviceId,
            timestamp = timestamp,
            serverTimestamp = serverTimestamp,
            content = messageContent,
        )
    }

    private fun dispatchData(data: SignalServiceProtos.DataMessage, timestamp: Long, senderAci: String = ""): MessageContent {
        val groupId = extractGroupId(data)

        if (data.hasDelete()) {
            return MessageContent.Delete(data.delete.targetSentTimestamp, groupId)
        }

        if (data.hasReaction()) {
            val r = data.reaction
            return MessageContent.Reaction(
                r.emoji, r.targetSentTimestamp, r.remove,
                targetAuthorAci = if (r.hasTargetAuthorAci()) r.targetAuthorAci else null,
                groupId = groupId,
            )
        }

        if (data.hasSticker()) {
            val s = data.sticker
            return MessageContent.Sticker(
                packId = s.packId.toByteArray(),
                packKey = s.packKey.toByteArray(),
                stickerId = s.stickerId,
                emoji = if (s.hasEmoji()) s.emoji else null,
                groupId = groupId,
            )
        }

        if (data.attachmentsCount > 0) {
            val pointers = data.attachmentsList.map { a ->
                AttachmentPointer(
                    cdnId = a.cdnId,
                    cdnKey = a.cdnKey,
                    contentType = a.contentType,
                    key = a.key.toByteArray(),
                    digest = a.digest.toByteArray(),
                    size = a.size,
                    fileName = if (a.hasFileName()) a.fileName else null,
                )
            }
            return MessageContent.Attachment(
                body = if (data.hasBody()) data.body else null,
                attachments = pointers,
                groupId = groupId,
                expireTimer = if (data.hasExpireTimer()) data.expireTimer else 0,
                isViewOnce = if (data.hasIsViewOnce()) data.isViewOnce else false,
            )
        }

        if (data.hasGroupCallUpdate()) {
            return MessageContent.GroupCallUpdate(
                eraId = if (data.groupCallUpdate.hasEraId()) data.groupCallUpdate.eraId else null,
                groupId = groupId,
            )
        }

        if (data.hasBody()) {
            return MessageContent.TextMessage(
                data.body, data.timestamp, groupId,
                expireTimer = if (data.hasExpireTimer()) data.expireTimer else 0,
                isViewOnce = if (data.hasIsViewOnce()) data.isViewOnce else false,
            )
        }

        return MessageContent.Unknown("DataMessage with no recognized fields")
    }

    private fun dispatchEdit(edit: SignalServiceProtos.EditMessage): MessageContent {
        val newBody = if (edit.hasDataMessage() && edit.dataMessage.hasBody()) {
            edit.dataMessage.body
        } else {
            ""
        }
        val groupId = if (edit.hasDataMessage()) extractGroupId(edit.dataMessage) else null
        return MessageContent.Edit(edit.targetSentTimestamp, newBody, groupId)
    }

    private fun dispatchSync(sync: SignalServiceProtos.SyncMessage, timestamp: Long): MessageContent {
        if (sync.hasSent()) {
            val sent = sync.sent
            val innerContent = when {
                sent.hasEditMessage() && sent.editMessage.hasDataMessage() -> {
                    val edit = sent.editMessage
                    MessageContent.Edit(
                        edit.targetSentTimestamp,
                        if (edit.dataMessage.hasBody()) edit.dataMessage.body else "",
                        if (edit.hasDataMessage()) extractGroupId(edit.dataMessage) else null,
                    )
                }
                sent.hasMessage() -> dispatchData(sent.message, sent.timestamp)
                else -> null
            }
            return MessageContent.SyncSent(
                destinationAci = if (sent.hasDestinationServiceId()) sent.destinationServiceId else null,
                message = innerContent,
                timestamp = sent.timestamp,
            )
        }

        if (sync.hasKeys()) {
            val keys = sync.keys
            val masterKey = if (keys.hasAccountEntropyPool() && keys.accountEntropyPool.isNotEmpty()) {
                try {
                    val aep = keys.accountEntropyPool
                    val hkdf = org.signal.libsignal.protocol.hkdf.HKDF.createFor(3)
                    hkdf.deriveSecrets(aep.toByteArray(), "20231031_Signal_Accounts_MasterKey".toByteArray(), 32)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to derive master key from AEP", e)
                    null
                }
            } else if (keys.hasMaster() && keys.master.size() == 32) {
                keys.master.toByteArray()
            } else null
            return MessageContent.SyncKeys(
                masterKey = masterKey,
                accountEntropyPool = if (keys.hasAccountEntropyPool()) keys.accountEntropyPool else null,
            )
        }

        if (sync.hasFetchLatest()) {
            return MessageContent.SyncFetchLatest(sync.fetchLatest.type.name)
        }

        if (sync.hasDeleteForMe()) {
            return MessageContent.SyncDeleteForMe(timestamp)
        }

        if (sync.hasMessageRequestResponse()) {
            val mrr = sync.messageRequestResponse
            return MessageContent.SyncMessageRequestResponse(
                threadAci = if (mrr.hasThreadAci()) mrr.threadAci else null,
                groupId = if (mrr.hasGroupId()) mrr.groupId.toByteArray() else null,
                type = mrr.type.name,
            )
        }

        if (sync.hasContacts()) {
            return MessageContent.Unknown("Contacts sync")
        }

        if (sync.readCount > 0) {
            val reads = sync.readList.map { it.senderAci to it.timestamp }
            return MessageContent.SyncRead(reads)
        }

        return MessageContent.Unknown("SyncMessage with no recognized fields")
    }

    private fun dispatchReceipt(receipt: SignalServiceProtos.ReceiptMessage): MessageContent {
        val timestamps = receipt.timestampList.map { it }
        return when (receipt.type) {
            SignalServiceProtos.ReceiptMessage.Type.READ -> MessageContent.ReadReceipt(timestamps)
            SignalServiceProtos.ReceiptMessage.Type.VIEWED -> MessageContent.ViewedReceipt(timestamps)
            SignalServiceProtos.ReceiptMessage.Type.DELIVERY -> MessageContent.DeliveryReceipt(timestamps)
            else -> {
                Log.w("SignalReceiver", "Unknown receipt type: ${receipt.type}")
                MessageContent.DeliveryReceipt(timestamps)
            }
        }
    }

    private fun dispatchTyping(typing: SignalServiceProtos.TypingMessage): MessageContent {
        val isTyping = typing.action == SignalServiceProtos.TypingMessage.Action.STARTED
        val groupId = if (typing.hasGroupId()) Base64.encodeToString(typing.groupId.toByteArray(), Base64.NO_WRAP) else null
        return MessageContent.Typing(isTyping, groupId)
    }

    private fun dispatchCall(call: SignalServiceProtos.CallMessage): MessageContent? {
        if (!call.hasOffer() && !call.hasHangup()) return null
        return MessageContent.Call(isRinging = call.hasOffer())
    }

    private fun extractGroupId(data: SignalServiceProtos.DataMessage): String? {
        if (!data.hasGroupV2()) return null
        return try {
            val masterKey = data.groupV2.masterKey.toByteArray()
            val groupMasterKey = org.signal.libsignal.zkgroup.groups.GroupMasterKey(masterKey)
            val groupSecretParams = org.signal.libsignal.zkgroup.groups.GroupSecretParams.deriveFromMasterKey(groupMasterKey)
            val groupId = groupSecretParams.publicParams.groupIdentifier.serialize()
            Base64.encodeToString(groupId, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract groupId", e)
            null
        }
    }
}
