package com.mccal.folio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryIndexTest {
    @Test fun `apps file under their first letter, accents off, digits and symbols under hash`() {
        assertEquals("A", LibraryIndex.letterOf("Amazon"))
        assertEquals("E", LibraryIndex.letterOf("éBay"))
        assertEquals("Z", LibraryIndex.letterOf("  Zoom"))
        assertEquals("#", LibraryIndex.letterOf("1Password"))
        assertEquals("#", LibraryIndex.letterOf("#Hashtags"))
        assertEquals("#", LibraryIndex.letterOf(""))
        assertEquals("Я", LibraryIndex.letterOf("яндекс"))
    }

    @Test fun `sections run A to Z, then other alphabets, then hash last, keeping each app's order`() {
        val apps = listOf("1Password", "Amazon", "Android Auto", "Яндекс", "Bank", "éBay", "Email", "7-Eleven")
        val sections = LibraryIndex.sections(apps) { it }
        assertEquals(listOf("A", "B", "E", "Я", "#"), sections.map { it.first })
        assertEquals(listOf("Amazon", "Android Auto"), sections[0].second)
        assertEquals(listOf("éBay", "Email"), sections[2].second)
        assertEquals(listOf("1Password", "7-Eleven"), sections.last().second)
    }

    @Test fun `the strip is A to Z and hash, with other alphabets only when they have apps`() {
        val plain = LibraryIndex.strip(setOf("A", "M", "#"))
        assertEquals(27, plain.size)
        assertEquals("A", plain.first()); assertEquals("Z", plain[25]); assertEquals("#", plain.last())
        val mixed = LibraryIndex.strip(setOf("A", "Я", "#"))
        assertEquals(listOf("Z", "Я", "#"), mixed.takeLast(3))
    }

    @Test fun `a letter with no apps goes to the next section down, or the last one above at the end`() {
        val present = setOf("B", "M", "#")
        val strip = LibraryIndex.strip(present)
        assertEquals("B", LibraryIndex.target("A", strip, present))
        assertEquals("M", LibraryIndex.target("C", strip, present))
        assertEquals("M", LibraryIndex.target("M", strip, present))
        assertEquals("#", LibraryIndex.target("Z", strip, present))
        assertEquals("M", LibraryIndex.target("Z", strip, setOf("B", "M")))
        assertNull(LibraryIndex.target("?", strip, present))
        assertNull(LibraryIndex.target("A", strip, emptySet()))
    }

    @Test fun `the finger reads the strip evenly and holds the ends when it runs past them`() {
        assertEquals(0, LibraryIndex.slotAt(-40f, 540f, 27))
        assertEquals(0, LibraryIndex.slotAt(0f, 540f, 27))
        assertEquals(1, LibraryIndex.slotAt(21f, 540f, 27))
        assertEquals(13, LibraryIndex.slotAt(270f, 540f, 27))
        assertEquals(26, LibraryIndex.slotAt(539f, 540f, 27))
        assertEquals(26, LibraryIndex.slotAt(900f, 540f, 27))
        assertEquals(0, LibraryIndex.slotAt(10f, 0f, 27))
    }

    @Test fun `a short strip keeps first and last with dots between evenly spaced letters`() {
        val strip = LibraryIndex.strip(setOf("A"))
        assertEquals(strip, LibraryIndex.shown(strip, 27))
        assertEquals(strip, LibraryIndex.shown(strip, 40))
        val short = LibraryIndex.shown(strip, 15)
        assertTrue(short.size <= 15)
        assertEquals("A", short.first()); assertEquals("#", short.last())
        assertTrue(short.withIndex().all { (i, entry) -> (entry == null) == (i % 2 == 1) })
        assertEquals(listOf("A"), LibraryIndex.shown(strip, 1))
        assertEquals(emptyList<String?>(), LibraryIndex.shown(strip, 0))
    }

    @Test fun `one app per row on the cover, three across the unfolded screen`() {
        assertEquals(1, LibraryIndex.columns(395f))
        assertEquals(1, LibraryIndex.columns(480f))
        assertEquals(2, LibraryIndex.columns(560f))
        assertEquals(3, LibraryIndex.columns(800f))
        assertEquals(4, LibraryIndex.columns(2000f))
        assertEquals(1, LibraryIndex.columns(0f))
    }

    @Test fun `headings are found counting back from the end of the list`() {
        val sizes = listOf("A" to 3, "B" to 1, "#" to 2)
        // One column: A heading + 3 rows, B heading + 1 row, # heading + 2 rows = 9 items, after 2 leading ones.
        assertEquals(mapOf("A" to 2, "B" to 6, "#" to 8), LibraryIndex.headingIndices(sizes, 1, total = 11))
        // Three columns: A heading + 1 row, B heading + 1 row, # heading + 1 row = 6 items, after 1 leading one.
        assertEquals(mapOf("A" to 1, "B" to 3, "#" to 5), LibraryIndex.headingIndices(sizes, 3, total = 7))
        assertEquals(1, LibraryIndex.itemsIn(0, 3))
        assertEquals(3, LibraryIndex.itemsIn(4, 3))
        assertEquals(5, LibraryIndex.itemsIn(4, 0))
    }
}
