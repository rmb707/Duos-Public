package com.mccal.folio.priv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HoldOffPolicyTest {
    @Test fun anOrdinaryAppIsLeftAlone() {
        assertNull(HoldOffPolicy.reason("com.google.android.youtube", 0))
        assertNull(HoldOffPolicy.reason(null, 0))
        assertNull(HoldOffPolicy.reason("com.mccal.folio.dev", 0))
    }

    @Test fun camerasHoldOff() {
        assertNotNull(HoldOffPolicy.reason("com.sec.android.app.camera", 0))
        assertNotNull(HoldOffPolicy.reason("com.google.android.GoogleCamera", 0))
        assertNotNull(HoldOffPolicy.reason("org.codeaurora.snapcam.camera", 0))
        assertNotNull(HoldOffPolicy.reason("net.sourceforge.opencamera.camera.pro", 0))
    }

    @Test fun aPhoneCallHoldsOffButAVideoCallDoesNot() {
        assertNotNull(HoldOffPolicy.reason("com.samsung.android.incallui", HoldOffPolicy.MODE_IN_CALL))
        assertNull(HoldOffPolicy.reason("com.google.android.apps.tachyon", 3))      // MODE_IN_COMMUNICATION
        assertNull(HoldOffPolicy.reason("com.whatsapp", 1))                         // MODE_RINGTONE
    }

    // ---- the planner's side of it

    private fun tent(planner: EarlyLightPlanner, holdOff: String?) = planner.onStateCommitted(FoldState.TENT, 1_000, holdOff)

    @Test fun aHeldOffOpeningStillTellsTheAppButNeverAsksForTheInnerScreen() {
        val planner = EarlyLightPlanner()
        planner.onStateCommitted(FoldState.CLOSED, 0)
        assertEquals(listOf(EarlyLightPlanner.Action.TellOpening(0)), tent(planner, "a camera in front"))
        assertEquals(EarlyLightPlanner.NEVER, planner.nextDeadline())
        for (t in 1_000L..3_000L step 50) assertTrue(planner.onTick(t).isEmpty())
        assertEquals(EarlyLightPlanner.Armed.NONE, planner.armed)
        assertEquals("a camera in front", planner.lastHoldOff)
    }

    @Test fun theNextOpeningIsJudgedAfresh() {
        val planner = EarlyLightPlanner()
        planner.onStateCommitted(FoldState.CLOSED, 0)
        tent(planner, "a phone call")
        planner.onStateCommitted(FoldState.CLOSED, 2_000)
        val told = planner.onStateCommitted(FoldState.TENT, 3_000, null)
        assertEquals(listOf(EarlyLightPlanner.Action.TellOpening(405)), told)
        assertEquals(listOf(EarlyLightPlanner.Action.Request(FoldState.OPENED)), planner.onTick(3_280))
    }

    @Test fun holdingOffNeverTouchesARequestAlreadyInForce() {
        val planner = EarlyLightPlanner()
        planner.onStateCommitted(FoldState.CLOSED, 0)
        planner.onStateCommitted(FoldState.TENT, 1_000)
        planner.onTick(1_280)                                                       // armed: OPENED requested
        assertTrue(planner.onStateCommitted(FoldState.TENT, 1_300, "a camera in front").isEmpty())
        assertEquals(EarlyLightPlanner.Armed.EARLY_LIGHT, planner.armed)            // only its own rules let go of it
    }
}
