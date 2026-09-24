package com.mccal.folio.duo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.mccal.folio.HingeFeed
import java.io.File
import java.lang.reflect.Proxy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Fold8Duo (WP-80, probes P-28 and P-29), development builds only: the data a Shizuku-free Duos needs, gathered from
 * the owner's ordinary folds while the Shizuku engine is still here to say what the true angle was.
 *
 * **P-28.** The sensors any app may read — the uncalibrated magnetometer (the far half's magnets move past it), the
 * game rotation vector (the IMU half's attitude from gyro and accelerometer, blind to magnets), the gyro, and the
 * public hinge sensor (0/90/180 only) — kept in a short ring while the screen is on. When the engine's true angle
 * moves, the ring from [PRE_MS] before the fold to [POST_MS] after it is written as one CSV per fold to the app's own
 * files (`…/Android/data/<package>/files/standalone/`), the true angle included, to fit and test [MagHinge]. It stops
 * by itself after [MAX_EPISODES] folds or [MAX_DAYS] days. Nothing is drawn, nothing leaves the phone.
 *
 * **P-29.** Whether an ordinary app can hear the system's fold states itself (the hidden DeviceStateManager, by
 * reflection), and how early compared with the engine's own signal. Listen only: this never requests a state.
 */
internal object StandaloneProbe : SensorEventListener, HingeFeed.Listener {
    private const val TAG = "FolioStandalone"
    private const val PREFS = "fold8duo.p28"
    private const val MAX_EPISODES = 60
    private const val MAX_DAYS = 3
    private const val RING_MS = 4_000L
    private const val PRE_MS = 1_500L
    private const val POST_MS = 500L
    private const val REST_MS = 800L
    private const val MIN_TRAVEL_DEG = 20f
    private const val PERIOD_US = 10_000   // 100 Hz

    private var started = false
    private lateinit var app: Context
    private lateinit var handler: Handler
    private val io = Executors.newSingleThreadExecutor { Thread(it, "FolioP28Writer").apply { isDaemon = true } }
    private var sensors: SensorManager? = null
    private var listening = false

    /** One sample. kind: M magnetometer (x y z + bias x y z), Q game rotation (x y z w), G gyro, H public hinge, T true angle, S signal. */
    private class Row(val t: Long, val kind: Char, val v: FloatArray)
    private val ring = ArrayDeque<Row>()
    private var inEpisode = false
    private var episodeFrom = 0L
    private var lastTruthAt = 0L
    private var truthMin = Float.MAX_VALUE
    private var truthMax = -Float.MAX_VALUE

    fun ensureStarted(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, 0)
        val firstAt = prefs.getLong("firstAt", 0L).takeIf { it > 0 } ?: System.currentTimeMillis().also { prefs.edit().putLong("firstAt", it).apply() }
        val count = prefs.getInt("episodes", 0)
        if (count >= MAX_EPISODES || System.currentTimeMillis() - firstAt > MAX_DAYS * 86_400_000L) {
            Log.i(TAG, "P-28: finished ($count folds recorded); not listening"); return
        }
        val thread = HandlerThread("FolioP28").apply { start() }   // a thread name // english-only
        handler = Handler(thread.looper)
        sensors = app.getSystemService(SensorManager::class.java)
        val screen = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { handler.post { listen(i.action == Intent.ACTION_SCREEN_ON) } }
        }
        val filter = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) }
        // System broadcasts only: nothing outside the phone's own system can reach this receiver.
        if (android.os.Build.VERSION.SDK_INT >= 33) app.registerReceiver(screen, filter, Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(screen, filter)
        val interactive = app.getSystemService(PowerManager::class.java)?.isInteractive == true
        handler.post { listen(interactive) }
        HingeFeed.attach(app, this, devSocket = false)
        handler.postDelayed(::tick, 250)
        Log.i(TAG, "P-28: recording folds ($count so far, up to $MAX_EPISODES) to ${dir()}")
        handler.post { probeDeviceStates() }
    }

    private fun dir(): File? = app.getExternalFilesDir("standalone")

    private fun listen(on: Boolean) {
        val sm = sensors ?: return
        if (on == listening) return
        listening = on
        if (!on) { sm.unregisterListener(this); ring.clear(); return }
        for (type in intArrayOf(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED, Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_GYROSCOPE))
            sm.getDefaultSensor(type)?.let { sm.registerListener(this, it, PERIOD_US, handler) }
        sm.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, handler) }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val kind = when (event.sensor.type) {
            Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> 'M'
            Sensor.TYPE_GAME_ROTATION_VECTOR -> 'Q'
            Sensor.TYPE_GYROSCOPE -> 'G'
            Sensor.TYPE_HINGE_ANGLE -> 'H'
            else -> return
        }
        add(Row(event.timestamp, kind, event.values.copyOf(minOf(event.values.size, 6))))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // The engine's true angle and signals arrive on the main thread; the clock is read there, the work done here.
    override fun onHingeAngle(degrees: Float) { val t = SystemClock.elapsedRealtimeNanos(); handler.post { onTruth(t, degrees) } }
    override fun onFeedChanged(connected: Boolean) = Unit
    override fun onOpeningSignal(swapInMs: Int) { val t = SystemClock.elapsedRealtimeNanos(); handler.post { add(Row(t, 'S', floatArrayOf(1f, swapInMs.toFloat()))) } }
    override fun onClosedSignal() { val t = SystemClock.elapsedRealtimeNanos(); handler.post { add(Row(t, 'S', floatArrayOf(0f, 0f))) } }

    private fun add(row: Row) {
        ring.addLast(row)
        val keepFrom = row.t - RING_MS * 1_000_000L
        if (!inEpisode) while (ring.isNotEmpty() && ring.first().t < keepFrom) ring.removeFirst()
    }

    private fun onTruth(t: Long, degrees: Float) {
        add(Row(t, 'T', floatArrayOf(degrees)))
        if (!inEpisode) { inEpisode = true; episodeFrom = t - PRE_MS * 1_000_000L; truthMin = degrees; truthMax = degrees }
        truthMin = minOf(truthMin, degrees); truthMax = maxOf(truthMax, degrees)
        lastTruthAt = t
    }

    private fun tick() {
        handler.postDelayed(::tick, 250)
        if (!inEpisode || SystemClock.elapsedRealtimeNanos() - lastTruthAt < REST_MS * 1_000_000L) return
        inEpisode = false
        val until = lastTruthAt + POST_MS * 1_000_000L
        val rows = ring.filter { it.t in episodeFrom..until }
        val travel = truthMax - truthMin
        val magnetometer = rows.count { it.kind == 'M' }
        if (travel < MIN_TRAVEL_DEG || magnetometer < 20) return
        val pre = rows.any { it.kind == 'M' && it.t < episodeFrom + PRE_MS * 1_000_000L / 2 }
        io.execute { write(rows, travel, pre) }
    }

    private fun write(rows: List<Row>, travel: Float, preRoll: Boolean) {
        val prefs = app.getSharedPreferences(PREFS, 0)
        val count = prefs.getInt("episodes", 0)
        if (count >= MAX_EPISODES) { handler.post { listen(false) }; return }
        val folder = dir() ?: return
        folder.mkdirs()
        val name = "fold_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(Date()) + ".csv"
        runCatching {
            File(folder, name).bufferedWriter().use { w ->
                w.write("# Fold8Duo probe P-28: public sensors vs the engine's true hinge angle, one fold\n")
                w.write("# t_ns is elapsedRealtimeNanos (sensor timestamps; T and S stamped on arrival)\n")
                w.write("# kinds: M uncalibrated magnetometer uT (x y z biasx biasy biasz), Q game rotation vector (x y z w), G gyro rad/s, H public hinge deg, T true angle deg, S signal (1 opening + swapInMs, 0 closed)\n")
                w.write("t_ns,kind,a,b,c,d,e,f\n")
                for (r in rows) {
                    w.write(r.t.toString()); w.write(","); w.write(r.kind.toString())
                    for (i in 0 until 6) { w.write(","); if (i < r.v.size) w.write(r.v[i].toString()) }
                    w.write("\n")
                }
            }
            prefs.edit().putInt("episodes", count + 1).apply()
            Log.i(TAG, "P-28: fold ${count + 1}/$MAX_EPISODES written: $name (${rows.size} rows, travel ${travel.toInt()} deg, pre-roll $preRoll)")
        }.onFailure { Log.w(TAG, "P-28: could not write $name", it) }
    }

    /** P-29: listen-only. Never calls requestState. */
    private fun probeDeviceStates() {
        val manager = runCatching { app.getSystemService("device_state") }.getOrNull()
        if (manager == null) { Log.i(TAG, "P-29: no device_state service reachable"); return }
        val cls = manager.javaClass
        val states = runCatching { cls.getMethod("getSupportedDeviceStates").invoke(manager).toString() }
            .recoverCatching { cls.getMethod("getSupportedStates").invoke(manager).let { (it as? IntArray)?.joinToString() ?: it.toString() } }
            .getOrElse { "unreadable: ${it.javaClass.simpleName}: ${it.message}" }
        Log.i(TAG, "P-29: supported states as an app: ${states.take(400)}")
        val callbackClass = runCatching { Class.forName("android.hardware.devicestate.DeviceStateManager\$DeviceStateCallback") }.getOrNull()
        if (callbackClass == null) { Log.i(TAG, "P-29: no DeviceStateCallback class reachable"); return }
        val callback = Proxy.newProxyInstance(callbackClass.classLoader, arrayOf(callbackClass)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.getOrNull(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "Fold8DuoP29"
                else -> {
                    if (method.name.contains("State", ignoreCase = true))
                        Log.i(TAG, "P-29: ${method.name} ${args?.joinToString { it.toString().take(160) }} at ${SystemClock.elapsedRealtime()}")
                    null
                }
            }
        }
        val executor = Executor { handler.post(it) }
        val registered = runCatching { cls.getMethod("registerCallback", Executor::class.java, callbackClass).invoke(manager, executor, callback) }
        val canRequest = cls.methods.any { it.name == "requestState" }
        Log.i(TAG, "P-29: listening=${registered.isSuccess}${registered.exceptionOrNull()?.let { " (${it.javaClass.simpleName}: ${it.cause?.message ?: it.message})" } ?: ""}; " +
            "requestState ${if (canRequest) "visible to the app (not called)" else "not visible"}")
    }
}
