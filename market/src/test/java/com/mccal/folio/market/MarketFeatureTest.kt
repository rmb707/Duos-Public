package com.mccal.folio.market

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Who sees the Market before it ships: supporters. Nobody else, however they got their build. */
class MarketFeatureTest {
    @Test fun `Folio Dev keeps Settings behind its icon in Fold8Duo`() {
        // Upstream shows Folio Dev the Market; in this fork Folio Dev is the owner's daily Home, not a store test bed.
        assertFalse(MarketFeature.isEnabled("com.mccal.folio.dev"))
        assertTrue(MarketFeature.isEnabled("com.mccal.folio.dev", hasEarlyCode = true))
    }

    @Test fun `the signed release hides it until 0_7_0 ships`() {
        assertFalse(MarketFeature.RELEASED)
        assertFalse(MarketFeature.isEnabled("com.mccal.folio"))
    }

    @Test fun `a supporter's code opens it, and nothing else does`() {
        assertTrue(MarketFeature.isEnabled("com.mccal.folio", hasEarlyCode = true))
        // Being on the beta channel is not a code. It used to be a way in, which made the store something anyone
        // could switch on rather than something a supporter gets (McCal, 2026-09-19).
        assertFalse(MarketFeature.isEnabled("com.mccal.folio", hasEarlyCode = false))
    }
}
