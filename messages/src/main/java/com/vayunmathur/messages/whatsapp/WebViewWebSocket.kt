package com.vayunmathur.messages.whatsapp

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * WebView-based WebSocket client for WhatsApp Web.
 *
 * Uses Android WebView (Chromium) for browser-identical TLS fingerprint,
 * bypassing JA3 fingerprinting that blocks OkHttp connections.
 *
 * Implements full Noise_XX_25519_AESGCM_SHA256 handshake including ClientFinish
 * with proper ClientPayload protobuf.
 *
 * Reference: whatsmeow/handshake.go, socket/framesocket.go
 */
class WebViewWebSocket(
    private val context: Context,
    private val authData: WhatsAppAuthData?,
) {
    private companion object {
        const val TAG = "WebViewWebSocket"
        const val KEEPALIVE_INTERVAL_MIN_MS = 20_000L
        const val KEEPALIVE_INTERVAL_MAX_MS = 30_000L
        const val KEEPALIVE_RESPONSE_DEADLINE_MS = 10_000L
        const val KEEPALIVE_MAX_FAIL_MS = 180_000L
        const val RECONNECT_DELAY_MS = 5_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var webView: WebView? = null
    private var isConnected = false

    private val _messages = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
    val messages: SharedFlow<ByteArray> = _messages.asSharedFlow()

    private val _connectionState = MutableSharedFlow<ConnectionState>(extraBufferCapacity = 16)
    val connectionState: SharedFlow<ConnectionState> = _connectionState.asSharedFlow()

    sealed interface ConnectionState {
        data object Connecting : ConnectionState
        data object Connected : ConnectionState
        data class Disconnected(val reason: String) : ConnectionState
    }

    // Noise protocol state
    private var noiseHandshake: WhatsAppProtocol.NoiseHandshake? = null
    private var noiseSocket: NoiseSocket? = null
    private var isHandshakeComplete = false
    private var handshakeJob: Job? = null
    private var keepaliveJob: Job? = null
    private var ephemeralPrivateKey: ByteArray? = null
    private var ephemeralPublicKey: ByteArray? = null
    private var scriptInjected = false
    private var serverHeaderReceived = false
    private val iqCounter = AtomicInteger(0)
    private var reconnectJob: Job? = null
    private var lastKeepaliveSuccess = System.currentTimeMillis()

    private inner class WebSocketBridge {
        @JavascriptInterface
        fun onOpen() {
            Log.i(TAG, "WebSocket opened via WebView, starting Noise handshake")
            startNoiseHandshake()
        }

        @JavascriptInterface
        fun onMessage(data: String) {
            try {
                var bytes = Base64.decode(data, Base64.DEFAULT)
                if (!serverHeaderReceived) {
                    serverHeaderReceived = true
                    if (bytes.size >= WhatsAppProtocol.WA_CONN_HEADER.size &&
                        bytes[0] == 'W'.code.toByte() && bytes[1] == 'A'.code.toByte()) {
                        bytes = bytes.copyOfRange(WhatsAppProtocol.WA_CONN_HEADER.size, bytes.size)
                    }
                }
                scope.launch {
                    if (!isHandshakeComplete) {
                        val framePayload = WhatsAppProtocol.extractFrame(bytes)
                        handleHandshakeMessage(framePayload)
                    } else {
                        processIncomingFrames(bytes)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decode message", e)
            }
        }

        @JavascriptInterface
        fun onClose(code: Int, reason: String) {
            Log.i(TAG, "WebSocket closed: $code $reason")
            isConnected = false
            scope.launch { _connectionState.emit(ConnectionState.Disconnected("Closed: $reason")) }
            scheduleReconnect()
        }

        @JavascriptInterface
        fun onError(error: String) {
            Log.e(TAG, "WebSocket error: $error")
            scope.launch { _connectionState.emit(ConnectionState.Disconnected("Error: $error")) }
            scheduleReconnect()
        }

        @JavascriptInterface
        fun onLog(message: String) {
            Log.d(TAG, "JS: $message")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun connect() {
        Log.i(TAG, "Starting WebView WebSocket connection to ${WhatsAppProtocol.WS_URL}")
        scope.launch {
            _connectionState.emit(ConnectionState.Connecting)
            withContext(Dispatchers.Main) {
                webView = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

                    addJavascriptInterface(WebSocketBridge(), "Android")

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            if (!scriptInjected) {
                                scriptInjected = true
                                injectWebSocketScript()
                            }
                        }

                        override fun onReceivedError(
                            view: WebView?, errorCode: Int, description: String?, failingUrl: String?
                        ) {
                            super.onReceivedError(view, errorCode, description, failingUrl)
                            Log.e(TAG, "WebView error: $errorCode $description")
                            scope.launch {
                                _connectionState.emit(ConnectionState.Disconnected("WebView error: $description"))
                            }
                        }
                    }

                    loadDataWithBaseURL(
                        "https://web.whatsapp.com",
                        "<html><body></body></html>",
                        "text/html",
                        "UTF-8",
                        null
                    )
                }
            }
        }
    }

    private fun injectWebSocketScript() {
        val js = """
            (function() {
                Android.onLog('Injecting WebSocket script');
                try {
                    const ws = new WebSocket('${WhatsAppProtocol.WS_URL}');
                    ws.binaryType = 'arraybuffer';
                    ws.onopen = function() {
                        Android.onLog('WebSocket opened');
                        Android.onOpen();
                    };
                    ws.onmessage = function(event) {
                        if (event.data instanceof ArrayBuffer) {
                            const bytes = new Uint8Array(event.data);
                            let binary = '';
                            for (let i = 0; i < bytes.length; i++) {
                                binary += String.fromCharCode(bytes[i]);
                            }
                            Android.onMessage(btoa(binary));
                        }
                    };
                    ws.onclose = function(event) {
                        Android.onClose(event.code, event.reason || '');
                    };
                    ws.onerror = function() {
                        Android.onError('WebSocket error');
                    };
                    window.whatsappWS = ws;
                } catch (e) {
                    Android.onError('Exception: ' + e.message);
                }
            })();
        """.trimIndent()

        webView?.evaluateJavascript(js, null)
    }

    fun disconnect() {
        reconnectJob?.cancel()
        scope.launch {
            withContext(Dispatchers.Main) {
                webView?.evaluateJavascript("if (window.whatsappWS) window.whatsappWS.close();", null)
                webView?.destroy()
                webView = null
            }
            isConnected = false
            isHandshakeComplete = false
            scriptInjected = false
            serverHeaderReceived = false
            noiseHandshake = null
            noiseSocket = null
            handshakeJob?.cancel()
            keepaliveJob?.cancel()
            _connectionState.emit(ConnectionState.Disconnected("Client disconnect"))
        }
    }

    private fun startNoiseHandshake() {
        handshakeJob?.cancel()
        handshakeJob = scope.launch {
            try {
                noiseHandshake = WhatsAppProtocol.NoiseHandshake().apply {
                    start(WhatsAppProtocol.NOISE_START_PATTERN, WhatsAppProtocol.WA_CONN_HEADER)
                }

                val (ephPriv, ephPub) = WhatsAppProtocol.generateX25519KeyPair()
                ephemeralPrivateKey = ephPriv
                ephemeralPublicKey = ephPub

                noiseHandshake?.authenticate(ephPub)

                val handshakeMessage = com.vayunmathur.messages.whatsapp.proto.WhatsAppHandshakeProto.HandshakeMessage.newBuilder()
                    .setClientHello(
                        com.vayunmathur.messages.whatsapp.proto.WhatsAppHandshakeProto.HandshakeMessage.ClientHello.newBuilder()
                            .setEphemeral(com.google.protobuf.ByteString.copyFrom(ephPub))
                            .build()
                    )
                    .build()
                val clientHelloBytes = handshakeMessage.toByteArray()

                val framedMessage = WhatsAppProtocol.buildFramedMessage(clientHelloBytes, WhatsAppProtocol.WA_CONN_HEADER)
                sendRaw(framedMessage)

                Log.i(TAG, "Sent ClientHello (${clientHelloBytes.size} bytes)")
            } catch (e: Exception) {
                Log.e(TAG, "Handshake failed", e)
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
                Log.e(TAG, "Invalid ServerHello: missing fields")
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
            Log.d(TAG, "Server cert decrypted (${certDecrypted.size} bytes)")

            // Send ClientFinish (whatsmeow/handshake.go lines 89-119)
            val noiseKeyPair = if (authData != null) {
                Pair(
                    Base64.decode(authData.noisePrivateKey, Base64.NO_WRAP),
                    Base64.decode(authData.noisePublicKey, Base64.NO_WRAP)
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
            val framedFinish = WhatsAppProtocol.buildFramedMessage(clientFinishBytes, null)
            sendRaw(framedFinish)

            Log.i(TAG, "Sent ClientFinish (${clientFinishBytes.size} bytes)")

            // Derive final encryption keys
            val (writeKey, readKey) = handshake.finish()
            noiseSocket = NoiseSocket(writeKey, readKey)
            isHandshakeComplete = true
            isConnected = true

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
            val widUser = authData.wid.substringBefore("@")
            payloadBuilder.username = widUser.toLongOrNull() ?: 0L
            payloadBuilder.device = authData.deviceId
            payloadBuilder.passive = true
            payloadBuilder.pull = true
            payloadBuilder.lidDbMigrated = true
            payloadBuilder.lc = 1
        } else {
            payloadBuilder.passive = false
            payloadBuilder.pull = false
        }

        return payloadBuilder.build().toByteArray()
    }

    /**
     * Process incoming post-handshake frames.
     * Strips 3-byte length prefix, decrypts with NoiseSocket, emits plaintext.
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

    private fun sendRaw(data: ByteArray): Boolean {
        return try {
            val base64 = Base64.encodeToString(data, Base64.NO_WRAP)
            val js = """
                (function() {
                    if (window.whatsappWS && window.whatsappWS.readyState === WebSocket.OPEN) {
                        const binary = atob('$base64');
                        const bytes = new Uint8Array(binary.length);
                        for (let i = 0; i < binary.length; i++) {
                            bytes[i] = binary.charCodeAt(i);
                        }
                        window.whatsappWS.send(bytes.buffer);
                        return true;
                    }
                    return false;
                })();
            """.trimIndent()

            var result = false
            webView?.evaluateJavascript(js) { value -> result = value == "true" }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send raw", e)
            false
        }
    }

    fun send(data: ByteArray): Boolean {
        if (!isConnected || !isHandshakeComplete) return false
        val socket = noiseSocket ?: return false

        return try {
            val encrypted = socket.encrypt(data)
            val framed = WhatsAppProtocol.buildFramedMessage(encrypted, null)
            sendRaw(framed)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send encrypted", e)
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
        if (authData == null) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(RECONNECT_DELAY_MS)
            Log.i(TAG, "Attempting reconnect")
            connect()
        }
    }

    /**
     * Post-handshake encrypted socket. No AAD post-handshake.
     * IV: 12 bytes with counter in last 4 bytes (big-endian).
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
