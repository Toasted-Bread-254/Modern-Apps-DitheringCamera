package com.vayunmathur.messages.telegram.mtproto.crypto

import java.math.BigInteger
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class SrpAnswer(val a: ByteArray, val m1: ByteArray)

object Srp {
    fun computeAnswer(
        password: ByteArray,
        srpB: ByteArray,
        randomA: ByteArray,
        salt1: ByteArray,
        salt2: ByteArray,
        g: Int,
        p: ByteArray,
    ): SrpAnswer {
        val pBig = BigInteger(1, p)
        val gBig = BigInteger.valueOf(g.toLong())

        checkInput(g, pBig)

        val gBytes = ByteArray(256)
        val gBigBytes = gBig.toByteArray()
        System.arraycopy(gBigBytes, 0, gBytes, 256 - gBigBytes.size, gBigBytes.size)

        val aBig = BigInteger(1, randomA)
        val gaResult = pad256Safe(gBig.modPow(aBig, pBig))
            ?: throw IllegalStateException("g_a is too big")
        val ga = gaResult
        val gb = pad256Raw(srpB)

        val u = BigInteger(1, hash(ga, gb))
        val x = BigInteger(1, secondary(password, salt1, salt2))
        val v = gBig.modPow(x, pBig)
        val k = BigInteger(1, hash(p, gBytes))
        val kv = k.multiply(v).mod(pBig)

        var t = BigInteger(1, srpB).subtract(kv)
        if (t.signum() < 0) t = t.add(pBig)

        val sa = pad256Safe(t.modPow(u.multiply(x).add(aBig), pBig))
            ?: throw IllegalStateException("s_a is too big")
        val ka = MessageDigest.getInstance("SHA-256").digest(sa)

        val hP = MessageDigest.getInstance("SHA-256").digest(p)
        val hG = MessageDigest.getInstance("SHA-256").digest(gBytes)
        val xorHpHg = ByteArray(32)
        for (i in 0 until 32) xorHpHg[i] = (hP[i].toInt() xor hG[i].toInt()).toByte()

        val m1 = hash(
            xorHpHg,
            hash(salt1),
            hash(salt2),
            ga, gb, ka
        )

        return SrpAnswer(ga, m1)
    }

    private fun hash(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        for (part in parts) md.update(part)
        return md.digest()
    }

    private fun saltHash(data: ByteArray, salt: ByteArray): ByteArray = hash(salt, data, salt)

    private fun primary(password: ByteArray, salt1: ByteArray, salt2: ByteArray): ByteArray =
        saltHash(saltHash(password, salt1), salt2)

    private fun secondary(password: ByteArray, salt1: ByteArray, salt2: ByteArray): ByteArray {
        val ph1 = primary(password, salt1, salt2)
        val pbkdf2 = pbkdf2Sha512(ph1, salt1, 100000, 64)
        return saltHash(pbkdf2, salt2)
    }

    private fun pbkdf2Sha512(password: ByteArray, salt: ByteArray, iterations: Int, keyLength: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(password, "HmacSHA512"))
        val hashLen = 64
        val blocks = (keyLength + hashLen - 1) / hashLen
        val result = ByteArray(blocks * hashLen)
        for (i in 1..blocks) {
            val blockIndex = byteArrayOf(
                (i shr 24).toByte(), (i shr 16).toByte(), (i shr 8).toByte(), i.toByte()
            )
            mac.reset()
            mac.update(salt)
            var u = mac.doFinal(blockIndex)
            val block = u.copyOf()
            for (j in 2..iterations) {
                mac.reset()
                u = mac.doFinal(u)
                for (k in block.indices) block[k] = (block[k].toInt() xor u[k].toInt()).toByte()
            }
            System.arraycopy(block, 0, result, (i - 1) * hashLen, hashLen)
        }
        return result.copyOfRange(0, keyLength)
    }

    private fun pad256Raw(data: ByteArray): ByteArray {
        return when {
            data.size == 256 -> data
            data.size > 256 -> data.copyOfRange(data.size - 256, data.size)
            else -> ByteArray(256 - data.size) + data
        }
    }

    private fun pad256Safe(v: BigInteger): ByteArray? {
        val bytes = v.toByteArray()
        val stripped = if (bytes.isNotEmpty() && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
        if (stripped.size > 256) return null
        return when {
            stripped.size == 256 -> stripped
            else -> ByteArray(256 - stripped.size) + stripped
        }
    }

    private fun checkInput(g: Int, p: BigInteger) {
        require(p.bitLength() == 2048) { "SRP: p must be 2048 bits" }
        require(p.isProbablePrime(64)) { "SRP: p is not prime" }
        val pMinus1Over2 = p.subtract(BigInteger.ONE).shiftRight(1)
        require(pMinus1Over2.isProbablePrime(64)) { "SRP: (p-1)/2 is not prime" }
        checkGP(g, p)
    }

    private fun checkGP(g: Int, p: BigInteger) {
        val result = when (g) {
            2 -> p.mod(BigInteger.valueOf(8)) == BigInteger.valueOf(7)
            3 -> p.mod(BigInteger.valueOf(3)) == BigInteger.valueOf(2)
            4 -> true
            5 -> {
                val rem = p.mod(BigInteger.valueOf(5))
                rem == BigInteger.ONE || rem == BigInteger.valueOf(4)
            }
            6 -> {
                val rem = p.mod(BigInteger.valueOf(24))
                rem == BigInteger.valueOf(19) || rem == BigInteger.valueOf(23)
            }
            7 -> {
                val rem = p.mod(BigInteger.valueOf(7))
                rem == BigInteger.valueOf(3) || rem == BigInteger.valueOf(5) || rem == BigInteger.valueOf(6)
            }
            else -> throw IllegalArgumentException("SRP: unexpected g = $g: g should be equal to 2, 3, 4, 5, 6 or 7")
        }
        require(result) { "SRP: g should be a quadratic residue mod p" }
    }
}
