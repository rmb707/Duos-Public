package com.mccal.folio

import org.junit.Assert.assertEquals
import org.junit.Test

/** The swipe-down choice on Home, and how the old Spotlight switch carries over. */
class SwipeDownHomeTest {
    private fun decode(json: String) = decodeLauncherState(json, legacyRaw = null)

    @Test fun `a new install swipes down for Spotlight`() {
        assertEquals("SPOTLIGHT", decode("{}").swipeDownHome)
    }

    @Test fun `the old switch carries over both ways`() {
        assertEquals("OFF", decode("""{"swipeDownSearch":false}""").swipeDownHome)
        assertEquals("SPOTLIGHT", decode("""{"swipeDownSearch":true}""").swipeDownHome)
    }

    @Test fun `a saved choice wins over the old switch`() {
        assertEquals("NOTIFICATIONS", decode("""{"swipeDownHome":"NOTIFICATIONS","swipeDownSearch":false}""").swipeDownHome)
    }
}
