package com.mccal.folio.priv

/** Device-state identifiers on a Galaxy Z Fold8 (`cmd device_state print-states`). */
internal object FoldState {
    const val CLOSED = 0
    const val TENT = 1
    const val HALF_OPENED = 2
    const val OPENED = 3
}

/**
 * Fold8Duo: when to ask the system for a different display, decided from what the system itself logs.
 *
 * Measured on an SM-F971U1 (docs/decisions.md): awake on the cover, One UI commits TENT at 3–13° but holds it until
 * the hinge reaches ~92° before switching to the inner panel, so content first appears at a median 122°; opening from
 * sleep the very same panel shows content at 25°. The wait is policy, not hardware, and the shell user is allowed to
 * overrule it (it holds CONTROL_DEVICE_STATE).
 *
 *  - **Early light:** on the TENT commit, after [swapDelayMs] (the front screen's hand-over needs that long, and the
 *    inner screen cannot be seen yet anyway), ask for OPENED — if the phone is still opening.
 *  - **Early cover:** while folding, once the hinge drops past [earlyCoverDeg], ask for CLOSED, so the front screen
 *    lights while there is still travel for its come-back to follow (One UI alone waits for ~5°).
 *
 * Every request is let go: when the hinge physically gets there, when the hand goes the other way, when an opening
 * stalls part-way (a peek, or a phone being stood up as a tent), or after [watchdogMs] whatever happens.
 *
 * Pure: no Android, no clock of its own, no threads. Everything that happened arrives as a call with a timestamp and
 * everything to do comes back as [Action]s, so the rules that can black out a screen are unit-tested.
 */
internal class EarlyLightPlanner(
    private val earlyLight: Boolean = true,
    private val swapDelayMs: Long = 280,
    private val earlyCoverDeg: Int = 40,
    private val watchdogMs: Long = 4_000,
    private val stallReleaseMs: Long = 700,
    /** Measured request → panel swap, so the app can be told when the swap will land. */
    private val requestToSwapMs: Int = 125,
) {
    sealed interface Action {
        /** Ask the system for [state]. */
        data class Request(val state: Int) : Action
        /** Withdraw whatever was asked for. */
        data object Release : Action
        /** The phone has started opening; the panels swap in about [swapInMs] (0 = not by us, timing unknown). */
        data class TellOpening(val swapInMs: Int) : Action
        data class TellAngle(val degrees: Int) : Action
        data object TellClosed : Action
    }

    enum class Armed { NONE, EARLY_LIGHT, EARLY_COVER }

    var armed = Armed.NONE; private set
    /** Why the last request was let go; for logs and tests. */
    var lastRelease = ""; private set

    private var committed = -1
    private var pendingOpenAt = NEVER
    private var armedAt = 0L
    private var lastAngleAt = 0L
    private var previousAngle = -1
    private var coverAtAngle = 0
    /**
     * Where early light was let go because the opening stalled (or the watchdog fired), or -1. One UI commits TENT again
     * as soon as we let go, and that is not a new opening: re-arming on it lit and dropped the inner screen every second
     * for as long as the phone was held there (seen live 2026-09-22 at 81°). Cleared by a close, or by opening on past
     * this by [RESUME_DEG].
     */
    private var stalledAt = -1

    /** Why the last opening was left to One UI's own timing ("" = it was not); for logs and tests. */
    var lastHoldOff = ""; private set

    /**
     * The system committed a device state (its own decision, or one of our requests taking effect).
     *
     * [holdOff] names a reason to leave THIS opening alone (see [HoldOffPolicy]): the app is still told the phone is
     * opening, so the fold effect plays, but the inner screen is left to light when One UI decides.
     */
    fun onStateCommitted(state: Int, nowMs: Long, holdOff: String? = null): List<Action> {
        committed = state
        return when (state) {
            FoldState.TENT -> when {
                armed != Armed.NONE -> emptyList()
                stalledAt >= 0 -> emptyList()      // One UI taking TENT back after we let go: the same opening, not a new one
                !earlyLight -> listOf(Action.TellOpening(0))
                holdOff != null -> { lastHoldOff = holdOff; pendingOpenAt = NEVER; listOf(Action.TellOpening(0)) }
                else -> { pendingOpenAt = nowMs + swapDelayMs; listOf(Action.TellOpening((swapDelayMs + requestToSwapMs).toInt())) }
            }
            FoldState.CLOSED -> { pendingOpenAt = NEVER; stalledAt = -1; listOf(Action.TellClosed) }
            else -> emptyList()
        }
    }

    /** Where the hinge physically is, as the fold policy judges it — it keeps judging while an override is active. */
    fun onPolicyState(state: Int, nowMs: Long): List<Action> = when {
        state == FoldState.OPENED && armed == Armed.EARLY_LIGHT -> release("the hinge reached OPENED")
        state == FoldState.CLOSED && armed != Armed.NONE -> release("the phone is physically closed")
        else -> emptyList()
    }

    /** One word of the HAL's true hinge angle. */
    fun onAngle(degrees: Int, nowMs: Long): List<Action> {
        val actions = mutableListOf<Action>(Action.TellAngle(degrees))
        val folding = previousAngle > degrees
        when {
            armed == Armed.NONE && earlyCoverDeg > 0 && folding && degrees in NEARLY_SHUT_DEG..earlyCoverDeg &&
                committed == FoldState.OPENED -> {
                armed = Armed.EARLY_COVER; armedAt = nowMs; coverAtAngle = degrees
                actions += Action.Request(FoldState.CLOSED)
            }
            armed == Armed.EARLY_COVER && degrees >= coverAtAngle + REOPEN_DEG -> actions += release("re-opened to $degrees°")
            // After a stall, a hand that opens clearly further is a real opening again: the same path as a TENT commit.
            armed == Armed.NONE && stalledAt >= 0 && earlyLight && committed == FoldState.TENT &&
                degrees >= stalledAt + RESUME_DEG && degrees < STALL_BELOW_DEG -> {
                stalledAt = -1
                pendingOpenAt = nowMs + swapDelayMs
                actions += Action.TellOpening((swapDelayMs + requestToSwapMs).toInt())
            }
        }
        previousAngle = degrees
        lastAngleAt = nowMs
        return actions
    }

    /** Call at [nextDeadline], or more often. */
    fun onTick(nowMs: Long): List<Action> {
        if (pendingOpenAt != NEVER && nowMs >= pendingOpenAt) {
            pendingOpenAt = NEVER
            // Still opening? A peek that closed again during the delay must not get the inner panel: CLOSED was
            // committed before anything was armed, so nothing would let go of it until the watchdog.
            if (committed == FoldState.TENT && armed == Armed.NONE) {
                armed = Armed.EARLY_LIGHT; armedAt = nowMs; lastAngleAt = nowMs
                return listOf(Action.Request(FoldState.OPENED))
            }
        }
        if (armed == Armed.NONE) return emptyList()
        if (nowMs - armedAt >= watchdogMs) {
            if (armed == Armed.EARLY_LIGHT) stalledAt = previousAngle.coerceAtLeast(0)
            return release("watchdog")
        }
        // An opening that stops part-way — a peek, or the phone being stood up as a tent — gets the cover back.
        if (armed == Armed.EARLY_LIGHT && nowMs - lastAngleAt >= stallReleaseMs && previousAngle < STALL_BELOW_DEG) {
            stalledAt = previousAngle.coerceAtLeast(0)
            return release("opening stalled at $previousAngle°")
        }
        return emptyList()
    }

    /** When [onTick] next has something to decide, or [NEVER]. */
    fun nextDeadline(): Long {
        var next = pendingOpenAt
        if (armed != Armed.NONE) {
            next = earliest(next, armedAt + watchdogMs)
            if (armed == Armed.EARLY_LIGHT && previousAngle < STALL_BELOW_DEG) next = earliest(next, lastAngleAt + stallReleaseMs)
        }
        return next
    }

    /** The engine is going away: never leave a request behind. */
    fun onShutdown(): List<Action> { pendingOpenAt = NEVER; return if (armed == Armed.NONE) emptyList() else release("shutdown") }

    private fun release(why: String): List<Action> { armed = Armed.NONE; lastRelease = why; return listOf(Action.Release) }

    private fun earliest(a: Long, b: Long) = if (a == NEVER) b else minOf(a, b)

    companion object {
        const val NEVER = -1L
        /** Below this the phone is as good as shut; One UI is about to swap anyway. */
        private const val NEARLY_SHUT_DEG = 8
        /** Re-opening this far past where early cover fired hands the inner screen back. */
        private const val REOPEN_DEG = 12
        /** A stall only counts below this; past it the policy's own OPENED (~92°) is about to take over. */
        private const val STALL_BELOW_DEG = 85
        /** After a stall, opening on by this much is a new opening (the HAL speaks every ~10°, so one word isn't enough). */
        private const val RESUME_DEG = 15
    }
}

