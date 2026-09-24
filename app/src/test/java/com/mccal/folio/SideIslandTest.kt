package com.mccal.folio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SideIslandTest {
    @Test fun `upright island only for a camera on a side edge`() {
        // Cover portrait: camera top-center.
        assertNull(cameraSideEdge(600, 0, 648, 104, 1248, 1972))
        // Cover turned sideways: camera on the right or left edge, vertically centered.
        assertEquals(1, cameraSideEdge(1868, 600, 1972, 648, 1972, 1248))
        assertEquals(-1, cameraSideEdge(0, 600, 104, 648, 1972, 1248))
        // Inner portrait: hidden camera near the right edge, low down.
        assertEquals(1, cameraSideEdge(1752, 1823, 1830, 1901, 1848, 2448))
        assertNull(cameraSideEdge(0, 0, 10, 10, 0, 0))
    }
}
