package com.vayunmathur.passwords.cable

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.KeyPair
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Self-consistency tests for the Noise KNpsk0 responder ([NoiseResponder]) and [P256].
 *
 * NOTE: these prove the handshake framework agrees *with itself* (a matching initiator is
 * implemented below), not that it is byte-compatible with Chromium — that still requires the
 * Chromium test vectors called out in the plan.
 */
class NoiseTest {

    @Test fun p256CompressDecompressRoundTrip() {
        val kp = P256.generateKeyPair()
        val compressed = P256.toCompressed(kp.public)
        assertEquals(P256.COMPRESSED_SIZE, compressed.size)
        val restored = P256.decodePoint(compressed)
        assertArrayEquals(P256.toUncompressed(kp.public), P256.toUncompressed(restored))
    }

    @Test fun p256EcdhIsSymmetric() {
        val a = P256.generateKeyPair()
        val b = P256.generateKeyPair()
        assertArrayEquals(P256.ecdh(a.private, b.public), P256.ecdh(b.private, a.public))
    }

    @Test fun cipherStateRoundTripAcrossNonces() {
        val key = ByteArray(32) { it.toByte() }
        val enc = CipherState(key)
        val dec = CipherState(key)
        for (i in 0 until 5) {
            val msg = "message #$i".toByteArray()
            assertArrayEquals(msg, dec.decrypt(enc.encrypt(msg)))
        }
    }

    @Test fun fullHandshakeAndTransport() {
        // The QR flow: browser (initiator) holds a static key whose compressed form is in the QR.
        val initiatorStatic = P256.generateKeyPair()
        val qrPeerKey = P256.decodePoint(P256.toCompressed(initiatorStatic.public))
        val psk = ByteArray(32) { (it * 7 + 1).toByte() }

        val responder = NoiseResponder(qrPeerKey, psk)
        val initiator = TestInitiator(initiatorStatic, psk)

        val msg1 = initiator.writeMessage1()
        assertArrayEquals(ByteArray(0), responder.readMessage1(msg1))

        val serverPayload = "getInfo-ish".toByteArray()
        val (msg2, respKeys) = responder.writeMessage2(serverPayload)
        val initKeys = initiator.readMessage2(msg2)
        assertArrayEquals(serverPayload, initKeys.payload)

        // Transport: initiator -> responder uses c1; responder -> initiator uses c2.
        val a = "ctap request".toByteArray()
        assertArrayEquals(a, respKeys.decrypt.decrypt(initKeys.encrypt.encrypt(a)))
        val b = "ctap response".toByteArray()
        assertArrayEquals(b, initKeys.decrypt.decrypt(respKeys.encrypt.encrypt(b)))
    }

    // --- Minimal matching KNpsk0 initiator, mirroring NoiseResponder's framework ---

    private class InitiatorResult(
        val decrypt: CipherState,
        val encrypt: CipherState,
        val payload: ByteArray,
    )

    private class TestInitiator(private val staticKey: KeyPair, private val psk: ByteArray) {
        private var ck: ByteArray
        private var h: ByteArray
        private var cipher: CipherState? = null
        private val ephemeral = P256.generateKeyPair()

        init {
            val name = NoiseResponder.PROTOCOL_NAME.toByteArray(Charsets.US_ASCII)
            h = name + ByteArray(32 - name.size)
            ck = h.copyOf()
            mixHash(P256.toUncompressed(staticKey.public)) // pre-message -> s
        }

        fun writeMessage1(): ByteArray {
            mixKeyAndHash(psk)
            val ePub = P256.toUncompressed(ephemeral.public)
            mixHash(ePub); mixKey(ePub)
            return ePub + encryptAndHash(ByteArray(0))
        }

        fun readMessage2(message: ByteArray): InitiatorResult {
            val rePub = message.copyOfRange(0, P256.UNCOMPRESSED_SIZE)
            val re = P256.decodePoint(rePub)
            mixHash(rePub); mixKey(rePub)
            mixKey(P256.ecdh(ephemeral.private, re))            // ee
            mixKey(P256.ecdh(staticKey.private, re))            // se
            val payload = decryptAndHash(message.copyOfRange(P256.UNCOMPRESSED_SIZE, message.size))
            val (c1, c2) = split()
            return InitiatorResult(decrypt = c2, encrypt = c1, payload = payload)
        }

        private fun mixHash(data: ByteArray) { h = sha256(h + data) }
        private fun mixKey(ikm: ByteArray) {
            val (newCk, k) = hkdf2(ck, ikm); ck = newCk; cipher = CipherState(k)
        }
        private fun mixKeyAndHash(ikm: ByteArray) {
            val (newCk, tempH, k) = hkdf3(ck, ikm); ck = newCk; mixHash(tempH); cipher = CipherState(k)
        }
        private fun encryptAndHash(pt: ByteArray): ByteArray {
            val c = cipher ?: return pt.also { mixHash(it) }
            return c.encrypt(h, pt).also { mixHash(it) }
        }
        private fun decryptAndHash(ct: ByteArray): ByteArray {
            val c = cipher ?: return ct.also { mixHash(it) }
            return c.decrypt(h, ct).also { mixHash(ct) }
        }
        private fun split(): Pair<CipherState, CipherState> {
            val (k1, k2) = hkdf2(ck, ByteArray(0)); return CipherState(k1) to CipherState(k2)
        }
    }

    companion object {
        private fun sha256(d: ByteArray) = MessageDigest.getInstance("SHA-256").digest(d)
        private fun hmac(key: ByteArray, d: ByteArray) = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256")); doFinal(d)
        }
        private fun hkdf2(ck: ByteArray, ikm: ByteArray): Pair<ByteArray, ByteArray> {
            val tk = hmac(ck, ikm); val o1 = hmac(tk, byteArrayOf(1)); val o2 = hmac(tk, o1 + 2)
            return o1 to o2
        }
        private fun hkdf3(ck: ByteArray, ikm: ByteArray): Triple<ByteArray, ByteArray, ByteArray> {
            val tk = hmac(ck, ikm); val o1 = hmac(tk, byteArrayOf(1))
            val o2 = hmac(tk, o1 + 2); val o3 = hmac(tk, o2 + 3)
            return Triple(o1, o2, o3)
        }
    }
}
