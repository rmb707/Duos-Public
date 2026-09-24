package com.mccal.folio.duo

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Fold8Duo: the iPhone Duo fold look as numbers. No android.* here, so it runs as a plain JVM test, and the two
 * renderers (the pane shader on Home, the pane painter over apps — DuoFold.kt) read their constants from it.
 *
 * Calibrated against Apple's own film (the owner's GIFs, 2026-09-22; `docs/status/WP-51.md`):
 *
 * - The picture lies on a plane fixed in space: where the screen is when the phone is flat. The half that does not move
 *   shows it as it is; the half that moves is a pane of glass hinged on that plane, tilted by [tilt] from it, and at
 *   every pixel shows the point of the plane behind it along the ray from the eye ([source]). From the front the picture
 *   never moves — in the film "9:41" stays put while the half folds under it — and the page is revealed from the hinge
 *   outward as the half flattens.
 * - The glass blurs and dims the picture by how far the half is from flat, more toward its free edge than at the hinge.
 *   Measured on the film's guitar: the white body keeps 93 % of its brightness at ~5°, 87 % at ~15°, 71 % at ~35°,
 *   63 % at ~45°, 53 % from ~60° on ([darkMid]). The blur is large — about a tenth of the half's width at full strength,
 *   reached by about 60° ([blurRadius]) — and round, not streaked.
 * - A half turned past the vertical is never black in the film: at ~150° it is a dim, very soft picture ([steepDark]).
 */
internal object DuoPaneModel {
    /** Eye to screen, as the recreations assume (SPEC `fold.viewDistanceMm`). */
    const val VIEW_DISTANCE_MM = 320f
    /** The projection is not pushed past this tilt: beyond it the pane would show less than 1/2.5 of its page, stretched. */
    const val PROJECTION_MAX_TILT_DEG = 70f
    /** What [PROJECTION_MAX_TILT_DEG] amounts to on a 74 mm half: the page is never stretched more than this. */
    const val MAX_STRETCH = 2.5f
    /** A pane this far from flat shows its own picture in full; below it the sharp screen shows through. */
    const val PANE_FADE_IN_DEG = 2f

    /** Blur radius at full strength, as a share of the pane's length (the film: about a tenth of a half). */
    const val BLUR_MAX_SHARE = .10f
    /** The blur is at full strength from this tilt; it grows linearly to it. */
    const val BLUR_FULL_TILT_DEG = 60f
    /** Across the pane: this share of the radius at the hinge, all of it at the free edge. */
    const val BLUR_AT_HINGE = .35f
    /** Darkness mid-pane at full strength (the film's guitar body: 0.53 of its brightness), the tilt it is reached by, and the curve's shape. */
    const val DARK_MID_MAX = .5f
    const val DARK_FULL_TILT_DEG = 60f
    const val DARK_TILT_POWER = .85f
    /**
     * The whole pane dims further once turned well past the vertical: a dim blur, never black. The film's icons clip at
     * ~150° shows the moving half at about half its brightness.
     */
    const val STEEP_DARK = .6f
    const val STEEP_FROM_DEG = 100f
    const val STEEP_TO_DEG = 165f
    /** The darkest any point gets. */
    const val DARK_CAP = .85f

    fun radians(degrees: Float): Float = degrees * (PI / 180).toFloat()

