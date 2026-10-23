package com.focuslock.mdm

import java.math.BigInteger
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Offline check of a FocusLock Pro licence code.
 *
 * The payment server signs codes with an ECDSA P-256 private key that never
 * leaves the server. This app only carries the matching public key, so it can
 * tell a real code from a fake one but cannot make one. (The 2.0 scheme kept
 * an HMAC secret in the APK, which let anyone who unpacked it mint codes.)
 *
 * Code: "FL2-" + base32(10-byte payload + 64-byte r||s signature), grouped
 * in fives. The layout is documented next to the issuer in the fulfillment
 * server's licence.js; both sides must change together.
 *
 * Pure JVM on purpose: no Android types, so it is unit-tested on the desktop
 * against codes produced by the real Node issuer.
 */
object LicenceVerifier {

    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private const val PREFIX = "FL2"
    private const val PAYLOAD_BYTES = 10
    private const val SIGNATURE_BYTES = 64
    private const val VERSION: Int = 1

    /** SPKI DER of the server's P-256 public key, base64. Public by design. */
    const val PUBLIC_KEY_B64 =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE8vXlFFMaCgYJFcG0oYIbGK6ntUy1ErUjAZ9wAdG70K5plREyuxbKae7PfBs5HLR0DL1XKjK2QijS70sAjK7hLQ=="

    private val publicKey: PublicKey by lazy { decodeKey(PUBLIC_KEY_B64) }

    fun decodeKey(b64: String): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(b64)))

    /** Strips spaces, dashes and case; null if it is not an FL2 code at all. */
    fun normalize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }
        // Also accept the deep-link form focuslock://pro/FL2-...
        val start = cleaned.indexOf(PREFIX)
        if (start < 0) return null
        return cleaned.substring(start + PREFIX.length)
    }

    /** The canonical display form, or null if [raw] is not a well-formed code. */
    fun format(raw: String?): String? {
        val body = normalize(raw) ?: return null
        if (decode(body)?.size != PAYLOAD_BYTES + SIGNATURE_BYTES) return null
        return PREFIX + "-" + body.chunked(5).joinToString("-")
    }

    fun verify(raw: String?, key: PublicKey = publicKey): Boolean {
        val body = normalize(raw) ?: return false
        val bytes = decode(body) ?: return false
        if (bytes.size != PAYLOAD_BYTES + SIGNATURE_BYTES) return false
        if ((bytes[0].toInt() and 0xff) != VERSION) return false
        val payload = bytes.copyOfRange(0, PAYLOAD_BYTES)
        val sig = bytes.copyOfRange(PAYLOAD_BYTES, bytes.size)
        return try {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(key)
                update(payload)
                verify(rawToDer(sig))
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun decode(text: String): ByteArray? {
        var bits = 0
        var value = 0
        val out = java.io.ByteArrayOutputStream()
        for (ch in text) {
            val idx = ALPHABET.indexOf(ch)
            if (idx < 0) return null
            value = (value shl 5) or idx
            bits += 5
            if (bits >= 8) {
                out.write((value ushr (bits - 8)) and 0xff)
                bits -= 8
            }
            value = value and 0xff
        }
        // The last character carries a few padding bits. They must be zero, or
        // one code would have several spellings that all verify.
        if (bits >= 5 || (value and ((1 shl bits) - 1)) != 0) return null
        return out.toByteArray()
    }

    /** Java's verifier wants ASN.1 DER; the code carries raw 32-byte r and s. */
    private fun rawToDer(raw: ByteArray): ByteArray {
        fun integer(part: ByteArray): ByteArray {
            val v = BigInteger(1, part).toByteArray()
            return byteArrayOf(0x02, v.size.toByte()) + v
        }
        val body = integer(raw.copyOfRange(0, 32)) + integer(raw.copyOfRange(32, 64))
        return byteArrayOf(0x30, body.size.toByte()) + body
    }
}
