package com.mccal.folio.market

/**
 * Who wrote a package, as opposed to who is handing it out.
 *
 * A source's signature says "this list came from this source, unchanged". It says nothing about the author, so a
 * mirror can carry someone's package, and a mirror can change it. An author signature answers the other question:
 * this is the package that author published, byte for byte, whoever you got it from.
 *
 * What is signed is the package's identity bound to its bytes:
 *
 * ```
 * folio-pkg:<id>:<version>:<sha256 of the .foliopkg>
 * ```
 *
 * so a signature can't be lifted onto a different package, a different version, or altered bytes. The algorithm is
 * the one the rest of Folio uses: ECDSA P-256 with SHA-256, base64 ([SourceKey]).
 *
 * **Trust is pinned per package id**, the way a phone pins an app's signing key: whoever first publishes
 * `dev.maya.sunset-icons` owns that name, and a later copy signed by a different key is refused rather than
 * installed over the top. That is also what stops the id collision the store can only warn about today.
 */
data class AuthorSignature(val keyBase64: String, val signature: String) {
    val key: SourceKey? get() = SourceKey.parse(keyBase64)

    /** True when this really is [id] at [version] with those bytes, signed by [key]. */
    fun verifies(id: String, version: DebVersion, sha256: String): Boolean =
        key?.verifies(payload(id, version, sha256).toByteArray(), signature) == true

    /** True when this is [id] at [version] with exactly these files, signed by [key]. */
    fun verifiesFiles(id: String, version: DebVersion, files: Map<String, ByteArray>): Boolean =
        key?.verifies(filesPayload(id, version, files).toByteArray(), signature) == true

    companion object {
        const val PREFIX = "folio-pkg"

        /** A signature inside the package, which can't sign the archive it lives in. */
        const val FILES_PREFIX = "folio-pkg-files"
        const val FILE = "signature.json"

        fun payload(id: String, version: DebVersion, sha256: String) = "$PREFIX:$id:${version.text}:$sha256"

        /**
         * What a signature inside the package signs.
         *
         * Not the archive's own hash - a file can't contain a signature over itself - and not the zip's bytes
         * either, because rebuilding a zip from the same files changes them. It's a digest over what's actually in
         * the package: every other file, by name, with its own hash, in a fixed order.
         */
        fun filesPayload(id: String, version: DebVersion, files: Map<String, ByteArray>): String {
            val digest = sha256Hex(
                files.filterKeys { it != FILE }
                    .toSortedMap()
                    .entries
                    .joinToString("") { (name, bytes) -> "$name ${sha256Hex(bytes)}\n" }
                    .toByteArray(),
            )
            return "$FILES_PREFIX:$id:${version.text}:$digest"
        }

        /** Reads `signature.json` out of a package's files. Absent is fine; malformed is not. */
        fun fromFiles(files: Map<String, ByteArray>): AuthorSignature? {
            val text = files[FILE]?.decodeToString() ?: return null
            val problems = Problems()
            val json = parseStrictObject(text, 8 * 1024, problems) ?: return null
            if (problems.errors.isNotEmpty()) return null
            val key = json.optString("key").takeIf { it.isNotEmpty() } ?: return null
            val signature = json.optString("signature").takeIf { it.isNotEmpty() } ?: return null
            if (SourceKey.parse(key) == null) return null
            return AuthorSignature(key, signature)
        }

        /** Reads the `author` signing block from an index entry. Absent is fine; malformed is not. */
        internal fun read(f: Fields, at: String): AuthorSignature? {
            val block = f.obj("signedBy", false, setOf("key", "signature")) ?: return null
            val key = block.string("key", true, maxLength = 512)
            val signature = block.string("signature", true, maxLength = 512)
            if (key == null || signature == null) return null
            if (SourceKey.parse(key) == null) {
                f.problems.errors += "$at.signedBy.key isn't a key Folio can read"
                return null
            }
            return AuthorSignature(key, signature)
        }
    }
}

/**
 * What Folio knows about who owns a package id.
 *
 * First signed copy wins the name, and it keeps it. That is deliberately the same shape as pinning a source's key:
 * the first time is a decision the user makes by installing, and every time after that is Folio's to enforce.
 */
class AuthorTrust(private val store: KeyValueStore) {
    /** The key that owns [id] here, or null when Folio has never seen a signed copy of it. */
    fun keyFor(id: String): String? = store.get(storeKey(id))

    fun remember(id: String, keyBase64: String) = store.set(storeKey(id), keyBase64)

    fun forget(id: String) = store.set(storeKey(id), null)

    private fun storeKey(id: String) = "package:$id:author"

    /**
     * Whether this copy of [id] may be installed.
     *
     * [signedBy] is what the index offered, [sha256] the bytes that arrived. An unsigned package is allowed - most
     * of them are, and Folio says so on the page - but not once a signed one has been seen, because dropping the
     * signature is how an attacker would get around this.
     */
    fun check(id: String, version: DebVersion, sha256: String, signedBy: AuthorSignature?): Result =
        judge(id, signedBy) { it.verifies(id, version, sha256) }

    /**
     * The same question for a package that arrived as a file rather than from a source: there's no index to carry
     * the signature, so it's inside the package, over the files rather than the archive's bytes.
     */
    fun checkFiles(id: String, version: DebVersion, files: Map<String, ByteArray>): Result =
        judge(id, AuthorSignature.fromFiles(files)) { it.verifiesFiles(id, version, files) }

    private fun judge(id: String, signedBy: AuthorSignature?, verify: (AuthorSignature) -> Boolean): Result {
        val known = keyFor(id)
        if (signedBy == null) {
            return if (known == null) Result.Unsigned else Result.SignatureMissing
        }
        if (!verify(signedBy)) return Result.Broken
        if (known != null && known != signedBy.keyBase64) return Result.WrongAuthor
        return if (known == null) Result.FirstTime(signedBy.keyBase64) else Result.Signed
    }

    sealed interface Result {
        /** Good, and by the key that has always published this package. */
        data object Signed : Result

        /** Good, and the first signed copy Folio has seen: installing it pins the key to this id. */
        data class FirstTime(val keyBase64: String) : Result

        /** No signature, and none has ever been seen for this id. Allowed; the store says so. */
        data object Unsigned : Result

        /** Signed by somebody else's key. This is the impostor case, and it is refused. */
        data object WrongAuthor : Result

        /** A signature that doesn't match these bytes: altered package, or altered listing. */
        data object Broken : Result

        /** This package has always been signed, and this copy isn't. Refused, or the signature means nothing. */
        data object SignatureMissing : Result

        val installable: Boolean get() = this is Signed || this is FirstTime || this is Unsigned

        /** What the user is told. The store shows this next to the package, not in a dialog. */
        val message: String
            get() = when (this) {
                Signed -> "Signed by its developer"
                is FirstTime -> "Signed by its developer"
                Unsigned -> "Nobody signed this package"
                WrongAuthor -> "Signed by someone other than the developer who published it before"
                Broken -> "That package doesn't match its developer's signature"
                SignatureMissing -> "This package has always been signed, and this copy isn't"
            }
    }
}
