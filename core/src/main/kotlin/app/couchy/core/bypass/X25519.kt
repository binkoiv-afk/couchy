package app.couchy.core.bypass

import java.math.BigInteger
import java.security.SecureRandom

/** WireGuard keys (Curve25519, RFC 7748), for Android versions without a built-in X25519. */
object X25519 {

    private val A24 = BigInteger.valueOf(121665)
    private val TWO = BigInteger.valueOf(2)
    private val P = TWO.pow(255) - BigInteger.valueOf(19)

    /** A new private key, base64 like `wg genkey`. */
    fun privateKey(random: SecureRandom = SecureRandom()): String {
        val k = ByteArray(32).also(random::nextBytes)
        k[0] = (k[0].toInt() and 248).toByte()
        k[31] = ((k[31].toInt() and 127) or 64).toByte()
        return Base64.encode(k)
    }

    /** The public key for a base64 private key, like `wg pubkey`. */
    fun publicKey(privateKey: String): String {
        val base = ByteArray(32).also { it[0] = 9 }
        return Base64.encode(multiply(Base64.decode(privateKey), base))
    }

    /** Scalar multiplication on the Montgomery curve; both arguments and the result are 32 little-endian bytes. */
    fun multiply(scalar: ByteArray, point: ByteArray): ByteArray {
        val k = scalar.copyOf().also {
            it[0] = (it[0].toInt() and 248).toByte()
            it[31] = ((it[31].toInt() and 127) or 64).toByte()
        }
        val u = decode(point.copyOf().also { it[31] = (it[31].toInt() and 127).toByte() })
        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = u
        var z3 = BigInteger.ONE
        var swap = 0
        for (t in 254 downTo 0) {
            val bit = (k[t ushr 3].toInt() ushr (t and 7)) and 1
            swap = swap xor bit
            if (swap == 1) {
                x2 = x3.also { x3 = x2 }
                z2 = z3.also { z3 = z2 }
            }
            swap = bit
            val a = (x2 + z2).mod(P)
            val aa = (a * a).mod(P)
            val b = (x2 - z2).mod(P)
            val bb = (b * b).mod(P)
            val e = (aa - bb).mod(P)
            val c = (x3 + z3).mod(P)
            val d = (x3 - z3).mod(P)
            val da = (d * a).mod(P)
            val cb = (c * b).mod(P)
            x3 = (da + cb).pow(2).mod(P)
            z3 = (u * (da - cb).pow(2)).mod(P)
            x2 = (aa * bb).mod(P)
            z2 = (e * (aa + A24 * e)).mod(P)
        }
        if (swap == 1) {
            x2 = x3
            z2 = z3
        }
        return encode((x2 * z2.modPow(P - TWO, P)).mod(P))
    }

    private fun decode(bytes: ByteArray): BigInteger = BigInteger(1, bytes.reversedArray())

    private fun encode(n: BigInteger): ByteArray {
        val be = n.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else it }
        val out = ByteArray(32)
        for (i in be.indices) out[i] = be[be.size - 1 - i]
        return out
    }
}

/** Standard base64; java.util.Base64 is missing on older Android. */
object Base64 {
    private const val ABC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encode(bytes: ByteArray): String = buildString {
        for (i in bytes.indices step 3) {
            val n = (bytes[i].toInt() and 255 shl 16) or
                ((bytes.getOrNull(i + 1)?.toInt() ?: 0) and 255 shl 8) or
                ((bytes.getOrNull(i + 2)?.toInt() ?: 0) and 255)
            append(ABC[n ushr 18 and 63]).append(ABC[n ushr 12 and 63])
            append(if (i + 1 < bytes.size) ABC[n ushr 6 and 63] else '=')
            append(if (i + 2 < bytes.size) ABC[n and 63] else '=')
        }
    }

    fun decode(text: String): ByteArray {
        val clean = text.trim().trimEnd('=').filter { it in ABC }
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in clean) {
            buffer = buffer shl 6 or ABC.indexOf(c)
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write(buffer ushr bits and 255)
            }
        }
        return out.toByteArray()
    }
}