/** One thing the system logged that the planner cares about. */
internal sealed interface FoldLogEvent {
    data class StateCommitted(val state: Int) : FoldLogEvent
    data class PolicyState(val state: Int) : FoldLogEvent
    data class Angle(val degrees: Int) : FoldLogEvent
}

/**
 * The three kinds of system log line the planner runs on, as seen on an SM-F971U1 (message text only, `logcat -v raw`):
 *
 *     Committing state: DeviceState{identifier=1, name='TENT', app_accessible=true, …}
 *     notifyDeviceStateChangedIfNeeded: newState=3, lastState=3, caller=…
 *     handle_sns_client_event:197, [0]lid_angle_fusion ts=64322371695694 ns value [1/ 47/2] [ 47/2] …
 */
internal object FoldLogParser {
    /** Hand this to `logcat -e` so logcat drops everything else natively; the sensor HAL logs thousands of lines a second. */
    const val LOGCAT_REGEX = "Committing state|notifyDeviceStateChangedIfNeeded|lid_angle_fusion ts="
    val LOGCAT_TAGS = listOf("DeviceStateManagerService:D", "DeviceStatePolicy:D", "sensors-hal:I")

    private val committed = Regex("""Committing state: DeviceState\{identifier=(\d+),""")
    private val policy = Regex("""notifyDeviceStateChangedIfNeeded: newState=(-?\d+),""")
    private val angle = Regex("""lid_angle_fusion ts=\d+ ns value \[\s*\d+/\s*(-?\d+)/""")

    fun parse(line: String): FoldLogEvent? {
        angle.find(line)?.let { return FoldLogEvent.Angle(it.groupValues[1].toInt().coerceIn(0, 180)) }
        committed.find(line)?.let { return FoldLogEvent.StateCommitted(it.groupValues[1].toInt()) }
        policy.find(line)?.let { m -> return m.groupValues[1].toInt().takeIf { it >= 0 }?.let { FoldLogEvent.PolicyState(it) } }
        return null
    }
}
