package com.mccal.folio

import org.junit.Assert.assertEquals
import org.junit.Test

class LayoutHistoryTest {
    private val layout = HomeLayout(
        slots = listOf("a", null, "folder:1", "b"), dock = listOf("phone", null, "chrome", null),
        widgetPlacements = listOf(WidgetPlacement(0, CLOCK_WIDGET, 0, 0, 0, 2, 2), WidgetPlacement(3, 42, 1, 2, 3, 2, 1)),
        folders = listOf(FolderEntry("folder:1", "Games", listOf("c", "d"))),
        widgetRestores = listOf(WidgetRestore(5, "com.x/.W", 0L, "Weather", "Personal", sourceScope = null)),
        leadingSlots = List(HOME_CELLS) { if (it == 1) "e" else null }, minPages = 3,
    )

    @Test fun `snapshots survive saving and loading unchanged`() {
        val snapshots = listOf(LayoutSnapshot(1_000L, "Before Arrange Like iPhone", layout), LayoutSnapshot(900L, "Saved by you", HomeLayout(emptyList(), List(4) { null })))
        assertEquals(snapshots, LayoutHistory.decode(LayoutHistory.encode(snapshots)))
    }

    @Test fun `unreadable history loads as empty instead of crashing`() {
        assertEquals(emptyList<LayoutSnapshot>(), LayoutHistory.decode("not json"))
        assertEquals(emptyList<LayoutSnapshot>(), LayoutHistory.decode(null))
    }
}
