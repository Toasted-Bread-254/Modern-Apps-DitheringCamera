package com.vayunmathur.messages.gmessages

import android.util.Base64
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable

/**
 * Direct port of `crypto/aesctr.go` from mautrix-gmessages.
 *
 * Wire format (output of [encrypt], input of [decrypt]):
 *     ciphertext || IV(16 bytes) || HMAC-SHA256(ciphertext||IV)(32 bytes)
 *
 * The AES key encrypts message bodies and is shared with the user's
 * phone via the QR pairing payload (see [PairFlow]). The HMAC key
 * authenticates the encrypted blob against tampering.
 */
class AesCtrHmac(
    val aesKey: ByteArray,
    val hmacKey: ByteArray,
) {

    fun encrypt(plaintext: ByteArray): ByteArray {
        val iv = ByteArray(AES_BLOCK_SIZE)
        SecureRandom().nextBytes(iv)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
        }
        val ct = cipher.doFinal(plaintext)
        val withIv = ct + iv
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(hmacKey, "HmacSHA256"))
        }
        val tag = mac.doFinal(withIv)
        return withIv + tag
    }

    fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size >= 48) { "ciphertext blob too short: ${blob.size} < 48" }
        val tag = blob.copyOfRange(blob.size - HMAC_TAG_SIZE, blob.size)
        val withIv = blob.copyOfRange(0, blob.size - HMAC_TAG_SIZE)

        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(hmacKey, "HmacSHA256"))
        }
        val expectedTag = mac.doFinal(withIv)
        require(tag.contentEquals(expectedTag)) { "HMAC mismatch" }

        val iv = withIv.copyOfRange(withIv.size - AES_BLOCK_SIZE, withIv.size)
        val ct = withIv.copyOfRange(0, withIv.size - AES_BLOCK_SIZE)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
        }
        return cipher.doFinal(ct)
    }

    companion object {
        private const val AES_BLOCK_SIZE = 16
        private const val HMAC_TAG_SIZE = 32

        fun generate(): AesCtrHmac = AesCtrHmac(
            aesKey = generateKey(32),
            hmacKey = generateKey(32),
        )

        fun generateKey(length: Int): ByteArray {
            val bytes = ByteArray(length)
            SecureRandom().nextBytes(bytes)
            return bytes
        }
    }
}

/**
 * Chunked AES-GCM helper used for media uploads.
 *
 * Direct port of `crypto/aesgcm.go` from mautrix-gmessages: the plaintext
 * is split into 32 KiB chunks, each encrypted with a fresh random
 * 12-byte nonce + AAD = `[isLast(1 byte), chunkIndex(uint32 big-endian)]`.
 * The 2-byte stream header `(0, log2(chunkSize))` precedes the chunk
 * stream so the decoder knows the chunk boundary.
 *
 * The on-disk wire format must match exactly — Google's media server
 * doesn't decrypt this; we encrypt before upload and the recipient's
 * Messages client decrypts after download using the [decryptionKey]
 * Google routes through the SendMessageRequest media field.
 */
class AesGcm(val key: ByteArray) {
    init {
        require(key.size == 32) { "unsupported AES-GCM key length (got=${key.size} expected=32)" }
    }

    fun encryptData(data: ByteArray): ByteArray {
        val chunkOverhead = NONCE_SIZE + TAG_SIZE
        var chunkSize = OUTGOING_RAW_CHUNK_SIZE - chunkOverhead
        val chunkCount = ((data.size + chunkSize - 1) / chunkSize).coerceAtLeast(1)
        val out = java.io.ByteArrayOutputStream(2 + data.size + chunkOverhead * chunkCount)
        // Stream header: `0` magic byte + log2(chunkSize). chunkSize here
        // means the OUTGOING_RAW_CHUNK_SIZE, NOT the plaintext size.
        out.write(0)
        out.write(LOG2_OUTGOING_RAW_CHUNK_SIZE)
        var chunkIndex = 0
        var i = 0
        while (i < data.size) {
            val isLast = (i + chunkSize) >= data.size
            if (isLast) chunkSize = data.size - i
            val aad = aad(chunkIndex, isLast)
            val nonce = ByteArray(NONCE_SIZE)
            SecureRandom().nextBytes(nonce)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(
                    Cipher.ENCRYPT_MODE,
                    SecretKeySpec(key, "AES"),
                    javax.crypto.spec.GCMParameterSpec(TAG_SIZE * 8, nonce),
                )
                updateAAD(aad)
            }
            val ct = cipher.doFinal(data, i, chunkSize)
            // Prepend nonce, exactly like Go's `c.gcm.Seal(nonce, …)`.
            out.write(nonce)
            out.write(ct)
            chunkIndex++
            i += chunkSize
        }
        return out.toByteArray()
    }

    fun decryptData(blob: ByteArray): ByteArray {
        if (blob.isEmpty()) return blob
        require(blob[0] == 0.toByte()) { "invalid AES-GCM stream header byte=${blob[0]}" }
        val chunkSize = 1 shl (blob[1].toInt() and 0xFF)
        val body = blob.copyOfRange(2, blob.size)
        val out = java.io.ByteArrayOutputStream(body.size)
        var chunkIndex = 0
        var i = 0
        var thisChunk = chunkSize
        while (i < body.size) {
            val isLast = (i + thisChunk) >= body.size
            if (isLast) thisChunk = body.size - i
            require(thisChunk >= NONCE_SIZE) { "GCM chunk too small ($thisChunk)" }
            val aad = aad(chunkIndex, isLast)
            val nonce = body.copyOfRange(i, i + NONCE_SIZE)
            val ct = body.copyOfRange(i + NONCE_SIZE, i + thisChunk)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(key, "AES"),
                    javax.crypto.spec.GCMParameterSpec(TAG_SIZE * 8, nonce),
                )
                updateAAD(aad)
            }
            out.write(cipher.doFinal(ct))
            i += thisChunk
            chunkIndex++
        }
        return out.toByteArray()
    }

    /** Per-chunk AAD: 1 magic byte (isLast) + big-endian uint32 chunkIndex. */
    private fun aad(chunkIndex: Int, isLast: Boolean): ByteArray {
        val aad = ByteArray(5)
        if (isLast) aad[0] = 1
        aad[1] = ((chunkIndex ushr 24) and 0xFF).toByte()
        aad[2] = ((chunkIndex ushr 16) and 0xFF).toByte()
        aad[3] = ((chunkIndex ushr 8) and 0xFF).toByte()
        aad[4] = (chunkIndex and 0xFF).toByte()
        return aad
    }

    companion object {
        const val NONCE_SIZE = 12
        const val TAG_SIZE = 16
        const val OUTGOING_RAW_CHUNK_SIZE = 1 shl 15  // 32 KiB
        const val LOG2_OUTGOING_RAW_CHUNK_SIZE = 15

        fun generateKey(): ByteArray = AesCtrHmac.generateKey(32)
    }
}

