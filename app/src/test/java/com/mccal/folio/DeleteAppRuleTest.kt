package com.mccal.folio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeleteAppRuleTest {
    private fun offer(isShortcut: Boolean = false, isFolio: Boolean = false, available: Boolean = true, installed: Boolean = true,
        system: Boolean = false, updatedSystem: Boolean = false) =
        DeleteAppRule.offer(isShortcut, isFolio, available, installed, system, updatedSystem)

    @Test fun `an app you installed can be deleted`() {
        assertTrue(offer())
    }

    @Test fun `a system app can't, unless it has updates to remove`() {
        assertFalse(offer(system = true))
        assertTrue(offer(system = true, updatedSystem = true))
    }

    @Test fun `a pinned shortcut keeps its own Delete Shortcut instead`() {
        assertFalse(offer(isShortcut = true))
    }

    @Test fun `Folio itself is never offered, like Settings on iPhone`() {
        assertFalse(offer(isFolio = true))
    }

    @Test fun `an app that's unavailable, or that Android can't describe, isn't offered`() {
        assertFalse(offer(available = false))
        assertFalse(offer(installed = false))
        // Unknown flags never count as "not a system app".
        assertFalse(offer(installed = false, system = false))
    }
}
