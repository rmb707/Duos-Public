package com.mccal.folio

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.mccal.folio.priv.FoldPrivilegedService
import com.mccal.folio.priv.IFoldPrivileged
import com.mccal.folio.priv.IFoldPrivilegedCallback
import rikka.shizuku.Shizuku

/**
 * Fold8Duo: the app's end of the USB-free fold engine.
 *
 * What makes the fold effect real on a Galaxy Z Fold8 — the hinge's true angle, lighting the inner screen as the phone
 * starts to open, lighting the front screen before it is shut — needs the shell user (docs/decisions.md D1, D13).
 * Shizuku lends an app that identity without root: it starts [FoldPrivilegedService] as uid 2000, and that service
 * reports back here over Binder. Unlike the development socket, Binder is not firewalled while the app is in the
 * background, so the angle keeps arriving while Home is off screen and an open straight from sleep is followed too.
 *
 * Everything degrades to upstream behaviour when Shizuku is absent, stopped, or refused: [HingeFeed] simply never
 * reports connected, and the fold effect runs on its learned timings.
 */
internal object FoldShizuku {
    enum class Status { OFF, NOT_RUNNING, NEEDS_PERMISSION, CONNECTING, CONNECTED }

    private const val TAG = "FolioHinge"
    private const val PREFS = "folio"
    private const val KEY_ENABLED = "fold8duo_engine"
    private const val KEY_EARLY_LIGHT = "fold8duo_early_light"
    private const val KEY_SWAP_DELAY = "fold8duo_swap_delay_ms"
    private const val KEY_EARLY_COVER = "fold8duo_early_cover_deg"
    private const val PERMISSION_CODE = 8108
    /** Bump when the service's code changes: Shizuku restarts a UserService whose version differs. */
    private const val SERVICE_VERSION = 10
    private const val KEY_OVER_APPS = "fold8duo_over_apps"
    private const val KEY_LIVE_FROST = "fold8duo_live_frost"

    var status by mutableStateOf(Status.NOT_RUNNING); private set

    /**
     * Every change of state is said out loud, once. "Timings off by a lot" turned out to be a dead Shizuku server
     * (unplugging USB restarts adbd and takes a USB-started server with it), and the log had nothing to say about it.
     */
    private fun become(next: Status) {
        if (status != next) Log.i(TAG, "Shizuku fold engine: $status -> $next")
        // Going from working to gone is the one change worth interrupting for: everything quietly falls back to timers,
        // and "the timings are off" is all anyone would notice. Said once, the next time Home is on screen.
        if (status == Status.CONNECTED && next == Status.NOT_RUNNING) owedNotice = true
        if (next == Status.CONNECTED) owedNotice = false
        status = next
    }
    private var owedNotice = false
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private fun sayIfOwed(context: Context) {
        if (!owedNotice) return
        main.postDelayed({
            if (owedNotice && status != Status.CONNECTED && FolioForeground.visible.value) {
                owedNotice = false
                IslandEvents.notice(context, context.getString(R.string.fold8_notice_hinge_offline))
            }
        }, NOTICE_DELAY_MS)
    }
    private const val NOTICE_DELAY_MS = 1_500L
    /** What the service last said about itself, for the settings card. */
    var detail by mutableStateOf(""); private set
    private var revision by mutableIntStateOf(0)

    private var appContext: Context? = null
    private var listening = false
    private var asked = false
    private var bound = false
    @Volatile private var service: IFoldPrivileged? = null

