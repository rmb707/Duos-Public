package com.mccal.folio.market

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest
import org.json.JSONObject
import kotlin.math.sign

/**
 * Jazzer fuzz targets for every parser. A normal test run replays the seeds in
 * `resources/com/mccal/folio/market/ParserFuzzTestInputs/<target>/`; fuzzing mode explores for 5 minutes per target:
 *
 *     JAZZER_FUZZ=1 ./gradlew :market:testDebugUnitTest --tests '*ParserFuzzTest.manifest'
 *
 * Any exception, hang or broken invariant is a finding, and Jazzer saves the input next to the seeds.
 */
class ParserFuzzTest {
    @FuzzTest(maxDuration = "5m")
    fun manifest(data: FuzzedDataProvider) {
        val result = PackageManifest.parse(data.consumeRemainingAsString())
        checkReport(result)
        if (result is ParseResult.Ok) {
            val m = result.value
            check(PackageManifest.ID.containsMatchIn(m.id) && m.kinds.isNotEmpty())
            check(m.name.english.codePointCount(0, m.name.english.length) <= PackageManifest.MAX_NAME)
            m.icon?.let { check(!it.startsWith("/") && ".." !in it) }
        }
    }

    @FuzzTest(maxDuration = "5m")
    fun depiction(data: FuzzedDataProvider) {
        val result = Depiction.parse(data.consumeRemainingAsString())
        checkReport(result)
        if (result is ParseResult.Ok) {
            check(result.value.blocks.size <= Depiction.MAX_BLOCKS)
            for (block in result.value.blocks) when (block) {
                is DepictionBlock.Link -> check(block.url.startsWith("https://"))
                is DepictionBlock.Donation -> check(block.url.startsWith("https://"))
                else -> Unit
            }
        }
    }

    @FuzzTest(maxDuration = "5m")
    fun entry(data: FuzzedDataProvider) {
        val result = SourceEntry.parse(data.consumeRemainingAsString())
        checkReport(result)
        if (result is ParseResult.Ok) {
            val entry = result.value
            check(SourceEntry.KEY_ID.containsMatchIn(entry.keyId))
            check(FileRef.SHA256.containsMatchIn(entry.index.sha256))
            check(entry.index.size in 1..SourceEntry.MAX_INDEX_BYTES && entry.maxAge in 3600..2592000)
            check(!entry.index.path.startsWith("/") && ".." !in entry.index.path)
        }
    }

    @FuzzTest(maxDuration = "5m")
    fun index(data: FuzzedDataProvider) {
        val result = RepoIndex.parse(data.consumeRemainingAsString())
        checkReport(result)
        if (result is ParseResult.Ok) {
            val index = result.value
            check(index.packages.size <= RepoIndex.MAX_PACKAGES && index.featured.size <= RepoIndex.MAX_FEATURED)
            for (pkg in index.packages) {
                // An entry is only installable when the index and the manifest copy agree.
                check(pkg.manifest == null || (pkg.manifest.id == pkg.id && pkg.manifest.version == pkg.version))
                pkg.url?.let { check(!it.startsWith("/") && ".." !in it) }
                check(!pkg.installable || pkg.sha256?.let(FileRef.SHA256::containsMatchIn) == true)
            }
        }
    }

    @FuzzTest(maxDuration = "5m")
    fun revoked(data: FuzzedDataProvider) {
        val result = RevocationList.parse(data.consumeRemainingAsString())
        checkReport(result)
        if (result is ParseResult.Ok) {
            val list = result.value
            check(list.packages.size <= RevocationList.MAX_ENTRIES)
            for (entry in list.packages) {
                check(entry.versions.isNotEmpty() && entry.versions.size <= RevocationList.MAX_VERSIONS)
                // A named version must be a real version; null is the "*" wildcard.
                entry.versions.filterNotNull().forEach { check(DebVersion.parse(it) != null) }
            }
        }
    }

    /** A package file is opened in memory, so no name it carries can ever reach the file system (T7). */
    @FuzzTest(maxDuration = "5m")
    fun archive(data: FuzzedDataProvider) {
        when (val result = PackageArchive.read(data.consumeRemainingAsBytes())) {
            is PackageArchive.Result.Rejected -> check(result.reason.isNotEmpty())
            is PackageArchive.Result.Ok -> {
                check(PackageArchive.MANIFEST in result.files)
                check(result.files.size <= PackageArchive.MAX_ENTRIES)
                check(result.files.values.sumOf { it.size } <= PackageArchive.MAX_UNCOMPRESSED)
                for (name in result.files.keys) {
                    check(!name.startsWith("/") && ".." !in name && '\\' !in name)
                    check(name.substringAfterLast('.', "").lowercase() in PackageArchive.ALLOWED_EXTENSIONS)
                }
            }
        }
    }

