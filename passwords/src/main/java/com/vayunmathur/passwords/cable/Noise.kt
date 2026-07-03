package com.vayunmathur.passwords.cable

import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Noise `KNpsk0` handshake over `P256 / AES256-GCM / SHA256`, responder side, for the caBLE v2
 * authenticator. The desktop browser is the initiator; the phone (this app) is the responder.
 *
 * Pattern (KNpsk0):
 * ```
 *   -> s            (pre-message: initiator static = the QR peer public key)
 *   ...
 *   -> psk, e
 *   <- e, ee, se
 * ```
 *
 * ⚠️ UNVERIFIED against Chromium test vectors. The Noise *framework* here follows the published
 * Noise spec (rev 34), but several caBLE-specific choices are reconstructed and MUST be confirmed
 * against Chromium `//device/fido/cable/noise.cc` before relying on interop:
 *  - protocol name string + padding of the initial `h`;
 *  - AES-GCM nonce layout (spec: `0x00000000 || big-endian uint64 counter`; Chromium is believed
 *    to place a 32-bit little-endian counter at bytes [4..8) — see [CipherState.nonceBytes]);
 *  - point encoding used for `e` on the wire and in MixHash (assumed 65-byte uncompressed).
 * A mismatch surfaces only as the generic browser "Bluetooth?" error.
 */
class NoiseResponder(
    /** Initiator's static public key, decompressed from the 33-byte QR value. */
    private val initiatorStatic: PublicKey,
    /** Pre-shared key from [CableKeys.psk]. */
    private val psk: ByteArray,
) {
    private val symmetric = SymmetricState(PROTOCOL_NAME)
    private val ephemeral = P256.generateKeyPair()
    private var peerEphemeral: PublicKey? = null

    init {
        require(psk.size == 32) { "Noise psk must be 32 bytes" }
        // Pre-message `-> s`: both sides MixHash the initiator's static public key.
        symmetric.mixHash(P256.toUncompressed(initiatorStatic))
    }

    /**
     * Reads the initiator's first handshake message (`-> psk, e` [+ encrypted payload]).
     * Returns the decrypted payload (usually empty).
     */
    fun readMessage1(message: ByteArray): ByteArray {
        // psk token (psk0): mix the PSK into ck and h before processing e.
        symmetric.mixKeyAndHash(psk)

        // e token: read the 65-byte ephemeral, MixHash then (psk mode) MixKey it.
        require(message.size >= P256.UNCOMPRESSED_SIZE) { "Noise msg1 too short" }
        val rePublic = message.copyOfRange(0, P256.UNCOMPRESSED_SIZE)
        peerEphemeral = P256.decodePoint(rePublic)
        symmetric.mixHash(rePublic)
        symmetric.mixKey(rePublic)

        val payloadCipher = message.copyOfRange(P256.UNCOMPRESSED_SIZE, message.size)
        return symmetric.decryptAndHash(payloadCipher)
    }

    /**
     * Writes the responder's reply (`<- e, ee, se` [+ encrypted payload]) and finalizes the
     * handshake. Returns the message bytes to send plus the derived transport keys.
     */
    fun writeMessage2(payload: ByteArray = ByteArray(0)): Pair<ByteArray, TransportKeys> {
        val re = peerEphemeral ?: error("readMessage1 must be called first")

        // e token: send our ephemeral public key, MixHash then (psk mode) MixKey it.
        val ePublic = P256.toUncompressed(ephemeral.public)
        symmetric.mixHash(ePublic)
        symmetric.mixKey(ePublic)

        // ee: DH(our ephemeral, their ephemeral).
        symmetric.mixKey(P256.ecdh(ephemeral.private, re))
        // se: DH(our ephemeral, initiator static). (Responder has no static key in KN.)
        symmetric.mixKey(P256.ecdh(ephemeral.private, initiatorStatic))

        val encryptedPayload = symmetric.encryptAndHash(payload)
        val message = ePublic + encryptedPayload

        val (c1, c2) = symmetric.split()
        // c1 = initiator->responder (decrypt), c2 = responder->initiator (encrypt).
        return message to TransportKeys(decrypt = c1, encrypt = c2)
    }

    /** Post-handshake transport cipher pair. */
    class TransportKeys(val decrypt: CipherState, val encrypt: CipherState)

    // ---- Noise SymmetricState ----

    private class SymmetricState(protocolName: String) {
        private var ck: ByteArray
        private var h: ByteArray
        private var cipher: CipherState? = null

        init {
            val nameBytes = protocolName.toByteArray(Charsets.US_ASCII)
            h = if (nameBytes.size <= 32) nameBytes + ByteArray(32 - nameBytes.size)
                else sha256(nameBytes)
            ck = h.copyOf()
        }

        fun mixHash(data: ByteArray) {
            h = sha256(h + data)
        }

        fun mixKey(input: ByteArray) {
            val (newCk, tempK) = hkdf2(ck, input)
            ck = newCk
            cipher = CipherState(tempK)
        }

        fun mixKeyAndHash(input: ByteArray) {
            val (newCk, tempH, tempK) = hkdf3(ck, input)
            ck = newCk
            mixHash(tempH)
            cipher = CipherState(tempK)
        }

        fun encryptAndHash(plaintext: ByteArray): ByteArray {
            val c = cipher ?: return plaintext.also { mixHash(it) }
            val ciphertext = c.encrypt(h, plaintext)
            mixHash(ciphertext)
            return ciphertext
        }

        fun decryptAndHash(ciphertext: ByteArray): ByteArray {
            val c = cipher ?: return ciphertext.also { mixHash(it) }
            val plaintext = c.decrypt(h, ciphertext)
            mixHash(ciphertext)
            return plaintext
        }

        fun split(): Pair<CipherState, CipherState> {
            val (k1, k2) = hkdf2(ck, ByteArray(0))
            return CipherState(k1) to CipherState(k2)
        }
    }

    companion object {
        const val PROTOCOL_NAME = "Noise_KNpsk0_P256_AESGCM_SHA256"

        private fun sha256(data: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(data)

        private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(key, "HmacSHA256"))
                doFinal(data)
            }

        /** Noise HKDF with two outputs. */
        private fun hkdf2(ck: ByteArray, ikm: ByteArray): Pair<ByteArray, ByteArray> {
            val tempKey = hmac(ck, ikm)
            val o1 = hmac(tempKey, byteArrayOf(0x01))
            val o2 = hmac(tempKey, o1 + 0x02)
            return o1 to o2
        }

        /** Noise HKDF with three outputs. */
        private fun hkdf3(ck: ByteArray, ikm: ByteArray): Triple<ByteArray, ByteArray, ByteArray> {
            val tempKey = hmac(ck, ikm)
            val o1 = hmac(tempKey, byteArrayOf(0x01))
            val o2 = hmac(tempKey, o1 + 0x02)
            val o3 = hmac(tempKey, o2 + 0x03)
            return Triple(o1, o2, o3)
        }
    }
}

