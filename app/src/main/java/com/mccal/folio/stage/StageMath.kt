package com.mccal.folio.stage

import kotlin.math.pow

/**
 * Fold8Duo: the numbers behind the fold effect when it is drawn over another app. No android.* here, so they run as a
 * plain JVM test.
 *
 * On Home the effect filters Folio's own pixels: `pixel × (1 − 0.9·dark) + glint` behind one soft front that crosses
 * the screen (DuoShader.sweepFront in FoldTransition.kt). Over another app there are no pixels to filter — but that
 * formula is exactly "black at alpha 0.9·dark, plus a little light", which needs none. Only the frost that runs ahead of
 * the dark needs to know what is underneath. This is the same front, so the two can never disagree about where it is.
 */
internal object VeilModel {
    /** Softness of the front, as a share of its path. */
    const val SOFTNESS = .35f
    /** How dark the dark gets: never quite black, as on Home. */
    const val MAX_DARK = .9f

    /** Where the front is for strength [m], along a path that runs 0..1. Past 1 the whole screen is behind it. */
    fun front(m: Float): Float = m.coerceIn(0f, 1f).pow(1.4f) * (1f + SOFTNESS)

    fun smoothstep(from: Float, to: Float, x: Float): Float {
        val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** 0 = untouched, 1 = fully behind the front, at [u] along the path (0 = where the dark enters). */
    fun dark(m: Float, u: Float): Float = front(m).let { f -> 1f - smoothstep(f - SOFTNESS, f, u) }

    /** The frost runs a little ahead of the dark. */
    fun frost(m: Float, u: Float): Float = front(m).let { f -> 1f - smoothstep(f - SOFTNESS, f + .5f * SOFTNESS, u) }

    /** Alpha of the black veil at [u]: drawn over an app, it leaves exactly what the Home shader leaves of Home. */
    fun veilAlpha(m: Float, u: Float): Float = MAX_DARK * dark(m, u)
}
