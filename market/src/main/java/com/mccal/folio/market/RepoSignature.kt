package com.mccal.folio.market

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * A source's signing key, pinned the first time the user adds the source (ADR 0002).
 *
 * The key is an ECDSA P-256 public key in SPKI form. [keyId] is the first 8 bytes of its SHA-256, which is what
 * `entry.json` names; [fingerprint] is the whole SHA-256, shown to the user when they add the source.
 */
class SourceKey private constructor(val spki: ByteArray, private val key: PublicKey) {
    val keyId: String by lazy { fingerprintBytes.take(8).joinToString("") { "%02X".format(it) } }
    val fingerprint: String by lazy { fingerprintBytes.joinToString("") { "%02X".format(it) } }

    /** The fingerprint in groups of four, the way Folio shows it: `A1B2 C3D4 …`. */
    val fingerprintGroups: String by lazy { fingerprint.chunked(4).joinToString(" ") }

    val base64: String by lazy { Base64.getEncoder().encodeToString(spki) }

    private val fingerprintBytes: List<Byte> by lazy { MessageDigest.getInstance("SHA-256").digest(spki).toList() }

    /** True when [signature] (base64, at most 1 KB) is this key's signature over exactly these [bytes]. */
    fun verifies(bytes: ByteArray, signature: String): Boolean {
        val der = decodeBase64(signature.trim(), MAX_SIGNATURE_CHARS) ?: return false
        return try {
            Signature.getInstance(ALGORITHM).run {
                initVerify(key)
                update(bytes)
                verify(der)
            }
        } catch (e: Exception) {
            // A malformed signature is a failed check, never a crash.
            false
        }
    }

    override fun equals(other: Any?) = other is SourceKey && other.spki.contentEquals(spki)
    override fun hashCode() = spki.contentHashCode()
    override fun toString() = "SourceKey($keyId)"

    companion object {
        /**
         * ECDSA P-256 with SHA-256. Ed25519 would be smaller, but Android only supports it from API 33 and Folio's
         * minSdk is 31; the format carries a key id, so a later version can add an algorithm. See ADR 0002.
         */
        const val ALGORITHM = "SHA256withECDSA"
        const val MAX_KEY_CHARS = 512
        const val MAX_SIGNATURE_CHARS = 1024

        /** Reads a key from its base64 SPKI form, or null when it isn't a usable P-256 key. */
        fun parse(base64Spki: String): SourceKey? {
            val spki = decodeBase64(base64Spki.trim(), MAX_KEY_CHARS) ?: return null
            return try {
                val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))
                val curve = (key as? java.security.interfaces.ECPublicKey)?.params?.curve?.field?.fieldSize
                if (curve != 256) null else SourceKey(spki, key)
            } catch (e: Exception) {
                null
            }
        }

        private fun decodeBase64(text: String, maxChars: Int): ByteArray? {
            if (text.isEmpty() || text.length > maxChars) return null
            return try {
                Base64.getDecoder().decode(text)
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }
}

/** SHA-256 of [bytes] as lowercase hex, the form the index and entry files use. */
internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