    private val callback = object : IFoldPrivilegedCallback.Stub() {
        override fun onOpening(swapInMs: Int) = HingeFeed.fromService { it.onOpeningSignal(swapInMs) }
        override fun onAngle(degrees: Int) = HingeFeed.serviceAngle(degrees.toFloat())
        override fun onClosed() = HingeFeed.fromService { it.onClosedSignal() }
        override fun onLog(message: String) { Log.i(TAG, "engine: $message") }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val remote = IFoldPrivileged.Stub.asInterface(binder)
            service = remote
            val context = appContext ?: return
            runCatching {
                remote.start(callback, earlyLight(context), swapDelayMs(context), earlyCoverDeg(context))
                detail = remote.describe()
            }.onSuccess { become(Status.CONNECTED); HingeFeed.serviceState(true); Log.i(TAG, "fold engine connected over Shizuku: $detail") }
                .onFailure { become(Status.NOT_RUNNING); Log.w(TAG, "fold engine refused to start", it) }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            service = null; bound = false
            HingeFeed.serviceState(false)
            become(Status.NOT_RUNNING)
            Log.i(TAG, "fold engine went away")
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, 0)
    fun enabled(context: Context) = prefs(context).getBoolean(KEY_ENABLED, true)
    fun earlyLight(context: Context) = prefs(context).getBoolean(KEY_EARLY_LIGHT, true)
    fun swapDelayMs(context: Context) = prefs(context).getInt(KEY_SWAP_DELAY, 280)
    fun earlyCoverDeg(context: Context) = prefs(context).getInt(KEY_EARLY_COVER, 40)
    /** The fold effect over other apps (FoldOverlay.kt). */
    fun overApps(context: Context) = prefs(context).getBoolean(KEY_OVER_APPS, true)
    /** Frost what is really under the effect, from a mirror of the screen, when the engine is connected. */
    fun liveFrost(context: Context) = prefs(context).getBoolean(KEY_LIVE_FROST, true)
    fun setOverApps(context: Context, on: Boolean) { prefs(context).edit().putBoolean(KEY_OVER_APPS, on).apply(); revision++ }
    fun setLiveFrost(context: Context, on: Boolean) { prefs(context).edit().putBoolean(KEY_LIVE_FROST, on).apply(); revision++ }

    // ------------------------------------------------------------------------------- the screen, for the overlay
    // Both calls are Binder round trips into the shell-side service (a few ms; starting a mirror more): never on the
    // main thread in the middle of an animation. "" = done, anything else says why not.

    val connected get() = status == Status.CONNECTED && service != null

    fun startMirror(surface: android.view.Surface, width: Int, height: Int): String =
        runCatching { service?.startMirror(surface, width, height) ?: "the fold engine is not connected" }.getOrElse { "startMirror: ${it.javaClass.simpleName}: ${it.message}" }

    fun stopMirror() { runCatching { service?.stopMirror() } }

    /** The service's one line about itself, asked now (blocking). */
    fun describeNow(): String = runCatching { service?.describe() }.getOrNull() ?: "not connected"

    /** The system's last picture of a background app, or null. Blocking; the caller closes the buffer. */
    fun taskSnapshot(packageName: String, userId: Int): android.hardware.HardwareBuffer? =
        runCatching { service?.taskSnapshot(packageName, userId) }.getOrNull()

    fun taskSnapshotRoute(): String = runCatching { service?.taskSnapshotRoute() }.getOrNull() ?: "not connected"

    fun excludeFromCapture(layer: android.view.SurfaceControl): String =
        runCatching { service?.excludeFromCapture(layer) ?: "the fold engine is not connected" }.getOrElse { "excludeFromCapture: ${it.javaClass.simpleName}: ${it.message}" }

    /**
     * Starts an app from the shell with its real window growing out of [from] (screen px) — WP-53's real open. "" =
     * started, else why not (nothing was started: launch the ordinary way). A Binder round trip: not on the main thread
     * mid-animation, but a tap is fine.
     */
    fun launch(intent: android.content.Intent, userId: Int, from: android.graphics.Rect, screenRadius: Float): String =
        runCatching { service?.launch(intent, userId, from, screenRadius) ?: "the fold engine is not connected" }.getOrElse { "launch: ${it.javaClass.simpleName}: ${it.message}" }

    fun launchRoute(): String = runCatching { service?.launchRoute() }.getOrNull() ?: "not connected"

