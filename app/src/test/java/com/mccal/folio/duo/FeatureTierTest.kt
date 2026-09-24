package com.mccal.folio.duo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureTierTest {
    @Test fun liteNeverTakesAnElevatedRouteEvenWithTheGrantLive() {
        for (c in Capability.entries) {
            assertFalse(FeatureTiers.elevated(Tier.LITE, c, grantLive = true))
            assertFalse(FeatureTiers.elevated(Tier.LITE, c, grantLive = false))
        }
    }

    @Test fun fullTakesItsElevatedRouteOnlyWhileTheGrantIsLive() {
        for (c in Capability.entries) {
            assertTrue(FeatureTiers.elevated(Tier.FULL, c, grantLive = true))
            assertFalse(FeatureTiers.elevated(Tier.FULL, c, grantLive = false))
        }
    }

    @Test fun degradedListsWhatTheUserIsMissing() {
        assertEquals(Capability.entries.toList(), FeatureTiers.degraded(Tier.LITE, grantLive = true))
        assertEquals(Capability.entries.toList(), FeatureTiers.degraded(Tier.FULL, grantLive = false))  // grant dropped
        assertEquals(emptyList<Capability>(), FeatureTiers.degraded(Tier.FULL, grantLive = true))
    }
}
