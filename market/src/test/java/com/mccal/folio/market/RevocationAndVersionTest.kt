package com.mccal.folio.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The two checks a package used to get past: a `minFolio` higher than this build, and a revoked source address
 * written without the slash Folio stores.
 */
class RevocationAndVersionTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "CHANGELOG.md").exists() }
    private val cabinetDir = File(root, "docs/sdk/source/packages/cabinet")

    private class FakeHost(override val capabilities: Set<Capability> = Capability.entries.toSet()) : PackageHost {
        override fun apply(change: PackageChange) = "before"
        override fun restore(change: PackageChange, snapshot: String) = Unit
    }

    private fun pack(replace: Map<String, String> = emptyMap()): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for (name in listOf("manifest.json", "depiction.json", "tweaks.json")) {
                var text = File(cabinetDir, name).readText()
                replace.forEach { (from, to) -> text = text.replace(from, to) }
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun installer(version: String?) = PackageInstaller(
        InstalledStore(MemoryStore()), FakeHost(),
        folioVersion = version?.let { FolioVersion.parse(it) },
    )

    @Test fun `a package that asks for a later Folio is refused, not installed`() {
        val bytes = pack(mapOf("\"minFolio\"" to "\"minFolio\"").plus("0.6.6" to "9.9.9"))
        val result = installer("0.7.0").install(bytes)
        assertTrue("expected NeedsNewerFolio, got $result", result is InstallResult.NeedsNewerFolio)
    }

    @Test fun `the same package installs on a build that is new enough`() {
        val result = installer("9.9.9").install(pack(mapOf("0.6.6" to "9.9.9")))
        assertTrue("expected Installed, got $result", result is InstallResult.Installed)
    }

    @Test fun `a revoked source matches whether or not its address ends in a slash`() {
        val list = RevocationList.parse(
            """{"format":1,"timestamp":1789660320,"packages":[],"sources":[{"url":"https://evil.example/repo","reason":"Malware"}]}""",
        )
        val revocations = (list as ParseResult.Ok).value
        assertEquals("Malware", revocations.reasonForSource("https://evil.example/repo/"))
        assertEquals("Malware", revocations.reasonForSource("https://evil.example/repo"))
        assertNull(revocations.reasonForSource("https://good.example/repo/"))
    }
}
