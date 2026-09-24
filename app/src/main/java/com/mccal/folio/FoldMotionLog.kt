package com.mccal.folio

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.abs

/**
 * Fold8Duo (WP-57, probe P-18 in the owner's own folds): which half of the phone carries the IMU, and which half he
 * moves. Development builds only, Home only. While the real hinge is reporting, the gyroscope's rotation about each
 * of its three axes is integrated; when the hinge comes to rest, one line goes to `FolioFoldTrace`:
 *
 *     motion: Δθ=-152° over 640 ms; gyro |x|=3° |y|=149° |z|=5°  → hinge axis y, imu share 0.98
 *
 * The axis whose rotation tracks Δθ is the hinge axis; its share of Δθ is α (SPEC §3.2): ~1 means the IMU half is the
 * one that swings, ~0 that it is held still, ~0.5 both. A few natural folds answer both questions; nothing is drawn.
 */
internal class FoldMotionLog(context: Context) : SensorEventListener, HingeFeed.Listener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val gyro: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val appContext = context.applicationContext
    private var listening = false
    private val turned = FloatArray(3)
    private var lastGyroNanos = 0L
    private var from = Float.NaN
    private var last = Float.NaN
    private var startedAt = 0L
    private var lastWordAt = 0L
    private var running = false

    fun start() {
        if (gyro == null) return
        HingeFeed.attach(appContext, this, devSocket = false)
    }

    fun stop() {
        HingeFeed.detach(this)
        finish("stopped")
        setListening(false)
    }

    override fun onHingeAngle(degrees: Float) {
        val now = SystemClock.uptimeMillis()
        if (!running || now - lastWordAt > REST_MS) { finish("rest"); begin(degrees, now) }
        last = degrees; lastWordAt = now
    }

    override fun onFeedChanged(connected: Boolean) { if (!connected) { finish("feed gone"); setListening(false) } }

    private fun begin(degrees: Float, now: Long) {
        running = true; from = degrees; last = degrees; startedAt = now
        turned.fill(0f); lastGyroNanos = 0L
        setListening(true)
    }

    private fun finish(why: String) {
        if (!running) return
        running = false
        val delta = last - from
        val ms = lastWordAt - startedAt
        if (abs(delta) >= MIN_DEG) {
            val axis = turned.indices.maxByOrNull { turned[it] } ?: 1
            val share = if (abs(delta) > 0f) (turned[axis] / abs(delta)).coerceIn(0f, 2f) else 0f
            FoldTrace.event("motion: Δθ=${"%.0f".format(delta)}° over $ms ms; gyro |x|=${"%.0f".format(turned[0])}° |y|=${"%.0f".format(turned[1])}° |z|=${"%.0f".format(turned[2])}° → hinge axis ${"xyz"[axis]}, imu share ${"%.2f".format(share)} ($why)")
        }
    }

    private fun setListening(on: Boolean) {
        if (on == listening) return
        listening = on
        if (on) gyro?.let { sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) } else sensors?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!running) return
        if (lastGyroNanos != 0L) {
            val dt = (event.timestamp - lastGyroNanos) / 1e9f
            if (dt in 0f..0.5f) for (i in 0..2) turned[i] += abs(event.values[i]) * dt * DEG_PER_RAD
        }
        lastGyroNanos = event.timestamp
        // The gyro keeps reporting between the HAL's words; a hinge at rest for a while ends the episode.
        if (SystemClock.uptimeMillis() - lastWordAt > REST_MS) { finish("rest"); setListening(false) }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val REST_MS = 900L
        const val MIN_DEG = 20f
        const val DEG_PER_RAD = 57.29578f
    }
}