/**
 * Serializable JWK form of an ECDSA P-256 key pair. Matches the JSON shape
 * used by libgm in `crypto/ecdsa.go` so the persisted on-disk
 * representation could theoretically be cross-read, though we never need
 * to (each install regenerates its own key on first pair).
 *
 * The private scalar `d` and the public point `(x, y)` are stored as
 * base64url-without-padding-encoded big-endian byte sequences. This
 * matches RFC 7518 §6.2.2.
 */
@Serializable
data class EcdsaP256Jwk(
    val kty: String = "EC",
    val crv: String = "P-256",
    val d: String,
    val x: String,
    val y: String,
) {
    fun toKeyPair(): KeyPair {
        val params = ecParameterSpec()
        val dBig = BigInteger(1, decodeUrl(d))
        val xBig = BigInteger(1, decodeUrl(x))
        val yBig = BigInteger(1, decodeUrl(y))
        val pubSpec = ECPublicKeySpec(ECPoint(xBig, yBig), params)
        val privSpec = ECPrivateKeySpec(dBig, params)
        val kf = KeyFactory.getInstance("EC")
        return KeyPair(kf.generatePublic(pubSpec), kf.generatePrivate(privSpec))
    }

    /** DER-encoded SubjectPublicKeyInfo, the same shape `x509.MarshalPKIXPublicKey` produces in Go. */
    fun publicKeyDer(): ByteArray = toKeyPair().public.encoded

    fun sign(data: ByteArray): ByteArray {
        val priv = toKeyPair().private as ECPrivateKey
        return Signature.getInstance("SHA256withECDSA").apply {
            initSign(priv)
            update(data)
        }.sign()
    }

    companion object {
        fun generate(): EcdsaP256Jwk {
            val kpg = KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
            }
            val kp = kpg.generateKeyPair()
            val pub = kp.public as ECPublicKey
            val priv = kp.private as ECPrivateKey
            return EcdsaP256Jwk(
                d = encodeUrl(priv.s.toUnsignedFixedWidth(32)),
                x = encodeUrl(pub.w.affineX.toUnsignedFixedWidth(32)),
                y = encodeUrl(pub.w.affineY.toUnsignedFixedWidth(32)),
            )
        }

        private fun ecParameterSpec(): ECParameterSpec {
            // Bootstrap a key just to grab the curve parameters object.
            val kpg = KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec("secp256r1"))
            }
            return (kpg.generateKeyPair().public as ECPublicKey).params
        }

        private fun encodeUrl(bytes: ByteArray): String =
            Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

        private fun decodeUrl(s: String): ByteArray =
            Base64.decode(s, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}

/** Big-endian, fixed-width unsigned byte representation. Strips the
 *  BigInteger sign byte when present and left-pads with zeros to [width]. */
private fun BigInteger.toUnsignedFixedWidth(width: Int): ByteArray {
    val raw = toByteArray()
    val trimmed = if (raw.isNotEmpty() && raw[0] == 0.toByte() && raw.size > width) {
        raw.copyOfRange(1, raw.size)
    } else raw
    return if (trimmed.size >= width) {
        trimmed.copyOfRange(trimmed.size - width, trimmed.size)
    } else {
        ByteArray(width - trimmed.size) + trimmed
    }
}