    fun smoothstep(from: Float, to: Float, x: Float): Float {
        val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * Where on the plane the pane point [d] (from the hinge along the pane) looks, for a pane tilted [tiltDeg]: from
     * the hinge along the plane, in the same unit. Flat, it is the point itself; tilted, a point nearer the hinge — so
     * the pane shows the first part of its page stretched over its whole length, which from the front is the page in
     * place. Never past [paneLen], and the tilt used is capped at [PROJECTION_MAX_TILT_DEG].
     */
    fun source(d: Float, tiltDeg: Float, paneLen: Float, eyeDistance: Float = VIEW_DISTANCE_MM): Float {
        val t = radians(tiltDeg.coerceIn(0f, PROJECTION_MAX_TILT_DEG))
        val depth = max(eyeDistance - d * sin(t), 1f)      // the glass point is this far from the eye along the normal
        return (d * cos(t) * eyeDistance / depth).coerceIn(0f, paneLen)
    }

    /** How much of the blur a tilt brings, 0..1. */
    fun blurStrength(tiltDeg: Float): Float = (tiltDeg / BLUR_FULL_TILT_DEG).coerceIn(0f, 1f)

    /** Blur across the pane, as a share of the free edge's radius, at [e] (0 = hinge, 1 = free edge). */
    fun blurAcross(e: Float): Float = BLUR_AT_HINGE + (1f - BLUR_AT_HINGE) * e.coerceIn(0f, 1f)

    /** Blur radius, in the unit of [paneLen], at [e] along a pane tilted [tiltDeg]; [gain] is the Intensity setting. */
    fun blurRadius(paneLen: Float, tiltDeg: Float, e: Float, gain: Float = 1f): Float =
        BLUR_MAX_SHARE * gain * paneLen * blurStrength(tiltDeg) * blurAcross(e)

    /** Darkness mid-pane for a tilt (the film's curve); [gain] is the Intensity setting. */
    fun darkMid(tiltDeg: Float, gain: Float = 1f): Float =
        min(DARK_CAP, DARK_MID_MAX * gain * (tiltDeg / DARK_FULL_TILT_DEG).coerceIn(0f, 1f).pow(DARK_TILT_POWER))

    /** Darkness across the pane relative to mid-pane: 0.63 at the hinge, exactly 1 mid-pane, 1.57 at the free edge. */
    fun darkAcross(e: Float): Float = (.4f + .6f * e.coerceIn(0f, 1f).pow(1.35f)) / DARK_ACROSS_MID
    /** 0.4 + 0.6·0.5^1.35: the shader divides by the same number. */
    const val DARK_ACROSS_MID = .6354f

    /** Extra dimming of the whole pane once turned well past the vertical. */
    fun steepDark(tiltDeg: Float): Float = STEEP_DARK * smoothstep(STEEP_FROM_DEG, STEEP_TO_DEG, tiltDeg)

    /** The darkness at [e] along a pane tilted [tiltDeg], 0..[DARK_CAP]. */
    fun dark(tiltDeg: Float, e: Float, gain: Float = 1f): Float =
        max(min(DARK_CAP, darkMid(tiltDeg, gain) * darkAcross(e)), steepDark(tiltDeg))

    /** How much the pane's own picture replaces the sharp screen: nothing at flat, all of it from [PANE_FADE_IN_DEG]. */
    fun paneAlpha(tiltDeg: Float): Float = smoothstep(0f, PANE_FADE_IN_DEG, tiltDeg)
}

/**
 * When things happen in the iPhone Duo style, on top of [DuoPaneModel]'s per-pixel look. The fold timeline
 * (FoldTransition.kt) turns its episode state into a tilt, a whole-screen dusk and a still's alpha with these.
 *
 * This phone lights one panel at a time, so the picture cannot be on both sides of a swap: the outgoing panel dusks to
 * black just before it, the incoming one dawns from black. Opening, the cover's still is already in place on the inner
 * right half (the film: the icons stay put as the phone opens); folding, the film shows the cover's own picture coming
 * into focus, so no still is carried that way.
 */
internal object DuoTiming {
    /** The timeline's strength m is (175° − hinge angle) / 163° while it follows the hinge: the moving half's tilt is m × this. */
    const val INNER_TILT_RANGE_DEG = 163f
    private const val FLAT_DEG = 175f
    /**
     * With the real angle the moving half's tilt is read from it directly, from here down: the film's fold effect starts
     * the instant the hinge leaves flat, whereas the sweep's flat band (175° in, 165° out) would hold the tilt at zero
     * for the first ~15° of a fold and then pop it in. The HAL stops a few degrees short of 180°, hence not 180.
     */
    const val FLAT_START_DEG = 178f

    /** The moving half's tilt from a real hinge angle. */
    fun innerTiltFromAngle(angleDeg: Float): Float = (FLAT_START_DEG - angleDeg).coerceIn(0f, INNER_TILT_RANGE_DEG)
    /**
     * A fast open outruns the panel: the inner screen lights with the hinge already near flat (the owner's opens lit it
     * at 124° and 160°), and a picture read from the angle alone has nothing left to show (owner, 2026-09-22: "if I go
     * too fast I have no animation I can see"). So from the moment the inner panel lights, the moving half's tilt is at
     * least this, settling to flat over [ARRIVAL_MS] — the last stretch of the film's opening, played whole even when
     * the hand was quicker than it. A slow open never notices: the hinge's own tilt is the larger for longer.
     */
    const val ARRIVAL_TILT_DEG = 42f
    const val ARRIVAL_MS = 340f
    /** The floor under the moving half's tilt, from the moment the inner panel lit. */
    fun arrivalTilt(sinceLitMs: Float): Float = ARRIVAL_TILT_DEG * (1f - DuoPaneModel.smoothstep(0f, ARRIVAL_MS, sinceLitMs))
    /** Dawn: the incoming panel fades up from black over this long after it is lit. */
    const val DAWN_MS = 120f
    /** The cover dusks to black over the last part of its hand-over (strength 0.6 → 1). */
    const val COVER_DUSK_FROM_M = .6f
    /**
     * The front screen is lit for only the first ~15° of an opening (early light swaps the panels at ~20°), and after a
     * fold it lights with at most ~35° of travel left. Tied to those few real degrees the look never got past a faint
     * edge blur before the dusk (owner, 2026-09-22: "the front screen animations are missing"). So the whole look — the
     * glass tilting away, the picture stretching from the hinge, blur and dark growing toward the free edge — is
     * compressed into the hand-over: the strength that rides the hinge (or the clock to the swap) stands for a tilt of
     * up to this many degrees. Coming back it plays in reverse, as the film's cover does (heavily frosted and dark at
     * its free edge when it appears, clearing as it flattens).
     */
    const val COVER_TILT_SPAN_DEG = 75f
    /**
     * Folding: the inner screen dusks over this many degrees before the angle at which the panels swap. Short and late:
     * in the film the fixed half stays bright until the moving half physically covers it (both panels lit at once);
     * this phone must switch the inner off, so it dims only just before that.
     */
    const val CLOSE_DUSK_SPAN_DEG = 10f
    /** … and is fully dark this many degrees before the early-cover request angle. */
    const val EARLY_COVER_LEAD_DEG = 4f
    /** Without early cover, One UI swaps at ~5°: fully dark by here (FoldTransition's HINGE_BOUND_DARK_DEG). */
    const val LATE_SWAP_DARK_DEG = 12f
    /** Opening: the cover's still holds on the inner right half, then melts into the live UI across these angles… */
    const val STILL_MELT_FROM_DEG = 55f
    const val STILL_MELT_TO_DEG = 120f
    /** … or, if the hinge rests, from this long after the panel lit, over [STILL_MELT_MS]. */
    const val STILL_HOLD_MS = 1_200f
    const val STILL_MELT_MS = 300f

