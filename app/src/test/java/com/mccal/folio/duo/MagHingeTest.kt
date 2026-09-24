package com.mccal.folio.duo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * The Earth-compensated magnetometer hinge (WP-80), on synthetic folds. bx is the Fold 8's measured prior (SPEC
 * Appendix B); by and bz are made up but smooth, as a real unit's will be once probe P-28 fits them. What these tests
 * prove is the method: the anchor, the attitude carry and the inversion — and that it survives the failure the spec
 * recorded (the phone turning 10° mid-fold: 43° off at 90° without compensation).
 */
class MagHingeTest {
    private val bxPrior = mapOf(0 to -197.4f, 5 to -203.0f, 15 to -208.3f, 25 to -216.1f, 35 to -223.6f, 45 to -229.0f, 55 to -230.7f,
        65 to -235.5f, 75 to -238.2f, 85 to -240.0f, 95 to -242.2f, 105 to -244.2f, 115 to -245.2f, 125 to -247.0f, 135 to -248.0f,
        145 to -249.8f, 155 to -251.9f, 165 to -254.5f, 175 to -255.7f, 180 to -260.4f)

    private fun by(deg: Float) = -35f + 25f * sin(PI.toFloat() * deg / 180f)
    private fun bz(deg: Float) = 60f - 30f * (deg / 180f).pow(1.5f)

    private val curve: MagCurve = run {
        val angles = bxPrior.keys.sorted().map { it.toFloat() }
        MagCurve(angles.toFloatArray(), angles.map { V3(bxPrior[it.toInt()]!!, by(it), bz(it)) }.toTypedArray())
    }

    /** Earth's field in the world frame: ~50 µT, mostly down, some north. */
    private val earth = V3(0f, 22f, -45f)

    /** The IMU half turned [deg] about [axis] (unit). */
    private fun turn(axis: V3, deg: Float): Rot {
        val h = (deg * PI / 360).toFloat()
        return Rot.fromQuaternion(axis.x * sin(h), axis.y * sin(h), axis.z * sin(h), cos(h))
    }

    /** What the magnetometer reads at hinge angle [deg] with the IMU half at [attitude], plus noise. */
    private fun reading(deg: Float, attitude: Rot, noise: Float = 0f, rnd: Random = Random(1)): V3 {
        val clean = curve.at(deg) + attitude.applyInverse(earth)
        if (noise == 0f) return clean
        fun n() = (rnd.nextFloat() + rnd.nextFloat() + rnd.nextFloat() - 1.5f) * 2f * noise   // ~N(0, noise)
        return clean + V3(n(), n(), n())
    }

    @Test fun rotationMatchesAndroidAndInverts() {
        val r = turn(V3(0f, 1f, 0f), 90f)
        val v = r.apply(V3(1f, 0f, 0f))                 // x turned 90° about y → -z
        assertEquals(0f, v.x, 1e-5f); assertEquals(-1f, v.z, 1e-5f)
        val back = r.applyInverse(v)
        assertEquals(1f, back.x, 1e-5f); assertEquals(0f, back.z, 1e-5f)
        // w left out: derived, as SensorManager does.
        val same = Rot.fromQuaternion(0f, sin(PI.toFloat() / 4), 0f).apply(V3(1f, 0f, 0f))
        assertEquals(v.z, same.z, 1e-5f)
    }

    @Test fun curveInvertsItsOwnPoints() {
        for (deg in listOf(0f, 3f, 17f, 40f, 90f, 140f, 173f, 180f)) {
            val (found, err) = curve.invert(curve.at(deg))
            assertEquals("at $deg", deg, found, 0.3f)
            assertTrue(err < 0.2f)
        }
    }

    @Test fun anOpeningHeldStillIsFollowedExactly() {
        val hinge = MagHinge(curve)
        val still = Rot.IDENTITY
        hinge.anchor(0f, reading(0f, still), still)
        val truth = (0..180 step 2).map { it.toFloat() }
        val est = truth.map { hinge.update(reading(it, still), still) }
        assertTrue("rms ${AngleError.rms(truth, est)}", AngleError.rms(truth, est) < 0.5f)
    }

    @Test fun theSpecsFailureTurningTenDegreesMidFoldNoLongerMatters() {
        // The spec's case: the phone (the IMU half with it) turns 10° about y while the hinge passes 60°–100°.
        val axis = V3(0f, 1f, 0f)
        val truth = (0..180 step 2).map { it.toFloat() }
        fun attitudeAt(deg: Float) = turn(axis, 10f * ((deg - 60f) / 40f).coerceIn(0f, 1f))

        val compensated = MagHinge(curve).apply { anchor(0f, reading(0f, Rot.IDENTITY), Rot.IDENTITY) }
        val est = truth.map { compensated.update(reading(it, attitudeAt(it)), attitudeAt(it)) }

        // What the prior-only estimate does: take Earth's field once, in the device frame, and never turn it.
        val naive = MagHinge(curve).apply { anchor(0f, reading(0f, Rot.IDENTITY), Rot.IDENTITY) }
        val naiveEst = truth.map { naive.update(reading(it, attitudeAt(it)), Rot.IDENTITY) }

        val at90 = truth.indexOf(90f)
        assertTrue("compensated at 90°: ${est[at90]}", kotlin.math.abs(est[at90] - 90f) < 1f)
        assertTrue("naive at 90°: ${naiveEst[at90]}", kotlin.math.abs(naiveEst[at90] - 90f) > 10f)
        assertTrue("compensated rms ${AngleError.rms(truth, est)}", AngleError.rms(truth, est) < 0.6f)
    }

    @Test fun realisticNoiseStaysWithinTheSpecsTarget() {
        // σ = 0.31 µT per axis (the spec's rest noise), 100 Hz over a 1 s fold, while the phone wobbles ±6°.
        val rnd = Random(7)
        val hinge = MagHinge(curve)
        hinge.anchor(180f, reading(180f, Rot.IDENTITY), Rot.IDENTITY)
        val truth = (0 until 100).map { 180f - 180f * it / 99f }
        val est = truth.mapIndexed { i, deg ->
            val wobble = turn(V3(0.6f, 0.8f, 0f), 6f * sin(i / 9f))
            hinge.update(reading(deg, wobble, 0.31f, rnd), wobble)
        }
        val rms = AngleError.rms(truth, est)
        assertTrue("rms $rms", rms < 2.5f)          // SPEC §1.2 target: ≤ 5° RMS, rotation-robust
        assertTrue("max ${AngleError.max(truth, est)}", AngleError.max(truth, est) < 8f)
    }

    @Test fun unanchoredSaysNothing() {
        val hinge = MagHinge(curve)
        assertTrue(hinge.update(reading(40f, Rot.IDENTITY), Rot.IDENTITY).isNaN())
        hinge.anchor(0f, reading(0f, Rot.IDENTITY), Rot.IDENTITY)
        assertTrue(hinge.anchored)
        hinge.reset()
        assertTrue(!hinge.anchored && hinge.angle.isNaN())
    }
}
