package com.mccal.folio.market

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Reads a `.foliopkg` (a zip) into memory, refusing anything that isn't a plain file inside the package (T7).
 *
 * Nothing is ever written to disk while reading, so a path that escapes the package, or an entry that claims to be a
 * link, can't reach the file system: names are checked, and only the closed set of file types below is kept. The caps
 * stop a small file from expanding into a large one.
 */
object PackageArchive {
    const val MAX_COMPRESSED = 20 * 1024 * 1024
    const val MAX_UNCOMPRESSED = 50 * 1024 * 1024
    const val MAX_ENTRIES = 500
    const val MAX_NAME = 200

    /** The only file types a package may contain. Everything else, including `classes.dex`, is refused (T6). */
    val ALLOWED_EXTENSIONS = setOf("json", "png", "webp", "jpg", "jpeg", "js")

    private val NAME = Regex("^(?!/)(?!.*\\.\\.)[A-Za-z0-9._/-]+\\z")

    sealed interface Result {
        data class Ok(val files: Map<String, ByteArray>) : Result
        data class Rejected(val reason: String) : Result
    }

    fun read(bytes: ByteArray): Result {
        if (bytes.isEmpty()) return Result.Rejected("that file is empty")
        if (bytes.size > MAX_COMPRESSED) return Result.Rejected("a package can be at most ${MAX_COMPRESSED / 1024 / 1024} MB")
        val files = LinkedHashMap<String, ByteArray>()
        var total = 0L
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    if (files.size >= MAX_ENTRIES) return Result.Rejected("a package can hold at most $MAX_ENTRIES files")
                    if (entry.isDirectory) continue
                    if (name.length > MAX_NAME) return Result.Rejected("a file name is too long")
                    // "../" and absolute paths are the zip-slip attack; a backslash is one on a system that splits on it.
                    if (!NAME.matches(name) || '\\' in name) return Result.Rejected("\"${name.take(60)}\" isn't a name a package may use")
                    val extension = name.substringAfterLast('.', "").lowercase()
                    if (extension !in ALLOWED_EXTENSIONS) return Result.Rejected("Folio doesn't accept .$extension files in a package")
                    if (name in files) return Result.Rejected("\"${name.take(60)}\" appears twice")
                    val content = zip.readAtMost(MAX_UNCOMPRESSED - total)
                        ?: return Result.Rejected("that package unpacks to more than ${MAX_UNCOMPRESSED / 1024 / 1024} MB")
                    total += content.size
                    files[name] = content
                }
            }
        } catch (e: Exception) {
            return Result.Rejected("that file isn't a package Folio can open")
        }
        if (files.isEmpty()) return Result.Rejected("that package has no files in it")
        if (MANIFEST !in files) return Result.Rejected("a package needs a manifest.json")
        return Result.Ok(files)
    }

    const val MANIFEST = "manifest.json"

    /** Reads at most [limit] bytes, or null when there are more: a zip bomb never finishes expanding. */
    private fun java.io.InputStream.readAtMost(limit: Long): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = read(buffer)
            if (read < 0) return out.toByteArray()
            if (out.size() + read > limit) return null
            out.write(buffer, 0, read)
        }
    }
}