/**
 * A Noise CipherState: AES-256-GCM keyed by a 32-byte key with a monotonically increasing nonce.
 * `h` is passed as the GCM associated data during the handshake; transport messages use empty AD.
 */
class CipherState(private val key: ByteArray) {
    private var nonce: Long = 0

    fun encrypt(ad: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = gcm(Cipher.ENCRYPT_MODE)
        if (ad.isNotEmpty()) cipher.updateAAD(ad)
        return cipher.doFinal(plaintext).also { nonce++ }
    }

    fun decrypt(ad: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = gcm(Cipher.DECRYPT_MODE)
        if (ad.isNotEmpty()) cipher.updateAAD(ad)
        return cipher.doFinal(ciphertext).also { nonce++ }
    }

    fun encrypt(plaintext: ByteArray): ByteArray = encrypt(ByteArray(0), plaintext)
    fun decrypt(ciphertext: ByteArray): ByteArray = decrypt(ByteArray(0), ciphertext)

    private fun gcm(mode: Int): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonceBytes()))
    }

    /**
     * Noise-spec AES-GCM nonce: 4 zero bytes then the 64-bit big-endian counter.
     * ⚠️ Chromium's caBLE may instead use a 32-bit little-endian counter at bytes [4..8) — verify.
     */
    private fun nonceBytes(): ByteArray {
        val n = ByteArray(12)
        for (i in 0 until 8) n[11 - i] = ((nonce ushr (8 * i)) and 0xFF).toByte()
        return n
    }
}