    /** WP-58: the platform's Full screen for one app, switched by the shell. A Binder round trip that restarts that app: never on the main thread. */
    fun setFullScreen(packageName: String, on: Boolean): String =
        runCatching { service?.setFullScreen(packageName, on) ?: "the fold engine is not connected" }.getOrElse { "setFullScreen: ${it.javaClass.simpleName}: ${it.message}" }

    /** The packages the platform has Full screen on for, comma-separated; "failed: …" when it cannot say. Blocking. */
    fun fullScreenPackages(): String =
        runCatching { service?.fullScreenPackages() ?: "failed: the fold engine is not connected" }.getOrElse { "failed: ${it.javaClass.simpleName}: ${it.message}" }

    private fun args(context: Context) = Shizuku.UserServiceArgs(ComponentName(context.packageName, FoldPrivilegedService::class.java.name))
        .daemon(false)                       // the engine must never outlive the app that asked for it
        .processNameSuffix("fold")
        .debuggable(false)
        .version(SERVICE_VERSION)

    /** Safe to call often (it is, from [HingeFeed.attach]). Does nothing until Shizuku is running. */
    fun ensureStarted(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (!listening) {
            listening = true
            runCatching {
                Shizuku.addBinderReceivedListenerSticky { refresh() }
                Shizuku.addBinderDeadListener {
                    // Seen on the SM-F971U1: unplugging USB restarts adbd, which takes a USB-started Shizuku server
                    // with it. The fold effect then falls back to learned timings until Shizuku is started again.
                    Log.w(TAG, "Shizuku's server died; the fold effect is back on learned timings until it is restarted")
                    bound = false; service = null; HingeFeed.serviceState(false); refresh()
                }
                Shizuku.addRequestPermissionResultListener { code, result -> if (code == PERMISSION_CODE && result == PackageManager.PERMISSION_GRANTED) refresh() else refresh() }
            }.onFailure { Log.w(TAG, "Shizuku is not usable here", it) }
        }
        refresh()
        sayIfOwed(app)
    }

    private fun refresh() {
        val context = appContext ?: return
        revision++
        if (!enabled(context)) { unbind(context); become(Status.OFF); return }
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) { become(Status.NOT_RUNNING); return }
        if (runCatching { Shizuku.checkSelfPermission() }.getOrDefault(PackageManager.PERMISSION_DENIED) != PackageManager.PERMISSION_GRANTED) {
            become(Status.NEEDS_PERMISSION)
            // Ask once per process, unprompted: nobody goes looking in Settings for a switch they do not know exists,
            // and without this the fold effect just quietly runs on timers. Never again after "deny and don't ask".
            if (!asked && !runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false)) {
                asked = true
                Log.i(TAG, "asking Shizuku for permission")
                requestPermission()
            }
            return
        }
        if (bound) return
        bound = true
        become(Status.CONNECTING)
        runCatching { Shizuku.bindUserService(args(context), connection) }
            .onFailure { bound = false; become(Status.NOT_RUNNING); Log.w(TAG, "could not bind the fold engine", it) }
    }

    private fun unbind(context: Context) {
        if (!bound) return
        runCatching { service?.stop() }
        runCatching { Shizuku.unbindUserService(args(context), connection, true) }
        bound = false; service = null
        HingeFeed.serviceState(false)
    }

    fun requestPermission() { runCatching { Shizuku.requestPermission(PERMISSION_CODE) } }

    fun setEnabled(context: Context, on: Boolean) { prefs(context).edit().putBoolean(KEY_ENABLED, on).apply(); refresh() }

    /** Applies a changed setting by restarting the engine with it. */
    fun set(context: Context, earlyLight: Boolean? = null, swapDelayMs: Int? = null, earlyCoverDeg: Int? = null) {
        prefs(context).edit().apply {
            earlyLight?.let { putBoolean(KEY_EARLY_LIGHT, it) }
            swapDelayMs?.let { putInt(KEY_SWAP_DELAY, it) }
            earlyCoverDeg?.let { putInt(KEY_EARLY_COVER, it) }
        }.apply()
        revision++
        val remote = service ?: return
        runCatching { remote.start(callback, earlyLight(context), swapDelayMs(context), earlyCoverDeg(context)); detail = remote.describe() }
    }

    /** Reading this in composition makes the settings card follow changes. */
    val observed: Int get() = revision
}

