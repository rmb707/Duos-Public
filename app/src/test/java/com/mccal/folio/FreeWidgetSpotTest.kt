package com.mccal.folio

import org.junit.Assert.assertEquals
import org.junit.Test

class FreeWidgetSpotTest {
    @Test fun `pinned widgets take the first free spot without moving anything`() {
        val slots = MutableList<String?>(HOME_CELLS) { null }.apply { for (i in 0 until 8) this[i] = "app$i" }
        val layout = HomeLayout(slots = slots, dock = listOf(null, null, null, null))
        // Rows 0-1 are full of apps: a 2×2 fits at row 2, column 0 of page 0.
        assertEquals(0 to 8, firstFreeWidgetSpot(layout, 2, 2))
        // A widget already there pushes the next one beside it.
        val withWidget = layout.copy(widgetPlacements = listOf(WidgetPlacement(0, 5, 0, 0, 2, 2, 2)))
        assertEquals(0 to 10, firstFreeWidgetSpot(withWidget, 2, 2))
        // A full page sends it to a new page.
        val full = HomeLayout(slots = List(HOME_CELLS) { "a$it" }, dock = listOf(null, null, null, null))
        assertEquals(1 to HOME_CELLS, firstFreeWidgetSpot(full, 4, 2))
    }
}
