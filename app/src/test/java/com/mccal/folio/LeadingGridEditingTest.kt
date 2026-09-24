package com.mccal.folio

import org.junit.Assert.*
import org.junit.Test

class LeadingGridEditingTest {
    private val H = HOME_CELLS

    @Test fun `signed leading addresses round trip without changing normal pages`() {
        assertEquals(-1, homeCellPage(-H)); assertEquals(0, homeCellLocal(-H))
        assertEquals(-1, homeCellPage(-1)); assertEquals(H - 1, homeCellLocal(-1))
        assertEquals(-H, homeCellIndex(-1, 0)); assertEquals(-1, homeCellIndex(-1, H - 1))
        assertEquals(0, homeCellIndex(0, 0)); assertEquals(H, homeCellIndex(1, 0))
        val layout = HomeLayout(listOf("screen1"), emptyList(), leadingSlots = List(H) { if (it == H - 1) "leading" else null })
        assertEquals("leading", layout.slotAt(-1)); assertEquals(-1, layout.indexOfShortcut("leading"))
        assertEquals("screen1", layout.slotAt(0)); assertEquals(1, layout.pageCount)
    }

    @Test fun `leading moves into empty cells directly and occupied insertion stays bounded`() {
        val leading = MutableList<String?>(H) { null }.apply { this[0] = "a"; this[1] = "b"; this[H - 1] = "z" }
        val before = HomeLayout(listOf("home"), emptyList(), leadingSlots = leading)
        val direct = dropApp(before, "a", DropTarget.Home(-H + 2))
        assertNull(direct.slotAt(-H)); assertEquals("a", direct.slotAt(-H + 2)); assertEquals("b", direct.slotAt(-H + 1))
        val inserted = dropApp(before, "new", DropTarget.Home(-H + 1))
        assertEquals(listOf("a", "new", "b"), listOf(-H, -H + 1, -H + 2).map(inserted::slotAt))
        assertEquals(listOf("home"), inserted.slots)
        val full = HomeLayout(listOf("home"), emptyList(), leadingSlots = List(H) { "l$it" })
        assertSame(full, dropApp(full, "new", DropTarget.Home(-H)))
    }

    @Test fun `cross-surface moves are atomic and never duplicate shortcuts`() {
        val before = HomeLayout(listOf("home"), listOf(null, null, null, null), leadingSlots = List(H) { null })
        val toLeading = dropApp(before, "home", DropTarget.Home(-H))
        assertEquals("home", toLeading.slotAt(-H)); assertNull(toLeading.slotAt(0))
        val back = dropApp(toLeading, "home", DropTarget.Home(4))
        assertNull(back.slotAt(-H)); assertEquals("home", back.slotAt(4))
        assertEquals(1, (back.leadingSlots + back.slots + back.dock).count { it == "home" })
    }

    @Test fun `leading widgets collide with leading apps using signed cells`() {
        val widget = WidgetPlacement(4, 26, -1, 0, 0, 2, 2)
        val leading = List<String?>(H) { if (it == 2) "app" else null }
        val layout = HomeLayout(emptyList(), emptyList(), listOf(widget), leadingSlots = leading)
        assertEquals(setOf(-H, -H + 1, -H + 4, -H + 5), widget.coveredIndices())
        assertNull(widgetCandidate(layout, 5, -H, 2, 2))
        assertNull(widgetCandidate(layout, 5, -H + 2, 1, 1))
        assertEquals(WidgetPlacement(5, EMPTY_WIDGET, -1, 2, 1, 1, 1), widgetCandidate(layout, 5, -H + 6, 1, 1))
        assertEquals(widget.copy(column = 2), moveWidget(layout.copy(leadingSlots = List(H) { null }), 4, -H + 2).placement(4))
    }

    @Test fun `folders create dissolve and transfer on leading surface`() {
        val folder = FolderEntry("folder:00000000-0000-0000-0000-000000000008", "Pair", emptyList())
        val before = HomeLayout(listOf("b"), emptyList(), leadingSlots = List(H) { if (it == 0) "a" else null })
        val created = createFolder(before, folder, "a", "b", -H)
        assertEquals(folder.id, created.slotAt(-H)); assertNull(created.slotAt(0))
        val extracted = removeAppFromFolder(created, folder.id, "a", DropTarget.Home(3))
        assertEquals("b", extracted.slotAt(-H)); assertEquals("a", extracted.slotAt(3))
        assertTrue(extracted.folders.isEmpty())
    }
}
