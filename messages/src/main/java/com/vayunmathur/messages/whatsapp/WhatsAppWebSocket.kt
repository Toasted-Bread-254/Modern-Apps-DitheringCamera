package com.vayunmathur.messages.whatsapp

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * OkHttp WebSocket client for WhatsApp Web.
 * Handles Noise_XX_25519_AESGCM_SHA256 handshake, frame processing,
 * and encrypted post-handshake communication.
 *
 * Frame format (from whatsmeow/socket/framesocket.go):
 *   [header?][3-byte big-endian length][payload]
 *   Header ('WA' + version) is sent only on the first frame.
 *
 * Reference: whatsmeow/socket/framesocket.go, noisehandshake.go, noisesocket.go
 */
class WhatsAppWebSocket(
    private val authData: WhatsAppAuthData?,
) {
    private companion object {
        const val TAG = "WhatsAppWebSocket"
        const val KEEPALIVE_INTERVAL_MIN_MS = 20_000L
        const val KEEPALIVE_INTERVAL_MAX_MS = 30_000L
        const val KEEPALIVE_RESPONSE_DEADLINE_MS = 10_000L
        const val KEEPALIVE_MAX_FAIL_MS = 180_000L
        const val RECONNECT_DELAY_MS = 5_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // indefinite read for WebSocket
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var keepaliveJob: Job? = null
    private var handshakeJob: Job? = null

    // Noise protocol state
    private var noiseHandshake: WhatsAppProtocol.NoiseHandshake? = null
    private var noiseSocket: NoiseSocket? = null
    private var isHandshakeComplete = false
    private var ephemeralPrivateKey: ByteArray? = null
    private var headerSent = false
    private var serverHeaderReceived = false

    // IQ request counter for keepalive
    private val iqCounter = AtomicInteger(0)
    private var lastKeepaliveSuccess = System.currentTimeMillis()

    private val _messages = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
    val messages: SharedFlow<ByteArray> = _messages.asSharedFlow()

    private val _connectionState = MutableSharedFlow<ConnectionState>(extraBufferCapacity = 16)
    val connectionState: SharedFlow<ConnectionState> = _connectionState.asSharedFlow()

    sealed interface ConnectionState {
        data object Connecting : ConnectionState
        data object Connected : ConnectionState
        data class Disconnected(val reason: String) : ConnectionState
    }

    fun connect() {
        scope.launch { _connectionState.emit(ConnectionState.Connecting) }
        headerSent = false
        serverHeaderReceived = false

        val request = Request.Builder()
            .url(WhatsAppProtocol.WS_URL)
            .header("Origin", WhatsAppProtocol.WS_ORIGIN)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected, starting Noise handshake")
                startHandshake()
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                var rawData = bytes.toByteArray()
                if (!serverHeaderReceived) {
                    serverHeaderReceived = true
                    if (rawData.size >= WhatsAppProtocol.WA_CONN_HEADER.size &&
                        rawData[0] == 'W'.code.toByte() && rawData[1] == 'A'.code.toByte()) {
                        rawData = rawData.copyOfRange(WhatsAppProtocol.WA_CONN_HEADER.size, rawData.size)
                    }
                }
                scope.launch {
                    if (!isHandshakeComplete) {
                        val framePayload = WhatsAppProtocol.extractFrame(rawData)
                        handleHandshakeMessage(framePayload)
                    } else {
                        processIncomingFrames(rawData)
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Received text message (unexpected): $text")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: $code $reason")
                scope.launch { _connectionState.emit(ConnectionState.Disconnected("Closed: $reason")) }
                cleanup()
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure", t)
                scope.launch { _connectionState.emit(ConnectionState.Disconnected("Failure: ${t.message}")) }
                cleanup()
                scheduleReconnect()
            }
        })
    }

    private fun startHandshake() {
        handshakeJob?.cancel()
        handshakeJob = scope.launch {
            try {
                noiseHandshake = WhatsAppProtocol.NoiseHandshake().apply {
                    start(WhatsAppProtocol.NOISE_START_PATTERN, WhatsAppProtocol.WA_CONN_HEADER)
                }

                val (ephPriv, ephPub) = WhatsAppProtocol.generateX25519KeyPair()
                ephemeralPrivateKey = ephPriv

                noiseHandshake?.authenticate(ephPub)

                val handshakeMessage = com.vayunmathur.messages.whatsapp.proto.WhatsAppHandshakeProto.HandshakeMessage.newBuilder()
                    .setClientHello(
                        com.vayunmathur.messages.whatsapp.proto.WhatsAppHandshakeProto.HandshakeMessage.ClientHello.newBuilder()
                            .setEphemeral(com.google.protobuf.ByteString.copyFrom(ephPub))
                            .build()
                    )
                    .build()
                val clientHelloBytes = handshakeMessage.toByteArray()

                val framedMessage = WhatsAppProtocol.buildFramedMessage(
                    clientHelloBytes,
                    WhatsAppProtocol.WA_CONN_HEADER
                )
                headerSent = true
                webSocket?.send(ByteString.of(*framedMessage))

                Log.i(TAG, "Sent ClientHello (${clientHelloBytes.size} bytes)")
            } catch (e: Exception) {
                Log.e(TAG, "Handshake initiation failed", e)
                scope.launch { _connectionState.emit(ConnectionState.Disconnected("Handshake failed: ${e.message}")) }
                disconnect()
            }
        }
    }

    private fun handleHandshakeMessage(data: ByteArray) {
        try {
            val handshakeMessage = com.vayunmathur.messages.whatsapp.proto.WhatsAppHandshakeProto.HandshakeMessage.parseFrom(data)
            val serverHello = handshakeMessage.serverHello
            if (serverHello == null) {
                Log.e(TAG, "ServerHello is null")
                scope.launch { _connectionState.emit(ConnectionState.Disconnected("Invalid ServerHello")) }
                disconnect()
                return
            }

            val handshake = noiseHandshake ?: return
            val ephPriv = ephemeralPrivateKey ?: return

            val serverEphemeral = serverHello.ephemeral.toByteArray()
            val serverStaticCiphertext = serverHello.getStatic().toByteArray()
            val certificateCiphertext = serverHello.payload.toByteArray()

            if (serverEphemeral.size != 32 || serverStaticCiphertext.isEmpty() || certificateCiphertext.isEmpty()) {
                Log.e(TAG, "Missing parts of ServerHello")
                disconnect()
                return
            }

            // Process ServerHello (whatsmeow/handshake.go lines 65-87)
            handshake.authenticate(serverEphemeral)
            handshake.mixSharedSecretIntoKey(ephPriv, serverEphemeral)

            val staticDecrypted = handshake.decrypt(serverStaticCiphertext)
            if (staticDecrypted.size != 32) {
                Log.e(TAG, "Invalid static key length: ${staticDecrypted.size}")
                disconnect()
                return
            }

            handshake.mixSharedSecretIntoKey(ephPriv, staticDecrypted)

            val certDecrypted = handshake.decrypt(certificateCiphertext)
            Log.d(TAG, "Certificate decrypted (${certDecrypted.size} bytes)")

            // Send ClientFinish (whatsmeow/handshake.go lines 89-119)
            val noiseKeyPair = if (authData != null) {
                Pair(
                    android.util.Base64.decode(authData.noisePrivateKey, android.util.Base64.NO_WRAP),
                    android.util.Base64.decode(authData.noisePublicKey, android.util.Base64.NO_WRAP)
                )
            } else {
                WhatsAppProtocol.generateX25519KeyPair()
            }
            val (noisePriv, noisePub) = noiseKeyPair

            val encryptedPubkey = handshake.encrypt(noisePub)
            handshake.mixSharedSecretIntoKey(noisePriv, serverEphemeral)

            val clientPayloadBytes = buildClientPayload()
            val encryptedPayload = handshake.encrypt(clientPayloadBytes)

            val clientFinishMessage = com.vayunmathur.messages.whatsapp.proto.WhatsAppHandshakeProto.HandshakeMessage.newBuilder()
                .setClientFinish(
                    com.vayunmathur.messages.whatsapp.proto.WhatsAppHandshakeProto.HandshakeMessage.ClientFinish.newBuilder()
                        .setStatic(com.google.protobuf.ByteString.copyFrom(encryptedPubkey))
                        .setPayload(com.google.protobuf.ByteString.copyFrom(encryptedPayload))
                        .build()
                )
                .build()
            val clientFinishBytes = clientFinishMessage.toByteArray()

            // No header on subsequent frames
            val framedFinish = WhatsAppProtocol.buildFramedMessage(clientFinishBytes, null)
            webSocket?.send(ByteString.of(*framedFinish))
            Log.i(TAG, "Sent ClientFinish (${clientFinishBytes.size} bytes)")

            // Derive final encryption keys
            val (writeKey, readKey) = handshake.finish()
            noiseSocket = NoiseSocket(writeKey, readKey)
            isHandshakeComplete = true

            Log.i(TAG, "Noise handshake complete")
            scope.launch { _connectionState.emit(ConnectionState.Connected) }
            startKeepalive()

        } catch (e: Exception) {
            Log.e(TAG, "Handshake processing failed", e)
            scope.launch { _connectionState.emit(ConnectionState.Disconnected("Handshake failed: ${e.message}")) }
            disconnect()
        }
    }

    private fun buildClientPayload(): ByteArray {
        val versionProto = com.vayunmathur.messages.whatsapp.proto.WhatsAppPayloadProto.ClientPayload.UserAgent.AppVersion.newBuilder()
            .setPrimary(WhatsAppProtocol.WA_VERSION[0])
            .setSecondary(WhatsAppProtocol.WA_VERSION[1])
            .setTertiary(WhatsAppProtocol.WA_VERSION[2])
            .build()

        val userAgent = com.vayunmathur.messages.whatsapp.proto.WhatsAppPayloadProto.ClientPayload.UserAgent.newBuilder()
            .setPlatform(com.vayunmathur.messages.whatsapp.proto.WhatsAppPayloadProto.ClientPayload.UserAgent.Platform.WEB)
            .setReleaseChannel(com.vayunmathur.messages.whatsapp.proto.WhatsAppPayloadProto.ClientPayload.UserAgent.ReleaseChannel.RELEASE)
            .setAppVersion(versionProto)
            .setMcc("000")
            .setMnc("000")
            .setOsVersion("0.1")
            .setManufacturer("")
            .setDevice("Desktop")
            .setOsBuildNumber("0.1")
            .setLocaleLanguageIso6391("en")
            .setLocaleCountryIso31661Alpha2("US")
            .build()

        val webInfo = com.vayunmathur.messages.whatsapp.proto.WhatsAppPayloadProto.ClientPayload.WebInfo.newBuilder()
            .setWebSubPlatform(com.vayunmathur.messages.whatsapp.proto.WhatsAppPayloadProto.ClientPayload.WebInfo.WebSubPlatform.WEB_BROWSER)
            .build()

        val payloadBuilder = com.vayunmathur.messages.whatsapp.proto.WhatsAppPayloadProto.ClientPayload.newBuilder()
            .setUserAgent(userAgent)
            .setWebInfo(webInfo)
            .setConnectType(com.vayunmathur.messages.whatsapp.proto.WhatsAppPayloadProto.ClientPayload.ConnectType.WIFI_UNKNOWN)
            .setConnectReason(com.vayunmathur.messages.whatsapp.proto.WhatsAppPayloadProto.ClientPayload.ConnectReason.USER_ACTIVATED)

        if (authData != null) {
            // Login payload (existing device)
            val widUser = authData.wid.substringBefore("@")
            payloadBuilder.username = widUser.toLongOrNull() ?: 0L
            payloadBuilder.device = authData.deviceId
            payloadBuilder.passive = true
            payloadBuilder.pull = true
            payloadBuilder.lidDbMigrated = true
            payloadBuilder.lc = 1
        } else {
            // Registration payload (new device pairing)
            payloadBuilder.passive = false
            payloadBuilder.pull = false
        }

        return payloadBuilder.build().toByteArray()
    }

    /**
     * Process incoming post-handshake frames.
     * Strips 3-byte length prefix, decrypts, and emits decoded node.
     */
    private suspend fun processIncomingFrames(rawData: ByteArray) {
        var data = rawData
        while (data.size >= WhatsAppProtocol.FRAME_LENGTH_SIZE) {
            val length = ((data[0].toInt() and 0xFF) shl 16) or
                    ((data[1].toInt() and 0xFF) shl 8) or
                    (data[2].toInt() and 0xFF)
            if (data.size < WhatsAppProtocol.FRAME_LENGTH_SIZE + length) break

            val frameData = data.copyOfRange(WhatsAppProtocol.FRAME_LENGTH_SIZE, WhatsAppProtocol.FRAME_LENGTH_SIZE + length)
            data = data.copyOfRange(WhatsAppProtocol.FRAME_LENGTH_SIZE + length, data.size)

            noiseSocket?.let { socket ->
                try {
                    val plaintext = socket.decrypt(frameData)
                    _messages.emit(plaintext)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decrypt frame", e)
                }
            }
        }
    }

    fun disconnect() {
        cleanup()
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
    }

    private fun cleanup() {
        keepaliveJob?.cancel()
        reconnectJob?.cancel()
        handshakeJob?.cancel()
        isHandshakeComplete = false
        noiseHandshake = null
        noiseSocket = null
        ephemeralPrivateKey = null
        serverHeaderReceived = false
    }

    fun send(data: ByteArray): Boolean {
        if (!isHandshakeComplete) return false
        val socket = noiseSocket ?: return false
        return try {
            val encrypted = socket.encrypt(data)
            val framed = WhatsAppProtocol.buildFramedMessage(encrypted, null)
            webSocket?.send(ByteString.of(*framed)) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send", e)
            false
        }
    }

    private fun startKeepalive() {
        keepaliveJob?.cancel()
        lastKeepaliveSuccess = System.currentTimeMillis()
        keepaliveJob = scope.launch {
            while (true) {
                delay(KEEPALIVE_INTERVAL_MIN_MS + (Math.random() * (KEEPALIVE_INTERVAL_MAX_MS - KEEPALIVE_INTERVAL_MIN_MS)).toLong())
                val id = "keepalive-${iqCounter.incrementAndGet()}"
                val keepaliveNode = WhatsAppProtocol.buildKeepalive(id)
                val encoded = WhatsAppProtocol.encodeNode(keepaliveNode)
                val sent = send(encoded)
                if (!sent) {
                    Log.w(TAG, "Failed to send keepalive")
                    if (System.currentTimeMillis() - lastKeepaliveSuccess > KEEPALIVE_MAX_FAIL_MS) {
                        Log.w(TAG, "Keepalive failed for too long, forcing reconnect")
                        disconnect()
                        scheduleReconnect()
                        return@launch
                    }
                } else {
                    lastKeepaliveSuccess = System.currentTimeMillis()
                }
            }
        }
    }

    private fun scheduleReconnect() {
        if (authData == null) {
            Log.w(TAG, "No auth data, not reconnecting")
            return
        }
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(RECONNECT_DELAY_MS)
            Log.i(TAG, "Attempting reconnect")
            connect()
        }
    }

    /**
     * Post-handshake encrypted socket using AES-256-GCM with counter-based IVs.
     * No AAD is used post-handshake (unlike during handshake where hash is AAD).
     * From whatsmeow/socket/noisesocket.go
     */
    private inner class NoiseSocket(
        private val writeKey: javax.crypto.spec.SecretKeySpec,
        private val readKey: javax.crypto.spec.SecretKeySpec,
    ) {
        private var writeCounter: UInt = 0u
        private var readCounter: UInt = 0u

        private fun generateIV(counter: UInt): ByteArray {
            val iv = ByteArray(12)
            java.nio.ByteBuffer.wrap(iv, 8, 4)
                .order(java.nio.ByteOrder.BIG_ENDIAN)
                .putInt(counter.toInt())
            return iv
        }

        fun encrypt(plaintext: ByteArray): ByteArray {
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            val spec = javax.crypto.spec.GCMParameterSpec(128, generateIV(writeCounter))
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, writeKey, spec)
            val ciphertext = cipher.doFinal(plaintext)
            writeCounter++
            return ciphertext
        }

        fun decrypt(ciphertext: ByteArray): ByteArray {
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            val spec = javax.crypto.spec.GCMParameterSpec(128, generateIV(readCounter))
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, readKey, spec)
            val plaintext = cipher.doFinal(ciphertext)
            readCounter++
            return plaintext
        }
    }
}
