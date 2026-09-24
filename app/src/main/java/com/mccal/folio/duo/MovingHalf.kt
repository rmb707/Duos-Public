package com.mccal.folio.duo

import kotlin.math.abs
import kotlin.math.exp

/**
 * Fold8Duo (WP-57): which half of the phone is the one moving, from the gyro and the hinge (SPEC §3.2 "world-lock").
 *
 * A fold changes the hinge angle by Δθ. The half that carries the IMU turns by Δφ about the hinge axis — all of Δθ if
 * the other half is held still, none of it if it is the one held still, half of it if both swing. So
 * `α = |Δφ_imu| / |Δθ|`, clamped to 0..1, tells the two panes' shares: the IMU half tilts by α·(180° − θ), the other by
 * the rest. Low-passed (τ = 150 ms) so a jittery reading never flips the picture; 0.5 until the sensors have spoken,
 * which is also the answer when there is no gyro.
 *
 * Pure: no android.*, no clock of its own. The caller feeds the gyro's rate about the hinge axis and the hinge angle.
 */
internal class MovingHalf(private val tauMs: Float = TAU_MS) {
    /** 0 = the IMU half is still, 1 = it is the one moving, 0.5 = both (or unknown). */
    var alpha = .5f; private set
    /** True once at least one real measurement has shaped [alpha]. */
    var measured = false; private set

    private var gyroDeg = 0f        // |rotation| about the hinge axis since the last measurement, degrees
    private var hingeFrom = Float.NaN
    private var lastHingeAt = 0L

    /** The gyro's rate about the hinge axis ([degPerSecond], sign irrelevant) over [dtSeconds]. */
    fun onGyro(degPerSecond: Float, dtSeconds: Float) {
        if (dtSeconds <= 0f || dtSeconds > 1f) return
        gyroDeg += abs(degPerSecond) * dtSeconds
    }

    /** A new hinge angle. Every [MIN_HINGE_DEG] of hinge travel, the gyro's share since then becomes a measurement. */
    fun onHinge(angleDeg: Float, nowMs: Long) {
        if (hingeFrom.isNaN()) { hingeFrom = angleDeg; lastHingeAt = nowMs; gyroDeg = 0f; return }
        val travel = abs(angleDeg - hingeFrom)
        if (travel < MIN_HINGE_DEG) return
        val raw = (gyroDeg / travel).coerceIn(0f, 1f)
        val dt = (nowMs - lastHingeAt).coerceIn(0L, 2_000L).toFloat()
        val k = if (measured) 1f - exp(-dt / tauMs) else 1f
        alpha += (raw - alpha) * k
        measured = true
        hingeFrom = angleDeg; lastHingeAt = nowMs; gyroDeg = 0f
    }

    /** A new fold: forget the accumulators (not the answer — how the phone is held rarely changes mid-fold). */
    fun reset() { hingeFrom = Float.NaN; gyroDeg = 0f }

    /** The two panes' tilts for a fold deficit [deficitDeg] (180° − θ): the IMU half's, then the other half's. */
    fun tilts(deficitDeg: Float): Pair<Float, Float> = (alpha * deficitDeg) to ((1f - alpha) * deficitDeg)

    companion object {
        const val TAU_MS = 150f
        /** Enough hinge travel for the ratio to mean something against the HAL's ~10° words and gyro noise. */
        const val MIN_HINGE_DEG = 8f
    }
}
