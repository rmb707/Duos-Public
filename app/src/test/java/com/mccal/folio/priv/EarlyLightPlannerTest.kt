package com.mccal.folio.priv

import com.mccal.folio.priv.EarlyLightPlanner.Action
import com.mccal.folio.priv.EarlyLightPlanner.Armed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fold8Duo: the rules that decide when to overrule One UI's choice of display. A wrong request blacks out the screen
 * the owner is looking at, so every way in and every way out is pinned here. Timings and angles are from SM-F971U1
 * traces (docs/evidence/WP-07).
 */
class EarlyLightPlannerTest {
    private fun planner() = EarlyLightPlanner(swapDelayMs = 280, earlyCoverDeg = 40, watchdogMs = 4_000, stallReleaseMs = 700, requestToSwapMs = 125)

    private fun List<Action>.requests() = filterIsInstance<Action.Request>().map { it.state }
    private fun List<Action>.releases() = count { it is Action.Release }

    // ── early light ────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun anOrdinaryOpenIsLitEarlyAndLetGoWhenTheHingeGetsThere() {
        val p = planner()
        assertEquals(listOf(Action.TellOpening(405)), p.onStateCommitted(FoldState.TENT, 0))
        assertEquals(280, p.nextDeadline())
        assertTrue(p.onTick(279).isEmpty())                                   // the front screen's hand-over is still playing
        assertEquals(listOf(FoldState.OPENED), p.onTick(280).requests())
        assertEquals(Armed.EARLY_LIGHT, p.armed)
        p.onStateCommitted(FoldState.OPENED, 300)                             // our own request taking effect
        for ((t, a) in listOf(320L to 16, 560L to 30, 800L to 52, 1_000L to 78)) { p.onAngle(a, t); assertEquals(0, p.onTick(t).releases()) }
        assertEquals(1, p.onPolicyState(FoldState.OPENED, 1_150).releases())  // the hinge passes ~92°
        assertEquals(Armed.NONE, p.armed)
        assertEquals(EarlyLightPlanner.NEVER, p.nextDeadline())
    }

    /** Found live: CLOSED was committed during the delay, before anything was armed, so nothing let go of the request. */
    @Test fun aPeekThatClosesDuringTheDelayNeverGetsTheInnerPanel() {
        val p = planner()
        p.onStateCommitted(FoldState.TENT, 0)
        assertEquals(listOf(Action.TellClosed), p.onStateCommitted(FoldState.CLOSED, 150))
        assertTrue(p.onTick(280).isEmpty())
        assertTrue(p.onTick(5_000).isEmpty())
        assertEquals(Armed.NONE, p.armed)
    }

    @Test fun aPeekThatStopsPartWayGetsTheCoverBack() {
        val p = planner()
        p.onStateCommitted(FoldState.TENT, 0)
        p.onTick(280)
        p.onAngle(22, 330)
        assertEquals(1_030, p.nextDeadline())                                 // 700 ms after the hinge's last word
        assertEquals(0, p.onTick(1_029).releases())
        assertEquals(1, p.onTick(1_030).releases())
        assertTrue(p.lastRelease.contains("stalled"))
    }

    /**
     * Found live 2026-09-22 00:22:55: held at 81°, the inner screen was lit and dropped four times in five seconds.
     * Letting go puts One UI back in TENT, and that TENT was taken for a new opening (request 280 ms later, stall 700 ms
     * after that, and so on until the phone was shut).
     */
    @Test fun oneUisOwnTentAfterAStallIsNotANewOpening() {
        val p = planner()
        p.onStateCommitted(FoldState.TENT, 0)
        p.onTick(280)
        p.onStateCommitted(FoldState.OPENED, 300)
        p.onAngle(81, 400)
        assertEquals(1, p.onTick(1_100).releases())                           // stalled at 81°
        assertTrue(p.onStateCommitted(FoldState.TENT, 1_150).isEmpty())       // One UI taking TENT back: nothing to do
        for (t in 1_150L..6_000L step 50) assertTrue("asked again at $t ms", p.onTick(t).requests().isEmpty())
        assertEquals(Armed.NONE, p.armed)
        assertEquals(EarlyLightPlanner.NEVER, p.nextDeadline())
    }

    @Test fun smallMovesAfterAStallLeaveItToOneUi() {
        val p = planner()
        p.onStateCommitted(FoldState.TENT, 0); p.onTick(280); p.onAngle(81, 400); p.onTick(1_100)
        p.onStateCommitted(FoldState.TENT, 1_150)
        for ((t, a) in listOf(1_300L to 84, 1_600L to 79, 1_900L to 90)) {
            assertTrue(p.onAngle(a, t).none { it is Action.TellOpening }); assertTrue(p.onTick(t + 400).requests().isEmpty())
        }
    }

    @Test fun openingOnClearlyPastAStallLightsTheInnerScreenAgain() {
        val p = planner()
        p.onStateCommitted(FoldState.TENT, 0); p.onTick(280); p.onAngle(30, 400)
        assertEquals(1, p.onTick(1_100).releases())                           // a peek at 30°
        p.onStateCommitted(FoldState.TENT, 1_150)
        assertTrue(p.onAngle(40, 1_300).none { it is Action.TellOpening })     // 10° on: not yet
        assertTrue(p.onAngle(47, 1_450).contains(Action.TellOpening(405)))     // 17° on: opening again
        assertEquals(listOf(FoldState.OPENED), p.onTick(1_730).requests())
        assertEquals(Armed.EARLY_LIGHT, p.armed)
    }

    @Test fun afterAStallAClosedPhoneStartsAfresh() {
        val p = planner()
        p.onStateCommitted(FoldState.TENT, 0); p.onTick(280); p.onAngle(30, 400); p.onTick(1_100)
        p.onStateCommitted(FoldState.TENT, 1_150)
        p.onStateCommitted(FoldState.CLOSED, 2_000)
        assertEquals(listOf(Action.TellOpening(405)), p.onStateCommitted(FoldState.TENT, 3_000))
        assertEquals(listOf(FoldState.OPENED), p.onTick(3_280).requests())
    }

    @Test fun theWatchdogsReleaseIsNotFollowedByAnotherRequestEither() {
        val p = planner()
        p.onStateCommitted(FoldState.TENT, 0)
        p.onTick(280)
        var t = 300L
        while (t < 4_279) { p.onAngle(if ((t / 100) % 2 == 0L) 40 else 52, t); p.onTick(t); t += 100 }
        assertEquals(1, p.onTick(4_280).releases())
        assertTrue(p.onStateCommitted(FoldState.TENT, 4_300).isEmpty())
        assertTrue(p.onTick(4_600).requests().isEmpty())
    }

    /** The HAL speaks per ~10° of travel: a slow open's words are ~360 ms apart. That is not a stall. */
    @Test fun aSlowOpenIsNotMistakenForAPeek() {
        val p = planner()
        p.onStateCommitted(FoldState.TENT, 0)
        p.onTick(280)
        var t = 300L
        for (a in listOf(18, 29, 39, 51, 62, 73, 83)) { p.onAngle(a, t); assertEquals("let go at $a°", 0, p.onTick(t + 359).releases()); t += 360 }
    }

    @Test fun pastEightyFiveDegreesThereIsNothingLeftToStall() {
        val p = planner()
        p.onStateCommitted(FoldState.TENT, 0)
        p.onTick(280)
        p.onAngle(88, 900)
        assertEquals(0, p.onTick(900 + 3_000).releases())                     // One UI's own OPENED is due any moment
    }

    @Test fun nothingOutlivesTheWatchdog() {
        val p = planner()
        p.onStateCommitted(FoldState.TENT, 0)
        p.onTick(280)
        var t = 300L
        while (t < 4_279) { p.onAngle(if ((t / 100) % 2 == 0L) 40 else 52, t); assertEquals(0, p.onTick(t).releases()); t += 100 }   // a hand wobbling forever
        assertEquals(1, p.onTick(4_280).releases())
        assertEquals("watchdog", p.lastRelease)
    }

    @Test fun aSecondTentWhileArmedAsksForNothingNew() {
        val p = planner()
        p.onStateCommitted(FoldState.TENT, 0)
        p.onTick(280)
        assertTrue(p.onStateCommitted(FoldState.TENT, 400).isEmpty())
    }

    @Test fun withEarlyLightOffTheAppIsStillToldTheOpeningBegan() {
        val p = EarlyLightPlanner(earlyLight = false)
        assertEquals(listOf(Action.TellOpening(0)), p.onStateCommitted(FoldState.TENT, 0))
        assertTrue(p.onTick(10_000).isEmpty())
        assertEquals(EarlyLightPlanner.NEVER, p.nextDeadline())
    }

    // ── early cover ────────────────────────────────────────────────────────────────────────────────────────────

    private fun open(p: EarlyLightPlanner) { p.onStateCommitted(FoldState.OPENED, 0) }

    /** The exact sequence dry-run against the shell version: fold, re-open, fold again, shut. */
    @Test fun foldingPastTheThresholdLightsTheFrontScreenAndAReopenHandsItBack() {
        val p = planner(); open(p)
        var t = 1_000L
        for (a in listOf(164, 95, 56, 45)) { assertTrue(p.onAngle(a, t).requests().isEmpty()); t += 100 }
        assertEquals(listOf(FoldState.CLOSED), p.onAngle(33, t).requests())
        assertEquals(Armed.EARLY_COVER, p.armed)
        p.onStateCommitted(FoldState.CLOSED, t + 20)                          // our request taking effect
        assertEquals(0, p.onAngle(20, t + 100).releases())
        assertEquals(0, p.onAngle(31, t + 200).releases())                    // a wobble is not a re-open
        assertEquals(1, p.onAngle(47, t + 300).releases())                    // 12° past where it fired: hand the inner screen back
        p.onStateCommitted(FoldState.OPENED, t + 320)
        assertEquals(listOf(FoldState.CLOSED), p.onAngle(36, t + 400).requests())
        assertEquals(1, p.onPolicyState(FoldState.CLOSED, t + 700).releases())
        assertEquals(Armed.NONE, p.armed)
    }

    @Test fun openingThroughTheSameAnglesNeverAsksForTheCover() {
        val p = planner(); open(p)
        for ((i, a) in listOf(9, 18, 27, 38).withIndex()) assertTrue(p.onAngle(a, 100L * i).requests().isEmpty())
    }

    @Test fun earlyCoverOnlyEverInterruptsTheOpenScreen() {
        val p = planner()
        p.onStateCommitted(FoldState.TENT, 0)                                 // on the cover, or mid early-light
        p.onAngle(60, 10)
        assertTrue(p.onAngle(35, 110).requests().isEmpty())
    }

    @Test fun aPhoneThatIsAlreadyNearlyShutIsLeftToOneUi() {
        val p = planner(); open(p)
        p.onAngle(30, 0)
        assertTrue(p.onAngle(6, 40).requests().isEmpty())                     // a snap-shut: the first word under 40° is already under 8°
    }

    @Test fun earlyCoverCanBeSwitchedOff() {
        val p = EarlyLightPlanner(earlyCoverDeg = 0); open(p)
        p.onAngle(60, 0)
        assertTrue(p.onAngle(30, 100).requests().isEmpty())
    }

    @Test fun earlyCoverIsAlsoUnderTheWatchdog() {
        val p = planner(); open(p)
        p.onAngle(60, 0); p.onAngle(30, 100)
        assertEquals(4_100, p.nextDeadline())
        assertEquals(1, p.onTick(4_100).releases())
    }

    // ── always ─────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun everyAngleIsPassedOn() {
        val p = planner()
        assertEquals(Action.TellAngle(47), p.onAngle(47, 0).first())
    }

    @Test fun shuttingDownNeverLeavesARequestBehind() {
        val p = planner()
        p.onStateCommitted(FoldState.TENT, 0)
        assertTrue(p.onShutdown().isEmpty())                                  // pending, not yet armed: nothing to undo…
        assertTrue(p.onTick(280).isEmpty())                                   // …and it must not fire afterwards
        val q = planner(); q.onStateCommitted(FoldState.TENT, 0); q.onTick(280)
        assertEquals(1, q.onShutdown().releases())
    }

    // ── the log lines themselves, verbatim from the phone ──────────────────────────────────────────────────────

    @Test fun parsesTheLinesTheSystemReallyWrites() {
        assertEquals(FoldLogEvent.StateCommitted(1), FoldLogParser.parse(
            "Committing state: DeviceState{identifier=1, name='TENT', app_accessible=true, cancel_when_requester_not_on_top=false}"))
        assertEquals(FoldLogEvent.PolicyState(3), FoldLogParser.parse(
            "notifyDeviceStateChangedIfNeeded: newState=3, lastState=3, caller=com.android.server.policy.FlexibleDeviceStatePolicy.adjustState:28"))
        assertEquals(FoldLogEvent.Angle(47), FoldLogParser.parse(
            "handle_sns_client_event:197, [0]lid_angle_fusion ts=64322371695694 ns value [1/ 47/2] [ 47/2] [ -1/-1] [    0/    0/    0]"))
        assertEquals(FoldLogEvent.Angle(168), FoldLogParser.parse(
            "handle_sns_client_event:197, [0]lid_angle_fusion ts=64326411503506 ns value [3/168/2] [168/2] [ -1/-1]"))
    }

    @Test fun ignoresTheLinesThatOnlyLookSimilar() {
        assertNull(FoldLogParser.parse("handle_sns_client_event:199, [0]lid_angle_fusion book_mode(0 -> 1)"))
        assertNull(FoldLogParser.parse("handle_sns_client_event:223, [0]lid_angle_fusion submit_sensors_hal_event 1/ 11/3  11/3"))
        assertNull(FoldLogParser.parse("notifyDeviceStateChangedIfNeeded: newState=-1, lastState=3, caller=x"))   // "no state matches"
        assertNull(FoldLogParser.parse("handle_sns_client_event:50, hinge_angle ts=64323016454288 ns value   0/ 17/0"))
        assertNull(FoldLogParser.parse(""))
    }
}
