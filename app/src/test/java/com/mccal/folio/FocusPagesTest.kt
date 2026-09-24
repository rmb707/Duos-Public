package com.mccal.folio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FocusPagesTest {
    private fun layout(): HomeLayout {
        val slots = MutableList<String?>(3 * HOME_CELLS) { null }
        slots[homeCellIndex(0, 8)] = "p0"; slots[homeCellIndex(1, 8)] = "p1"; slots[homeCellIndex(2, 9)] = "p2"
        return HomeLayout(slots, listOf("dock", null, null, null),
            widgetPlacements = listOf(WidgetPlacement(0, CLOCK_WIDGET, 0, 0, 0, 2, 2), WidgetPlacement(1, DATE_WIDGET, 2, 0, 0, 2, 2),
                WidgetPlacement(2, DATE_WIDGET, -1, 0, 0, 2, 2)), minPages = 3)
    }

    @Test fun `only chosen pages show, renumbered in order, with widgets and the dock kept`() {
        val filtered = FocusPages.filter(layout(), setOf(2, 0))
        assertEquals(2, filtered.pageCount)
        assertEquals("p0", filtered.slots[homeCellIndex(0, 8)])
        assertEquals("p2", filtered.slots[homeCellIndex(1, 9)])
        assertEquals(null, filtered.slots.getOrNull(homeCellIndex(1, 8)))
        assertEquals(listOf(0 to 0, 1 to 1, 2 to -1), filtered.widgetPlacements.map { it.slot to it.page })
        assertEquals(layout().dock, filtered.dock)
    }

    @Test fun `opening page follows the renumbering`() {
        val work = DEFAULT_FOCUS_MODES.first { it.id == "work" }.copy(pages = setOf(0, 2), homePage = 2)
        assertEquals(1, FocusPages.openPage(work, 3))
        assertNull(FocusPages.openPage(work.copy(homePage = null), 3))
        // Pages that don't exist are ignored; an empty choice still shows the first page.
        assertEquals(1, FocusPages.filter(layout(), setOf(9)).pageCount)
    }

    @Test fun `new items skip hidden pages`() {
        // Page 0 hidden, page 1 showing: the first free cell on page 1 is chosen.
        val hidden = (0 until HOME_CELLS).map { homeCellIndex(0, it) }.toSet()
        val slots = pinHomeApp(List(2 * HOME_CELLS) { null }, "new", true, hidden)
        assertEquals(homeCellIndex(1, 0), slots.indexOf("new"))
    }
}
