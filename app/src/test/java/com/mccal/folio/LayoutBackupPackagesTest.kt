package com.mccal.folio

import com.mccal.folio.market.DebVersion
import com.mccal.folio.market.InstalledPackage
import com.mccal.folio.market.InstalledStore
import com.mccal.folio.market.MemoryStore
import com.mccal.folio.market.PackageChange
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A layout backup carries what the Market installed. Without this, a phone restored from a backup came up with the
 * layout back and every package silently gone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LayoutBackupPackagesTest {
    private val state = LauncherState(homeSlots = List(HOME_CELLS) { null }, widgetPlacements = emptyList(), loading = false)

    private fun phoneWithAPackage(): InstalledStore {
        val store = InstalledStore(MemoryStore())
        store.put(
            InstalledPackage(
                id = "dev.maya.sunset", version = DebVersion.parse("1.2.0")!!, name = "Sunset",
                origin = InstalledPackage.Origin.FOLIO_SOURCE, sourceUrl = "https://example.test/source/",
                installedAt = 1_789_000_000, snapshots = listOf("what it replaced on that phone"),
            ),
            changes = listOf(PackageChange.Theme("""{"name":"Sunset"}""")),
        )
        return store
    }

    @Test fun `a backup carries the packages, and a restore can read them back`() {
        val raw = encodeLayoutBackup(state, emptyList(), "old-phone", phoneWithAPackage().export())
        val preview = decodeLayoutBackup(raw, emptyList(), emptyList(), "new-phone")

        val fresh = InstalledStore(MemoryStore())
        val backup = fresh.readBackup(preview.packages!!)!!
        assertEquals(listOf("dev.maya.sunset"), backup.records.map { it.id })
        assertEquals("Sunset", backup.records.single().name)
        assertEquals(listOf("dev.maya.sunset"), backup.wasOn)
        // What it replaced, it replaced on the other phone. That never travels; the change it made does.
        assertEquals(emptyList<String>(), backup.records.single().snapshots)
        fresh.restore(backup)
        val change = fresh.changesFor("dev.maya.sunset", DebVersion.parse("1.2.0")!!)?.single()
        assertEquals(PackageChange.Theme("""{"name":"Sunset"}"""), change)
        assertFalse("it goes in off, until the installer applies it here", fresh.find("dev.maya.sunset")!!.enabled)
    }

    @Test fun `a phone with no packages writes no packages, and an older backup carries none`() {
        val empty = encodeLayoutBackup(state, emptyList(), "old-phone", InstalledStore(MemoryStore()).export())
        // An empty store still exports a document; what matters is that the backup can be read either way.
        assertEquals(0, JSONObject(empty).getJSONObject("packages").getJSONArray("packages").length())

        val none = encodeLayoutBackup(state, emptyList(), "old-phone")
        assertFalse("no key at all, so a Folio without the Market never sees one", JSONObject(none).has("packages"))
        assertNull(decodeLayoutBackup(none, emptyList(), emptyList(), "new-phone").packages)
    }

    @Test fun `the backup version does not move, so an older Folio still reads one made here`() {
        val raw = encodeLayoutBackup(state, emptyList(), "old-phone", phoneWithAPackage().export())
        assertEquals(LAYOUT_BACKUP_VERSION, JSONObject(raw).getInt("version"))
        // What an older Folio does with it: everything but the key it has never heard of.
        val older = JSONObject(raw).also { it.remove("packages") }.toString()
        assertTrue(decodeLayoutBackup(older, emptyList(), emptyList(), "new-phone").layout.slots.isEmpty())
    }
}
