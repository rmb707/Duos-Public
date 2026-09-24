package com.mccal.folio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IPhoneHomeTest {
    @Test fun `arranges page one and dock without losing apps`() {
        val slots = MutableList<String?>(HOME_CELLS) { null }.apply { this[8] = "old-a"; this[20] = "cal" }
        val layout = HomeLayout(slots = slots, dock = listOf("old-dock", null, null, null),
            widgetPlacements = listOf(WidgetPlacement(0, CLOCK_WIDGET, 0, 0, 0, 4, 2)),
            folders = listOf(FolderEntry("folder:x", "F", listOf("maps"))))
        val result = arrangeLikeIPhone(layout, mapOf(IPhoneApp.FACETIME to "meet", IPhoneApp.CALENDAR to "cal",
            IPhoneApp.MAPS to "maps", IPhoneApp.PHONE to "dialer", IPhoneApp.MUSIC to "spotify"))
        assertEquals("meet", result.slots[8])       // row 3 of the grid = first app row under the widgets
        assertEquals("cal", result.slots[9])        // moved from where it was
        assertEquals(null, result.slots[20])
        assertEquals(null, result.slots[15])        // Maps is in a folder: left alone
        assertEquals(listOf("dialer", null, null, "spotify"), result.dock)
        val moved = result.slots.drop(HOME_CELLS)
        assertTrue("old-a" in moved && "old-dock" in moved)
        assertEquals(1, result.slots.count { it == "cal" })
    }
}
