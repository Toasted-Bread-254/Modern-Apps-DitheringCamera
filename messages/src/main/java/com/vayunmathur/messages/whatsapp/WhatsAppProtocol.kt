package com.vayunmathur.messages.whatsapp

import android.util.Base64
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json as KotlinJson
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.digests.SHA256Digest

/**
 * WhatsApp Web protocol implementation.
 * Handles Noise protocol handshake, message encoding/decoding, and binary protocol.
 * 
 * Implements Noise_XX_25519_AESGCM_SHA256 handshake as per:
 * https://github.com/tulir/whatsmeow/blob/main/handshake.go
 */
object WhatsAppProtocol {
    private const val TAG = "WhatsAppProtocol"

    // WhatsApp Web WebSocket URL
    const val WS_URL = "wss://web.whatsapp.com/ws/chat"

    // Protocol constants
    private const val NOISE_PROTOCOL = "Noise_XX_25519_AESGCM_SHA256\u0000\u0000\u0000\u0000"
    private const val WA_HEADER = "WA"
    private const val WA_VERSION = "2.3000.1017131629"
    
    // WhatsApp certificate authority public key (Ed25519)
    // From whatsmeow/handshake.go line 27
    private val WA_CERT_PUBKEY = byteArrayOf(
        0x14, 0x23, 0x75, 0x57, 0x4d, 0x0a, 0x58, 0x71, 0x66, 0xaa.toByte(), 0xe7.toByte(), 0x1e, 0xbe.toByte(), 0x51, 0x64, 0x37,
        0xc4.toByte(), 0xa2.toByte(), 0x8b.toByte(), 0x73, 0xe3.toByte(), 0x69, 0x5c, 0x6c, 0xe1.toByte(), 0xf7.toByte(), 0xf9.toByte(), 0x54, 0x5d, 0xa8.toByte(), 0xee.toByte(), 0x6b
    )

    /**
     * Noise protocol handshake message.
     */
    @Serializable
    data class HandshakeMessage(
        val clientHello: ClientHello,
    )

    @Serializable
    data class ClientHello(
        val ephemeral: String, // Base64 encoded public key
        val static: String?, // Base64 encoded static key (optional)
        val payload: String, // Base64 encoded encrypted payload
    )

    /**
     * WhatsApp message node (binary XML-like structure).
     */
    data class Node(
        val tag: String,
        val attrs: Map<String, String> = emptyMap(),
        val content: List<Node> = emptyList(),
        val data: ByteArray? = null,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Node
            if (tag != other.tag) return false
            if (attrs != other.attrs) return false
            if (content != other.content) return false
            if (data != null) {
                if (other.data == null) return false
                if (!data.contentEquals(other.data)) return false
            } else if (other.data != null) return false
            return true
        }