    fun innerTilt(m: Float): Float = m.coerceIn(0f, 1f) * INNER_TILT_RANGE_DEG

    /** The hinge angle a strength stands for (the inverse of the timeline's map), for timed episodes. */
    fun angleFromStrength(m: Float): Float = FLAT_DEG - innerTilt(m)

    /** 1 = black, falling to 0 over [DAWN_MS]. */
    fun dawn(sinceLitMs: Float): Float = 1f - DuoPaneModel.smoothstep(0f, DAWN_MS, sinceLitMs)

    fun coverDusk(m: Float): Float = DuoPaneModel.smoothstep(COVER_DUSK_FROM_M, 1f, m)

    /** The angle at which the inner screen must be fully dark while folding. */
    fun closeDuskEndDeg(earlyCover: Boolean, earlyCoverRequestDeg: Float): Float =
        if (earlyCover) earlyCoverRequestDeg - EARLY_COVER_LEAD_DEG else LATE_SWAP_DARK_DEG

    /** Rises from 0 to 1 as the angle falls through the last [CLOSE_DUSK_SPAN_DEG] before [duskEndDeg]. */
    fun closingDusk(angleDeg: Float, duskEndDeg: Float): Float =
        DuoPaneModel.smoothstep(duskEndDeg + CLOSE_DUSK_SPAN_DEG, duskEndDeg, angleDeg)

    /** The cover's still on the inner right half: whole until 55°, gone by 120° — or by 1.5 s after lit if the hinge rests. */
    fun stillWhileOpening(angleDeg: Float, sinceLitMs: Float): Float = min(
        1f - DuoPaneModel.smoothstep(STILL_MELT_FROM_DEG, STILL_MELT_TO_DEG, angleDeg),
        1f - DuoPaneModel.smoothstep(STILL_HOLD_MS, STILL_HOLD_MS + STILL_MELT_MS, sinceLitMs))
}

/**
 * "A tiny bit of physical inertia": the picture follows the hinge through a spring rather than jumping with each word
 * of the angle. Critically damped-ish (a little overshoot is the settle at flat). Frame-rate independent: the step is
 * integrated in slices of at most [SLICE_MS].
 */
internal class PaneSpring(responseSeconds: Float = RESPONSE_S, private val damping: Float = DAMPING) {
    private val omega = (2 * PI / responseSeconds).toFloat()   // rad/s
    var value = 0f; private set
    var velocity = 0f; private set

    fun snap(to: Float) { value = to; velocity = 0f }

    /** Advances toward [target] by [dtMs] and returns the new value. */
    fun step(target: Float, dtMs: Float): Float {
        var left = dtMs.coerceIn(0f, MAX_DT_MS)
        while (left > 0f) {
            val h = min(left, SLICE_MS) / 1000f
            left -= SLICE_MS
            val accel = omega * omega * (target - value) - 2f * damping * omega * velocity
            velocity += accel * h
            value += velocity * h
        }
        return value
    }

    /** True once it has come to rest on [target]. */
    fun settled(target: Float): Boolean = kotlin.math.abs(value - target) < REST && kotlin.math.abs(velocity) < REST_VELOCITY

    companion object {
        const val RESPONSE_S = .16f
        const val DAMPING = .85f
        const val SLICE_MS = 4f
        const val MAX_DT_MS = 64f
        private const val REST = .002f
        private const val REST_VELOCITY = .02f
        /** Ratio of the first overshoot to the step, for these constants — documented for the tests. */
        val overshoot: Float get() = kotlin.math.exp((-DAMPING * PI / sqrt(1.0 - DAMPING * DAMPING)).toFloat())
    }
}
