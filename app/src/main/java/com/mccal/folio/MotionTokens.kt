package com.mccal.folio

import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import kotlin.math.PI

/**
 * Fold8Duo (WP-56): the one place motion is decided, as SPEC §3.2 asks.
 *
 * Two forms. The morph and the fold animate by hand in iOS's two numbers — a spring's *response* (seconds for one
 * undamped swing) and *damping* — because that is how Apple's motion is described and matched by eye; those are the
 * constants at the top. Everything in Compose animates with [SpringSpec]s; those come from the named families below,
 * and every one of them goes through [MotionSpeed], so Settings › Gestures › Animation Speed moves the whole launcher
 * together (before WP-56, 32 springs were written straight into their call sites and never heard about that setting).
 *
 * The families keep the numbers the owner has been living with, grouped where sites were within a hair of each other,
 * so nothing accepted changes feel; the taste pass comes after, with the owner watching.
 */
internal object MotionTokens {
    // ---------------------------------------------------------------- iOS-form springs (response s, damping)

    /** icon → full screen (SPEC open.spring). */
    const val OPEN_RESPONSE_S = .40f
    const val OPEN_DAMPING = .85f
    /** full → icon, a ~3 % settle bounce (SPEC close.spring). */
    const val CLOSE_RESPONSE_S = .48f
    const val CLOSE_DAMPING = .78f
    /** The fold settling at flat (SPEC fold.settleSpring). */
    const val SETTLE_RESPONSE_S = .35f
    const val SETTLE_DAMPING = .90f
    /** A morph's hard cap, then snap (SPEC morph.maxMs). */
    const val MORPH_MAX_MS = 600L
    /** The icon's picture is gone this far up (SPEC morph.iconFadeEnd). */
    const val ICON_FADE_END = .25f
    /** An icon's corner radius as a share of its side (iOS). */
    const val ICON_CORNER_SHARE = .225f
    /** Overlay card → live app (SPEC handoff.crossfadeMs). */
    const val HANDOFF_MS = 120L
    /** A cold start may take this long to draw; then hand over regardless (SPEC coldStart.maxHoldMs). */
    const val COLD_START_MAX_MS = 3_000L

    /** Compose's stiffness for an iOS response: (2π / response)². */
    fun stiffness(responseSeconds: Float): Float = ((2 * PI / responseSeconds) * (2 * PI / responseSeconds)).toFloat()

    // ---------------------------------------------------------------- Compose families (damping ratio, stiffness)

    /** Panels, sheets, cards and menus appearing: a little life, no wobble. */
    val Appear = .78f to Spring.StiffnessMediumLow
    /** The islands: bouncier, as the Dynamic Island. */
    val Bouncy = .72f to 420f
    /** Things settling into place — placement, hinge avoidance, rows shifting (FolioMotion.Settle). */
    val Place = FolioMotion.Settle
    /** Pressing an icon, a row, a switch: fast, a touch of give. */
    val Press = .6f to Spring.StiffnessMedium
    /** The dock magnifying under a finger. */
    val Magnify = .75f to Spring.StiffnessMedium
    /** A segmented control's thumb: fast, hardly a bounce. */
    val Thumb = .85f to Spring.StiffnessMedium
    /** Things popping out: a stack fanning, a card growing. */
    val Pop = .7f to 520f
    /** Quick responses to a touch, and firm dismissals (FolioMotion's). */
    val Quick = FolioMotion.Quick
    val Firm = FolioMotion.Firm
    /** A value following a finger or a level: critically damped, quick. */
    val Follow = 1f to Spring.StiffnessMedium

    fun <T> of(pair: Pair<Float, Float>): SpringSpec<T> = MotionSpeed.spring(pair.first, pair.second)
    fun <T> appear(): SpringSpec<T> = of(Appear)
    fun <T> bouncy(): SpringSpec<T> = of(Bouncy)
    fun <T> place(): SpringSpec<T> = of(Place)
    fun <T> press(): SpringSpec<T> = of(Press)
    fun <T> magnify(): SpringSpec<T> = of(Magnify)
    fun <T> thumb(): SpringSpec<T> = of(Thumb)
    fun <T> pop(): SpringSpec<T> = of(Pop)
    fun <T> quick(): SpringSpec<T> = of(Quick)
    fun <T> firm(): SpringSpec<T> = of(Firm)
    fun <T> follow(): SpringSpec<T> = of(Follow)
    /** A sheet's entrance at its own stiffness, no bounce unless asked, through Animation Speed. */
    fun <T> firmAt(stiffness: Float, dampingRatio: Float = 1f): SpringSpec<T> = MotionSpeed.spring(dampingRatio, stiffness)

    // ---------------------------------------------------------------- haptics (SPEC §3.3)

    /**
     * One limiter for every haptic: never two within 80 ms. Played through a View, which already honours Android's
     * touch-feedback setting; Folio's own Haptics switch is the caller's to check (it is what `tick` gates on today).
     */
    object Haptics {
        private const val GAP_MS = 80L
        @Volatile private var lastAt = 0L

        private fun play(view: View, constant: Int): Boolean {
            val now = SystemClock.uptimeMillis()
            if (now - lastAt < GAP_MS) return false
            lastAt = now
            return view.performHapticFeedback(constant)
        }

        private val tick get() = if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK

        /** Reaching flat: a light tick. */
        fun flat(view: View) = play(view, tick)
        /** Halfway through a fold: the same light tick (the one the fold has had since WP-07). */
        fun halfway(view: View) = play(view, tick)
        /** Fully closed: a thud. */
        fun closed(view: View) = play(view, HapticFeedbackConstants.CONFIRM)
        /** A move that was refused. */
        fun rejected(view: View) = play(view, HapticFeedbackConstants.REJECT)
    }
}