    @FuzzTest(maxDuration = "5m")
    fun tweaks(data: FuzzedDataProvider) {
        val result = TweakBundle.parse(data.consumeRemainingAsString())
        checkReport(result)
        if (result is ParseResult.Ok) {
            check(result.value.tweaks.isNotEmpty() && result.value.tweaks.size <= TweakBundle.MAX_TWEAKS)
        }
    }

    /**
     * `signature.json`, which comes out of a `.foliopkg` someone was sent: the one file in a package that is read
     * before Folio knows who made it.
     */
    @FuzzTest(maxDuration = "5m")
    fun authorSignature(data: FuzzedDataProvider) {
        val text = data.consumeRemainingAsString()
        val signature = AuthorSignature.fromFiles(mapOf(AuthorSignature.FILE to text.toByteArray())) ?: return
        // Anything it hands back has a key Folio can read, or it should have refused it.
        check(signature.key != null)
        // And a signature over nothing in particular verifies nothing.
        val version = DebVersion.parse("1.0.0")!!
        check(!signature.verifies("dev.someone.package", version, "a".repeat(64)))
    }

    /**
     * What a signature inside a package covers can't depend on the signature itself, or signing would change what
     * was signed. Nor on the order the files arrive in.
     */
    @FuzzTest(maxDuration = "5m")
    fun authorPayload(data: FuzzedDataProvider) {
        val count = data.consumeInt(0, 8)
        val files = buildMap {
            repeat(count) { put(data.consumeString(24), data.consumeBytes(64)) }
        }
        val version = DebVersion.parse("1.0.0")!!
        val payload = AuthorSignature.filesPayload("dev.someone.package", version, files)
        check(payload == AuthorSignature.filesPayload("dev.someone.package", version, files.entries.reversed().associate { it.key to it.value }))
        check(payload == AuthorSignature.filesPayload("dev.someone.package", version, files + (AuthorSignature.FILE to data.consumeRemainingAsBytes())))
    }

    /** Whatever JsonGuard accepts, org.json must read without an exception. */
    @FuzzTest(maxDuration = "5m")
    fun jsonGuard(data: FuzzedDataProvider) {
        val text = data.consumeRemainingAsString()
        if (JsonGuard.check(text, 64 * 1024) == null) JSONObject(text)
    }

    /** dpkg ordering stays a total order that agrees with equals and hashCode. */
    @FuzzTest(maxDuration = "5m")
    fun versions(data: FuzzedDataProvider) {
        val a = DebVersion.parse(data.consumeString(80)) ?: return
        val b = DebVersion.parse(data.consumeString(80)) ?: return
        val c = DebVersion.parse(data.consumeRemainingAsString()) ?: return
        check(a.compareTo(b).sign == -b.compareTo(a).sign)
        check((a.compareTo(b) == 0) == (a == b))
        if (a == b) check(a.hashCode() == b.hashCode())
        if (a <= b && b <= c) check(a <= c)
        PackageRelation.parse("x.y (>= $a)")?.let { check(it.matches(a)) }
    }

    @FuzzTest(maxDuration = "5m")
    fun localizedText(data: FuzzedDataProvider) {
        val text = data.consumeString(4096)
        val preferred = List(data.consumeInt(0, 4)) { data.consumeString(16) }
        if (JsonGuard.check(text, 8192) != null) return
        val value = LocalizedText.read(JSONObject(text), 400) {} ?: return
        check(value.resolve(preferred) in listOf(value.english) + value.languages.map { value.resolve(listOf(it)) })
    }

    private fun checkReport(result: ParseResult<*>) {
        when (result) {
            is ParseResult.Invalid -> check(result.errors.size in 1..Problems.MAX_REPORTED)
            is ParseResult.Unsupported -> check(result.needs.isNotEmpty())
            is ParseResult.Ok -> check(result.ignored.size <= Problems.MAX_REPORTED)
        }
    }
}
