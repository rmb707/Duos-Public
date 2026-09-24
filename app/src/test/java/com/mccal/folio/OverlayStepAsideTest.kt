package com.mccal.folio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the island in every app steps aside (Settings › Dynamic Island › In Every App). */
class OverlayStepAsideTest {
    @Test fun `a full-screen film hides the island by default`() {
        assertTrue(overlayStepsAside(hideFullScreen = true, hideLandscape = false, fullScreen = true, landscape = true))
        assertFalse(overlayStepsAside(hideFullScreen = true, hideLandscape = false, fullScreen = false, landscape = true))
    }

    @Test fun `landscape only hides it when you ask`() {
        assertFalse(overlayStepsAside(hideFullScreen = false, hideLandscape = false, fullScreen = false, landscape = true))
        assertTrue(overlayStepsAside(hideFullScreen = false, hideLandscape = true, fullScreen = false, landscape = true))
    }

    @Test fun `turning both off keeps the island where it was`() {
        assertFalse(overlayStepsAside(hideFullScreen = false, hideLandscape = false, fullScreen = true, landscape = true))
    }

    @Test fun `a saved layout keeps full screen on and landscape off`() {
        val state = decodeLauncherState("{}", legacyRaw = null)
        assertTrue(state.islandHideFullScreen)
        assertFalse(state.islandHideLandscape)
    }
}
