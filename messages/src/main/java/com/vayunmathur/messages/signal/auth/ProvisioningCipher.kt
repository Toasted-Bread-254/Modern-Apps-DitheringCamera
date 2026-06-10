package com.vayunmathur.messages.signal.auth

import com.vayunmathur.messages.signal.proto.ProvisioningProtos.ProvisionEnvelope
import com.vayunmathur.messages.signal.proto.ProvisioningProtos.ProvisionMessage
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.ecc.ECPrivateKey
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class ProvisioningCipher {
    private var keyPair: ECKeyPair? = null

    companion object {
        const val SUPPORTED_VERSION: Byte = 1
        const val CIPHER_KEY_SIZE = 32
        const val MAC_SIZE = 32
        const val VERSION_OFFSET = 0
        const val VERSION_LENGTH = 1
        const val IV_OFFSET = VERSION_OFFSET + VERSION_LENGTH
        const val IV_LENGTH = 16
        const val CIPHERTEXT_OFFSET = IV_OFFSET + IV_LENGTH
    }

    fun getPublicKey(): ECPublicKey {
        if (keyPair == null) {
            keyPair = ECKeyPair.generate()
        }
        return keyPair!!.publicKey
    }

    fun decrypt(envelope: ProvisionEnvelope): ProvisionMessage {
        val kp = keyPair ?: throw IllegalStateException("Key pair not initialized")
        require(envelope.hasPublicKey() && envelope.publicKey.size() > 0) { "Envelope missing public key" }
        require(envelope.hasBody() && envelope.body.size() > 0) { "Envelope missing body" }
        val masterEphemeral = ECPublicKey(envelope.publicKey.toByteArray())
        val body = envelope.body.toByteArray()
        require(body[VERSION_OFFSET] == SUPPORTED_VERSION) { "Unsupported ProvisionMessage version: ${body[0]}" }

        val bodyLen = body.size
        val iv = body.copyOfRange(IV_OFFSET, IV_OFFSET + IV_LENGTH)
        val mac = body.copyOfRange(bodyLen - MAC_SIZE, bodyLen)
        require(iv.size == IV_LENGTH) { "Invalid IV size: ${iv.size}" }
        require(mac.size == MAC_SIZE) { "Invalid MAC size: ${mac.size}" }
        val cipherText = body.copyOfRange(CIPHERTEXT_OFFSET, bodyLen - CIPHER_KEY_SIZE)
        val ivAndCipherText = body.copyOfRange(0, bodyLen - CIPHER_KEY_SIZE)

        val agreement = kp.privateKey.calculateAgreement(masterEphemeral)

        val sharedSecrets = hkdfDeriveSecrets(agreement, "TextSecure Provisioning Message".toByteArray(), 64)

        val cipherKey = sharedSecrets.copyOfRange(0, CIPHER_KEY_SIZE)
        val macKey = sharedSecrets.copyOfRange(CIPHER_KEY_SIZE, 64)

        val hmac = Mac.getInstance("HmacSHA256")
        hmac.init(SecretKeySpec(macKey, "HmacSHA256"))
        val ourMac = hmac.doFinal(ivAndCipherText)
        require(ourMac.size == mac.size) { "Invalid MAC length: ourMac=${ourMac.size} mac=${mac.size}" }
        require(java.security.MessageDigest.isEqual(ourMac.copyOfRange(0, MAC_SIZE), mac)) { "Invalid MAC" }

        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(cipherKey, "AES"), IvParameterSpec(iv))
        val decrypted = cipher.doFinal(cipherText)

        val unpadded = unpadPKCS7(decrypted)
        return ProvisionMessage.parseFrom(unpadded)
    }

    private fun unpadPKCS7(data: ByteArray): ByteArray {
        require(data.isNotEmpty()) { "data is empty" }
        val paddingLen = data.last().toInt() and 0xFF
        require(paddingLen in 1..data.size) { "invalid padding" }
        for (i in 0 until paddingLen) {
            require(data[data.size - 1 - i] == paddingLen.toByte()) { "invalid padding" }
        }
        return data.copyOfRange(0, data.size - paddingLen)
    }

    private fun hkdfDeriveSecrets(input: ByteArray, info: ByteArray, outputLength: Int): ByteArray {
        val prk = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
            doFinal(input)
        }
        val result = ByteArray(outputLength)
        var offset = 0
        var t = ByteArray(0)
        var i: Byte = 1
        while (offset < outputLength) {
            val hmac = Mac.getInstance("HmacSHA256")
            hmac.init(SecretKeySpec(prk, "HmacSHA256"))
            hmac.update(t)
            hmac.update(info)
            hmac.update(byteArrayOf(i))
            t = hmac.doFinal()
            val toCopy = minOf(t.size, outputLength - offset)
            System.arraycopy(t, 0, result, offset, toCopy)
            offset += toCopy
            i++
        }
        return result
    }
}