        override fun hashCode(): Int {
            var result = tag.hashCode()
            result = 31 * result + attrs.hashCode()
            result = 31 * result + content.hashCode()
            result = 31 * result + (data?.contentHashCode() ?: 0)
            return result
        }
    }

    /**
     * Noise Protocol Handshake State Machine
     * Implements Noise_XX_25519_AESGCM_SHA256 as per whatsmeow/socket/noisehandshake.go
     */
    class NoiseHandshake {
        private var hash = ByteArray(32)
        private var salt = ByteArray(32)
        private var key: SecretKeySpec? = null
        private var counter: UInt = 0u

        /**
         * Initialize handshake with protocol pattern and header
         */
        fun start(pattern: String, header: ByteArray) {
            val data = pattern.toByteArray(Charsets.UTF_8)
            hash = if (data.size == 32) {
                data
            } else {
                sha256(data)
            }
            salt = hash.copyOf()
            key = SecretKeySpec(hash, "AES")
            authenticate(header)
        }

        /**
         * Mix data into the handshake hash (transcript)
         */
        fun authenticate(data: ByteArray) {
            hash = sha256(hash + data)
        }

        /**
         * Encrypt plaintext with AES-GCM using current key
         * Increments counter and mixes ciphertext into transcript
         */
        fun encrypt(plaintext: ByteArray): ByteArray {
            val currentKey = key ?: throw IllegalStateException("Handshake not started")
            val iv = generateIV(counter)
            counter++
            
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.ENCRYPT_MODE, currentKey, spec)
            cipher.updateAAD(hash)
            val ciphertext = cipher.doFinal(plaintext)
            authenticate(ciphertext)
            return ciphertext
        }

        /**
         * Decrypt ciphertext with AES-GCM using current key
         * Increments counter and mixes ciphertext into transcript on success
         */
        fun decrypt(ciphertext: ByteArray): ByteArray {
            val currentKey = key ?: throw IllegalStateException("Handshake not started")
            val iv = generateIV(counter)
            counter++
            
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, currentKey, spec)
            cipher.updateAAD(hash)
            val plaintext = cipher.doFinal(ciphertext)
            authenticate(ciphertext)
            return plaintext
        }

        /**
         * Mix shared secret (from X25519) into the key
         * Uses HKDF-SHA256 to derive new salt and key
         */
        fun mixSharedSecretIntoKey(privateKey: ByteArray, publicKey: ByteArray) {
            val sharedSecret = x25519(privateKey, publicKey)
            mixIntoKey(sharedSecret)
        }

        /**
         * Mix arbitrary data into the key using HKDF
         */
        fun mixIntoKey(data: ByteArray) {
            counter = 0u
            val (newSalt, newKey) = extractAndExpand(salt, data)
            salt = newSalt
            key = SecretKeySpec(newKey, "AES")
        }

        /**
         * HKDF-SHA256 extract and expand
         * Returns (salt, key) pair, each 32 bytes
         */
        private fun extractAndExpand(salt: ByteArray, data: ByteArray): Pair<ByteArray, ByteArray> {
            val hkdf = HKDFBytesGenerator(SHA256Digest())
            hkdf.init(HKDFParameters(data, salt, null))
            
            val writeKey = ByteArray(32)
            val readKey = ByteArray(32)
            hkdf.generateBytes(writeKey, 0, 32)
            hkdf.generateBytes(readKey, 0, 32)
            
            return Pair(writeKey, readKey)
        }

        /**
         * Finish handshake and derive final read/write keys
         */
        fun finish(): Pair<SecretKeySpec, SecretKeySpec> {
            val (writeKey, readKey) = extractAndExpand(salt, ByteArray(0))
            return Pair(
                SecretKeySpec(writeKey, "AES"),
                SecretKeySpec(readKey, "AES")
            )
        }

        private fun generateIV(counter: UInt): ByteArray {
            val iv = ByteArray(12)
            // First 4 bytes are 0, last 8 bytes are counter (big-endian)
            ByteBuffer.wrap(iv, 4, 8)
                .order(ByteOrder.BIG_ENDIAN)
                .putLong(counter.toLong())
            return iv
        }
    }

    /**
     * Perform X25519 scalar multiplication
     */
    fun x25519(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        val privParams = X25519PrivateKeyParameters(privateKey, 0)
        val pubParams = X25519PublicKeyParameters(publicKey, 0)
        val agreement = X25519Agreement()
        agreement.init(privParams)
        val sharedSecret = ByteArray(32)
        agreement.calculateAgreement(pubParams, sharedSecret, 0)
        return sharedSecret
    }

    /**
     * Generate X25519 key pair
     */
    fun generateX25519KeyPair(): Pair<ByteArray, ByteArray> {
        val random = SecureRandom()
        val privateKey = ByteArray(32)
        random.nextBytes(privateKey)
        // Clamp the private key as per X25519 spec
        privateKey[0] = (privateKey[0].toInt() and 248).toByte()
        privateKey[31] = (privateKey[31].toInt() and 127).toByte()
        privateKey[31] = (privateKey[31].toInt() or 64).toByte()
        
        val privParams = X25519PrivateKeyParameters(privateKey, 0)
        val publicKey = privParams.generatePublicKey().encoded
        return Pair(privateKey, publicKey)
    }

    /**
     * Encrypt data using AES-256-GCM.
     */
    fun encryptAesGcm(key: ByteArray, iv: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, spec)
        return cipher.doFinal(plaintext)
    }

    /**
     * Decrypt data using AES-256-GCM.
     */
    fun decryptAesGcm(key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, spec)
        return cipher.doFinal(ciphertext)
    }

    /**
     * Compute HMAC-SHA256.
     */
    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val keySpec = SecretKeySpec(key, "HmacSHA256")
        mac.init(keySpec)
        return mac.doFinal(data)
    }

    /**
     * Compute SHA256 hash.
     */
    fun sha256(data: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data)
    }

    /**
     * Encode node to binary format.
     * Simplified implementation - full binary protocol is complex.
     */
    fun encodeNode(node: Node): ByteArray {
        // Simplified JSON-based encoding for initial implementation
        // Full binary protocol requires implementing WhatsApp's binary XML format
        val json = KotlinJson.encodeToString(node.toMap())
        return json.toByteArray(Charsets.UTF_8)
    }

    /**
     * Decode binary data to node.
     */
    fun decodeNode(data: ByteArray): Node {
        val json = String(data, Charsets.UTF_8)
        val map = KotlinJson.decodeFromString<Map<String, Any>>(json)
        return map.toNode()
    }

    private fun Node.toMap(): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        map["tag"] = tag
        if (attrs.isNotEmpty()) map["attrs"] = attrs
        if (content.isNotEmpty()) map["content"] = content.map { it.toMap() }
        data?.let { map["data"] = Base64.encodeToString(it, Base64.NO_WRAP) }
        return map
    }

    private fun Map<String, Any>.toNode(): Node {
        val tag = this["tag"] as String
        val attrs = (this["attrs"] as? Map<String, String>) ?: emptyMap()
        val content = (this["content"] as? List<Map<String, Any>>)?.map { it.toNode() } ?: emptyList()
        val data = (this["data"] as? String)?.let { Base64.decode(it, Base64.NO_WRAP) }
        return Node(tag, attrs, content, data)
    }

    /**
     * Build a message node for sending text.
     */
    fun buildTextMessage(to: String, id: String, text: String): Node {
        return Node(
            tag = "message",
            attrs = mapOf(
                "to" to to,
                "id" to id,
                "type" to "chat"
            ),
            content = listOf(
                Node(
                    tag = "body",
                    data = text.toByteArray(Charsets.UTF_8)
                )
            )
        )
    }

    /**
     * Parse incoming message node.
     */
    fun parseMessage(node: Node): WhatsAppMessage? {
        if (node.tag != "message") return null

        val from = node.attrs["from"] ?: return null
        val id = node.attrs["id"] ?: return null
        val type = node.attrs["type"] ?: "chat"
        val timestamp = node.attrs["t"]?.toLongOrNull() ?: System.currentTimeMillis() / 1000

        val bodyNode = node.content.find { it.tag == "body" }
        val body = bodyNode?.data?.let { String(it, Charsets.UTF_8) } ?: ""

        return WhatsAppMessage(
            id = id,
            from = from,
            to = node.attrs["to"] ?: "",
            body = body,
            timestamp = timestamp,
            type = type
        )
    }
}

/**
 * Parsed WhatsApp message.
 */
data class WhatsAppMessage(
    val id: String,
    val from: String,
    val to: String,
    val body: String,
    val timestamp: Long,
    val type: String,
)
