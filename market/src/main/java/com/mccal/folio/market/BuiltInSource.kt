package com.mccal.folio.market

/**
 * Folio's own source: the themes and tweaks that ship inside the app, written as real packages
 * (`docs/sdk/source`, copied into assets at build time). It needs no network and no signature, because the files came
 * with the app the user already installed.
 *
 * [read] and [list] are given by the caller, so the app can read assets while the tests read the repository. The
 * build writes a `files.json` next to the packages listing every file, because an APK's assets can't be listed
 * reliably; [list] is only the fallback when that file isn't there.
 */
class BuiltInSource(
    private val read: (path: String) -> ByteArray?,
    private val list: (path: String) -> List<String> = { emptyList() },
) {
    /** Every file in the source, relative to [ROOT], from `files.json`. Null when the build didn't write one. */
    private val paths: List<String>? by lazy {
        val text = read(FILES)?.decodeToString() ?: return@lazy null
        runCatching {
            val array = org.json.JSONArray(text)
            (0 until array.length()).map(array::getString).filter { SAFE_PATH.containsMatchIn(it) }
        }.getOrNull()
    }
    /** The package list, or null when the bundled files are missing or unreadable (a build problem, not a user one). */
    fun index(): RepoIndex? = read(INDEX)?.decodeToString()?.let { (RepoIndex.parse(it) as? ParseResult.Ok)?.value }

    /** The revocation list Folio ships with, so a package pulled after a release is off even before the first refresh. */
    fun revocations(): RevocationList? =
        read(REVOKED)?.decodeToString()?.let { (RevocationList.parse(it) as? ParseResult.Ok)?.value }

    /** Every package's files, by package id. The folder names aren't ids, so each manifest says which is which. */
    fun packages(): Map<String, Map<String, ByteArray>> = folders().mapNotNull { folder ->
        val files = filesIn(folder)
        val manifest = files[PackageArchive.MANIFEST]?.decodeToString() ?: return@mapNotNull null
        val id = (PackageManifest.parse(manifest) as? ParseResult.Ok)?.value?.id ?: return@mapNotNull null
        id to files
    }.toMap()

    /** One package's files, for installing it. */
    fun filesFor(id: String): Map<String, ByteArray>? = packages()[id]

    /** An image the index or a page points at, such as `assets/home-clear.webp`. */
    fun asset(path: String): ByteArray? = if (SAFE_PATH.containsMatchIn(path)) read("$ROOT/$path") else null

    /** The package folders, from `files.json` when it's there and by listing when it isn't. */
    private fun folders(): List<String> = paths
        ?.mapNotNull { it.removePrefix("packages/").takeIf { path -> it.startsWith("packages/") }?.substringBefore('/') }
        ?.distinct()
        ?.sorted()
        ?: list(PACKAGES)

    private fun filesIn(folder: String): Map<String, ByteArray> {
        val names = paths
            ?.filter { it.startsWith("packages/$folder/") }
            ?.map { it.removePrefix("packages/$folder/") }
            ?: list("$PACKAGES/$folder")
        return names.mapNotNull { name -> read("$PACKAGES/$folder/$name")?.let { name to it } }.toMap()
    }

    companion object {
        const val ROOT = "market/source"
        const val INDEX = "$ROOT/index.json"
        const val REVOKED = "$ROOT/revoked.json"
        const val PACKAGES = "$ROOT/packages"
        const val FILES = "$ROOT/files.json"
    }
}
