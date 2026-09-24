package com.mccal.folio.duo

import kotlin.math.abs
import kotlin.math.sqrt

/** A 3-vector (µT for fields). */
internal data class V3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Float) = V3(x * k, y * k, z * k)
    fun dot(o: V3) = x * o.x + y * o.y + z * o.z
    fun norm() = sqrt(dot(this))
}

/**
 * The IMU half's attitude: device → world. Built from a rotation-vector quaternion exactly as Android's
 * `SensorManager.getRotationMatrixFromVector` builds it, so the game rotation vector (gyro + accelerometer, no
 * magnetometer — SPEC Appendix B: the ~250 µT closure magnet ruins the ordinary rotation vector) feeds it directly.
 */
internal class Rot private constructor(private val m: FloatArray) {
    /** device → world */
    fun apply(v: V3) = V3(m[0] * v.x + m[1] * v.y + m[2] * v.z, m[3] * v.x + m[4] * v.y + m[5] * v.z, m[6] * v.x + m[7] * v.y + m[8] * v.z)
    /** world → device (the transpose: a rotation's inverse) */
    fun applyInverse(v: V3) = V3(m[0] * v.x + m[3] * v.y + m[6] * v.z, m[1] * v.x + m[4] * v.y + m[7] * v.z, m[2] * v.x + m[5] * v.y + m[8] * v.z)

    companion object {
        val IDENTITY = Rot(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f))

        /** From the sensor's (x, y, z[, w]); w is derived when the sensor leaves it out, as Android does. */
        fun fromQuaternion(x: Float, y: Float, z: Float, w: Float? = null): Rot {
            val q0 = w ?: sqrt((1f - x * x - y * y - z * z).coerceAtLeast(0f))
            val sq1 = 2 * x * x; val sq2 = 2 * y * y; val sq3 = 2 * z * z
            val q1q2 = 2 * x * y; val q3q0 = 2 * z * q0; val q1q3 = 2 * x * z
            val q2q0 = 2 * y * q0; val q2q3 = 2 * y * z; val q1q0 = 2 * x * q0
            return Rot(floatArrayOf(
                1 - sq2 - sq3, q1q2 - q3q0, q1q3 + q2q0,
                q1q2 + q3q0, 1 - sq1 - sq3, q2q3 - q1q0,
                q1q3 - q2q0, q2q3 + q1q0, 1 - sq1 - sq2))
        }
    }
}

/**
 * The magnets' field at the magnetometer, in the IMU half's frame, as a function of the hinge angle — the other half's
 * magnets and iron plus the IMU half's own constant bias. Rigid geometry makes it a function of the angle alone. A
 * table of measured points, linearly interpolated; it is fitted per phone from recorded folds (probe P-28).
 */
internal class MagCurve(private val angles: FloatArray, private val field: Array<V3>) {
    init {
        require(angles.size == field.size && angles.size >= 2)
        require((1 until angles.size).all { angles[it] > angles[it - 1] })
    }

    fun at(deg: Float): V3 {
        if (deg <= angles.first()) return field.first()
        if (deg >= angles.last()) return field.last()
        var i = 1
        while (angles[i] < deg) i++
        val t = (deg - angles[i - 1]) / (angles[i] - angles[i - 1])
        return field[i - 1] + (field[i] - field[i - 1]) * t
    }

    /** The angle in [lo, hi] whose field is closest to [b], and how far off it still is (µT). */
    fun invert(b: V3, lo: Float = angles.first(), hi: Float = angles.last(), step: Float = STEP): Pair<Float, Float> {
        val from = lo.coerceIn(angles.first(), angles.last())
        val to = hi.coerceIn(angles.first(), angles.last())
        var best = from
        var bestErr = Float.MAX_VALUE
        var deg = from
        while (deg <= to + 1e-3f) {
            val e = (b - at(deg)).norm()
            if (e < bestErr) { bestErr = e; best = deg }
            deg += step
        }
        // A parabola through the best grid point and its neighbours: sub-step resolution for free.
        val l = (b - at((best - step).coerceAtLeast(from))).norm()
        val r = (b - at((best + step).coerceAtMost(to))).norm()
        val curvature = l - 2 * bestErr + r
        if (curvature > 1e-6f && best - step >= from && best + step <= to) {
            val shift = (0.5f * (l - r) / curvature).coerceIn(-0.5f, 0.5f) * step
            best += shift
        }
        return best to (b - at(best)).norm()
    }

    private companion object { const val STEP = 0.5f }
}

/**
 * Fold8Duo (WP-80): the hinge angle from sensors any app may read — the Shizuku-free source.
 *
 * The magnetometer in one half sees the magnets in the other move past it, which carries the angle (SPEC §2: bx is
 * monotone over 0–180°). What ruined that estimate before (RMS 9.3°, and 43° off at 90° when the phone turned 10°
 * mid-fold) is Earth's field, which the table could not tell from the magnets. Here it is taken out: at a rest whose
 * angle is known — shut (0°) or flat (180°), which the public hinge sensor and the fold state both confirm — Earth's
 * field is what the magnetometer reads minus the magnets' own field at that angle. Carried into the world frame with
 * the IMU half's attitude, it stays put while the phone turns; each later reading minus Earth's field turned back
 * into the IMU half's frame leaves the magnets' field alone, which [MagCurve] turns back into an angle.
 */
internal class MagHinge(private val curve: MagCurve, private val window: Float = WINDOW_DEG) {
    private var earthWorld: V3? = null
    /** Degrees; NaN until anchored. */
    var angle = Float.NaN; private set
    /** µT left unexplained by the model at [angle]: large means the anchor is stale or something magnetic is near. */
    var residual = Float.NaN; private set
    val anchored get() = earthWorld != null

    /** At rest, at a known angle: take Earth's field from this reading. */
    fun anchor(knownDeg: Float, b: V3, attitude: Rot) {
        earthWorld = attitude.apply(b - curve.at(knownDeg))
        angle = knownDeg
        residual = 0f
    }

    fun update(b: V3, attitude: Rot): Float {
        val earth = earthWorld ?: return Float.NaN
        val magnets = b - attitude.applyInverse(earth)
        // Near the last answer first (a fold moves at most a few degrees per 10 ms sample), the whole range if that fits badly.
        var fit = if (angle.isNaN()) curve.invert(magnets) else curve.invert(magnets, angle - window, angle + window)
        if (fit.second > RELOCK_UT) fit = curve.invert(magnets).let { if (it.second < fit.second) it else fit }
        angle = fit.first
        residual = fit.second
        return angle
    }

    fun reset() { earthWorld = null; angle = Float.NaN; residual = Float.NaN }

    private companion object {
        const val WINDOW_DEG = 30f
        const val RELOCK_UT = 6f
    }
}

/** Helpers for tests and the recorder's analysis: how far apart two angle series are. */
internal object AngleError {
    fun rms(truth: List<Float>, estimate: List<Float>): Float {
        require(truth.size == estimate.size && truth.isNotEmpty())
        return sqrt(truth.indices.map { (truth[it] - estimate[it]).let { d -> d * d } }.average().toFloat())
    }
    fun max(truth: List<Float>, estimate: List<Float>): Float = truth.indices.maxOf { abs(truth[it] - estimate[it]) }
}
