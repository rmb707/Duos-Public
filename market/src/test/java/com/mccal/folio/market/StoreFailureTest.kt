package com.mccal.folio.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * What happens when the phone won't take a write.
 *
 * The store used to swallow every failure and say nothing, so a package could end up applied to Home and missing from
 * the list - unremovable - and a failed write could destroy the value that was already there, including a source's
 * pinned key.
 */
class StoreFailureTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "CHANGELOG.md").exists() }
    private val cabinetDir = File(root, "docs/sdk/source/packages/cabinet")

    private class FakeHost : PackageHost {
        override val capabilities = Capability.entries.toSet()
        val applied = mutableListOf<PackageChange>()
        val restored = mutableListOf<PackageChange>()
        override fun apply(change: PackageChange): String = "before".also { applied += change }
        override fun restore(change: PackageChange, snapshot: String) { restored += change }
    }

    /** Takes writes until it's told to stop, the way a full disk does. */
    private class FailingStore(var failFrom: Int = Int.MAX_VALUE) : KeyValueStore {
        private val values = HashMap<String, String>()
        var writes = 0
        override fun get(key: String) = values[key]
        override fun set(key: String, value: String?): Boolean {
            if (++writes >= failFrom) return false
            if (value == null) values.remove(key) else values[key] = value
            return true
        }
    }

    private fun pack(): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            for (name in listOf("manifest.json", "depiction.json", "tweaks.json")) {
                zip.putNextEntry(ZipEntry(name)); zip.write(File(cabinetDir, name).readBytes()); zip.closeEntry()
            }
        }
    }.toByteArray()

    @Test fun `a package that can't be written down is put back, not left on the Home screen`() {
        val keyValue = FailingStore()
        val store = InstalledStore(keyValue)
        val host = FakeHost()
        val installer = PackageInstaller(store, host)
        keyValue.failFrom = 2 // the marker is written first; the record is next

        val result = installer.install(pack())
        assertTrue("$result", result is InstallResult.Failed)
        assertEquals(InstallResult.Reason.APPLY, (result as InstallResult.Failed).reason)
        // Applied, then put back: Home is as it was, and nothing claims to be installed.
        assertEquals(host.applied.size, host.restored.size)
        assertTrue(store.installed().isEmpty())
    }

    @Test fun `a write that fails leaves the value that was there`() {
        val dir = Files.createTempDirectory("folio-store").toFile()
        val store = FileStore(dir)
        assertTrue(store.set("source:https://maya.example/:state", """{"keyBase64":"the pinned key"}"""))

        // The store can't write beside the target any more, so the move can't happen.
        val locked = File(dir, "nope")
        locked.writeText("not a directory")
        val blocked = FileStore(locked)
        assertFalse("a write into a file-as-a-folder can't succeed", blocked.set("k", "v"))

        // The first store still has what it had, which is the point: a lost pinned key asks the user to trust a
        // source all over again, exactly as a stolen key would.
        assertEquals("""{"keyBase64":"the pinned key"}""", store.get("source:https://maya.example/:state"))
        assertNull(blocked.get("k"))
        dir.deleteRecursively()
    }

    @Test fun `two writers to one key don't wipe it between them`() {
        val dir = Files.createTempDirectory("folio-store").toFile()
        val store = FileStore(dir)
        val threads = (1..8).map { n ->
            Thread { repeat(40) { store.set("installed:packages", "value from writer $n") } }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        // Whoever wrote last, the key exists and holds a whole value - never nothing, and never half a file.
        val value = store.get("installed:packages")
        assertTrue("the key was lost between writers", value != null && value.startsWith("value from writer "))
        assertTrue("stray temp files left behind", dir.listFiles()!!.none { it.name.contains(".tmp") })
        dir.deleteRecursively()
    }
}
