package com.vayunmathur.passwords.cable

import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.spec.ECNamedCurveSpec
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec

/**
 * P-256 (secp256r1 / prime256v1) helpers used by the caBLE Noise handshake and EID handling.
 *
 * Point encodings follow X9.62: compressed = `02|03 || X` (33 bytes), uncompressed =
 * `04 || X || Y` (65 bytes). ECDH returns the 32-byte big-endian X coordinate of the shared
 * point (Noise `DH` output for P-256).
 */
object P256 {
    const val UNCOMPRESSED_SIZE = 65
    const val COMPRESSED_SIZE = 33
    const val COORD_SIZE = 32
    const val DH_OUTPUT_SIZE = 32

    private val bcSpec = ECNamedCurveTable.getParameterSpec("secp256r1")
    private val jcaSpec = ECNamedCurveSpec("secp256r1", bcSpec.curve, bcSpec.g, bcSpec.n, bcSpec.h)

    fun generateKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }

    /** Serializes a public key as the 65-byte uncompressed X9.62 form. */
    fun toUncompressed(publicKey: PublicKey): ByteArray {
        val w = (publicKey as ECPublicKey).w
        return byteArrayOf(0x04) + fixed(w.affineX) + fixed(w.affineY)
    }

    /** Serializes a public key as the 33-byte compressed X9.62 form. */
    fun toCompressed(publicKey: PublicKey): ByteArray {
        val w = (publicKey as ECPublicKey).w
        val prefix = if (w.affineY.testBit(0)) 0x03 else 0x02
        return byteArrayOf(prefix.toByte()) + fixed(w.affineX)
    }

    /** Parses a 33-byte compressed or 65-byte uncompressed point into a [PublicKey]. */
    fun decodePoint(encoded: ByteArray): PublicKey {
        val point = bcSpec.curve.decodePoint(encoded).normalize()
        val ecPoint = ECPoint(
            point.affineXCoord.toBigInteger(),
            point.affineYCoord.toBigInteger(),
        )
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ecPoint, jcaSpec))
    }

    /** Raw ECDH: returns the 32-byte big-endian X coordinate of `priv * pub`. */
    fun ecdh(priv: PrivateKey, pub: PublicKey): ByteArray {
        val agreement = javax.crypto.KeyAgreement.getInstance("ECDH")
        agreement.init(priv)
        agreement.doPhase(pub, true)
        return leftPad(agreement.generateSecret(), DH_OUTPUT_SIZE)
    }

    private fun fixed(value: BigInteger): ByteArray = leftPad(value.toByteArray(), COORD_SIZE)

    private fun leftPad(bytes: ByteArray, length: Int): ByteArray = when {
        bytes.size == length -> bytes
        bytes.size > length -> bytes.copyOfRange(bytes.size - length, bytes.size)
        else -> ByteArray(length - bytes.size) + bytes
    }
}
