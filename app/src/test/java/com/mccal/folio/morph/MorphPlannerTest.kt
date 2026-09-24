package com.mccal.folio.morph

import com.mccal.folio.morph.MorphPlanner.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MorphPlannerTest {
    private val cells = 24
    private fun slots(vararg placed: Pair<Int, String>): List<String?> =
        MutableList<String?>(cells * 3) { null }.apply { placed.forEach { (index, id) -> this[index] = id } }

    @Test fun anAppInTheDockAlwaysLandsInTheDock() {
        assertEquals("mail", MorphPlanner.tileFor("mail", listOf("phone", "mail", null), slots(), emptyMap(), page = 2, cellsPerPage = cells))
    }

    @Test fun anAppOnTheShownPageLandsOnItsIcon() {
        val home = slots(3 to "maps", cells + 5 to "notes")
        assertEquals("maps", MorphPlanner.tileFor("maps", emptyList(), home, emptyMap(), 0, cells))
        assertEquals("notes", MorphPlanner.tileFor("notes", emptyList(), home, emptyMap(), 1, cells))
    }

    @Test fun anAppOnAnotherPageHasNowhereToLand() {
        assertNull(MorphPlanner.tileFor("notes", emptyList(), slots(cells + 5 to "notes"), emptyMap(), 0, cells))
    }

    @Test fun anAppInAFolderLandsOnTheFolderWhenTheFolderIsShown() {
        val home = slots(7 to "folder:work")
        val folders = mapOf("folder:work" to listOf("slack", "zoom"))
        assertEquals("folder:work", MorphPlanner.tileFor("slack", emptyList(), home, folders, 0, cells))
        assertNull(MorphPlanner.tileFor("slack", emptyList(), home, folders, 1, cells))
        assertEquals("folder:work", MorphPlanner.tileFor("zoom", listOf("folder:work"), slots(), folders, 2, cells))
    }

    @Test fun anAppOnlyInTheLibraryHasNowhereToLand() {
        assertNull(MorphPlanner.tileFor("calculator", listOf("phone"), slots(0 to "maps"), emptyMap(), 0, cells))
    }

    @Test fun homeReturnsToTheRestingHomePageElseTheLastOne() {
        assertEquals(1, MorphPlanner.pageAfterReturn(settledPage = 1, lastHomePage = 0, homePages = 3))
        assertEquals(2, MorphPlanner.pageAfterReturn(settledPage = -1, lastHomePage = 2, homePages = 3)) // Today view
        assertEquals(0, MorphPlanner.pageAfterReturn(settledPage = 3, lastHomePage = 0, homePages = 3)) // App Library
        assertEquals(1, MorphPlanner.pageAfterReturn(settledPage = 5, lastHomePage = 7, homePages = 2)) // pages removed since
        assertEquals(0, MorphPlanner.pageAfterReturn(settledPage = -1, lastHomePage = 0, homePages = 0))
    }

    @Test fun onlyWholeSquareIconsInsideTheWindowAreTrusted() {
        assertTrue(MorphPlanner.wholeIcon(Box(100, 200, 260, 360), 1080, 2520, 60, 480))
        assertFalse("a sliver left by a page sliding away", MorphPlanner.wholeIcon(Box(0, 200, 12, 360), 1080, 2520, 60, 480))
        assertFalse("clipped to half", MorphPlanner.wholeIcon(Box(0, 200, 80, 360), 1080, 2520, 60, 480))
        assertFalse("outside the window", MorphPlanner.wholeIcon(Box(1100, 200, 1260, 360), 1080, 2520, 60, 480))
        assertFalse("empty", MorphPlanner.wholeIcon(Box(10, 10, 10, 10), 1080, 2520, 60, 480))
        assertFalse("too large to be an icon", MorphPlanner.wholeIcon(Box(0, 0, 600, 600), 1080, 2520, 60, 480))
        assertFalse("no window yet", MorphPlanner.wholeIcon(Box(100, 200, 260, 360), 0, 0, 60, 480))
    }

    @Test fun theOpeningAppStartsInsideTheIconWithTheWindowsShape() {
        // Cover screen, 1080 x 2520: a 160 px icon starts a 69 x 160 window centred on it.
        val cover = MorphPlanner.evenScaleStart(Box(100, 200, 260, 360), 1080, 2520)
        assertEquals(160, cover.height)
        assertEquals(69, cover.width)
        assertEquals(180f, (cover.left + cover.right) / 2f, 1f)
        assertEquals(280f, (cover.top + cover.bottom) / 2f, 1f)
        // Inner screen, wider than tall: the width fits the icon and the height follows.
        val inner = MorphPlanner.evenScaleStart(Box(0, 0, 200, 200), 2184, 1968)
        assertEquals(200, inner.width)
        assertEquals(180, inner.height)
        assertEquals(2184f / 1968f, inner.width.toFloat() / inner.height, .02f)
    }

    @Test fun aDegenerateStartIsLeftAlone() {
        val icon = Box(5, 5, 5, 5)
        assertEquals(icon, MorphPlanner.evenScaleStart(icon, 1080, 2520))
        assertEquals(Box(0, 0, 100, 100), MorphPlanner.evenScaleStart(Box(0, 0, 100, 100), 0, 2520))
    }

    @Test fun distanceIsTheLargestEdgeMove() {
        assertEquals(0, Box(1, 2, 3, 4).distanceTo(Box(1, 2, 3, 4)))
        assertEquals(7, Box(0, 0, 10, 10).distanceTo(Box(2, -7, 12, 9)))
        assertEquals(Box(11, 22, 13, 24), Box(1, 2, 3, 4).offset(10, 20))
    }
}
