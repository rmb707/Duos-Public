package com.mccal.folio

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentlyAddedTest {
    private val now = 1_790_000_000_000L
    private val hour = 60L * 60 * 1000
    private val day = 24 * hour

    private fun pick(installed: Map<String, Long?>, max: Int = RecentlyAdded.MAX) =
        RecentlyAdded.pick(installed.keys.toList(), now, { installed[it] }, max = max)

    @Test fun `apps installed this week come newest first`() {
        val installed = mapOf("Maps" to now - 3 * day, "Bank" to now - 2 * hour, "Chess" to now - 6 * day, "Notes" to now - 10 * 60_000L)
        assertEquals(listOf("Notes", "Bank", "Maps", "Chess"), pick(installed))
    }

    @Test fun `a week ago is out, just inside a week is in`() {
        val installed = mapOf("Old" to now - RecentlyAdded.WINDOW_MS, "Nearly" to now - RecentlyAdded.WINDOW_MS + 1, "Ancient" to now - 400 * day)
        assertEquals(listOf("Nearly"), pick(installed))
    }

    @Test fun `at most eight, the newest ones`() {
        val installed = (1..12).associate { "App$it" to now - it * hour }
        assertEquals((1..8).map { "App$it" }, pick(installed))
        assertEquals(listOf("App1", "App2"), pick(installed, max = 2))
        assertEquals(emptyList<String>(), pick(installed, max = 0))
    }

    @Test fun `no install time, or a clock set back by days, leaves an app out`() {
        val installed = mapOf("Unknown" to null, "Zero" to 0L, "Future" to now + 3 * day, "Skewed" to now + 30 * 60_000L, "Fine" to now - hour)
        // A few minutes "ahead" is a clock nudged back by network time: still the newest.
        assertEquals(listOf("Skewed", "Fine"), pick(installed))
    }

    @Test fun `apps installed at the same moment keep the list's order`() {
        // Two launcher icons from one install, or a restore that installed a batch at once: A–Z order is kept.
        val same = now - day
        val installed = linkedMapOf("Alpha" to same, "Beta" to same, "Gamma" to now - hour, "Delta" to same)
        assertEquals(listOf("Gamma", "Alpha", "Beta", "Delta"), pick(installed))
    }

    @Test fun `nothing recent, nothing shown`() {
        assertEquals(emptyList<String>(), pick(emptyMap()))
        assertEquals(emptyList<String>(), pick(mapOf("Old" to now - 30 * day)))
    }
}