/** Fold8Duo: Settings › Fold & Displays. One card; everything else about the fold effect stays upstream's. */
@Composable
internal fun RealHingeSettingsCard() {
    val context = LocalContext.current
    FoldShizuku.observed
    val status = FoldShizuku.status
    SettingsCard(stringResource(R.string.fold8_real_hinge_title)) {
        SettingsSwitch(stringResource(R.string.fold8_follow_real_hinge), FoldShizuku.enabled(context), { FoldShizuku.setEnabled(context, it) }, "real-hinge-switch")
        CardNote(when (status) {
            FoldShizuku.Status.OFF -> stringResource(R.string.fold8_hinge_off)
            FoldShizuku.Status.NOT_RUNNING -> stringResource(R.string.fold8_hinge_not_running)
            FoldShizuku.Status.NEEDS_PERMISSION -> stringResource(R.string.fold8_hinge_needs_permission)
            FoldShizuku.Status.CONNECTING -> stringResource(R.string.fold8_hinge_connecting)
            FoldShizuku.Status.CONNECTED -> stringResource(R.string.fold8_hinge_connected, FoldShizuku.detail)
        })
        if (status == FoldShizuku.Status.NEEDS_PERMISSION) CardAction(stringResource(R.string.fold8_allow_in_shizuku)) { FoldShizuku.requestPermission() }
        if (status == FoldShizuku.Status.CONNECTED) {
            SettingsSwitch(stringResource(R.string.fold8_light_early), FoldShizuku.earlyLight(context), { FoldShizuku.set(context, earlyLight = it) }, "early-light-switch")
            CardNote(stringResource(R.string.fold8_light_early_note))
        }
    }
    SettingsCard(stringResource(R.string.fold8_over_apps_title)) {
        SettingsSwitch(stringResource(R.string.fold8_over_apps), FoldShizuku.overApps(context), { FoldShizuku.setOverApps(context, it) }, "fold-over-apps-switch")
        CardNote(stringResource(R.string.fold8_over_apps_note))
        if (FoldShizuku.overApps(context)) {
            SettingsSwitch(stringResource(R.string.fold8_live_frost), FoldShizuku.liveFrost(context), { FoldShizuku.setLiveFrost(context, it) }, "fold-live-frost-switch")
            CardNote(stringResource(if (status == FoldShizuku.Status.CONNECTED) R.string.fold8_live_frost_note else R.string.fold8_live_frost_needs_hinge))
        }
    }
    SettingsCard(stringResource(R.string.fold8_smooth_title)) {
        FoldSmoothness.load(context)
        SettingsSwitch(stringResource(R.string.fold8_smooth_switch), FoldSmoothness.halfRes.value, { FoldSmoothness.set(context, it) }, "fold-half-res-switch")
        CardNote(stringResource(R.string.fold8_smooth_note))
    }
    SettingsCard(stringResource(R.string.fold8_open_close_title)) {
        var morph by androidx.compose.runtime.remember { mutableStateOf(IconMorph.enabled(context)) }
        var card by androidx.compose.runtime.remember { mutableStateOf(OpenMorph.enabled(context)) }
        SettingsSwitch(stringResource(R.string.fold8_icon_morph), morph, { morph = it; IconMorph.setEnabled(context, it) }, "icon-morph-switch")
        CardNote(stringResource(R.string.fold8_icon_morph_note))
        if (morph) {
            SettingsSwitch(stringResource(R.string.fold8_open_morph), card, { card = it; OpenMorph.setEnabled(context, it) }, "open-morph-switch")
            CardNote(stringResource(R.string.fold8_open_morph_note))
        }
    }
}
