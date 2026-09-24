package com.mccal.folio.duo

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Process
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.mccal.folio.AppEntry
import com.mccal.folio.CardNote
import com.mccal.folio.FoldShizuku
import com.mccal.folio.LauncherState
import com.mccal.folio.MenuDivider
import com.mccal.folio.MenuRow
import com.mccal.folio.R
import com.mccal.folio.SettingsCard
import com.mccal.folio.SettingsSwitch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * Fold8Duo (WP-58): apps fill the inner screen. The inner screen is landscape by nature (4:3) and Samsung has it ignore
 * what apps ask for, so a portrait-locked app sits in a box. Samsung's own cure is per app: Settings › Apps › the app ›
 * Aspect ratio › Full screen, which sets app-compat change OVERRIDE_ANY_ORIENTATION_TO_USER for it. This does the same
 * from Home, through the Shizuku core (priv/AppCompatOverrides.kt): a default for every app the owner installed, his
 * own on/off per app from the app's long-press panel, and a row that opens Samsung's page. What to switch is decided
 * by [FillPolicy] (pure, tested); the switching runs off the main thread and each switch restarts that app.
 */
internal object AppDisplay {
    private const val PREFS = "folio"
    private const val KEY_DEFAULT = "fold8duo.fillInner"
    private const val KEY_ON = "fold8duo.fillInner.on"
    private const val KEY_OFF = "fold8duo.fillInner.off"
    private const val KEY_OURS = "fold8duo.fillInner.ours"
    private const val TAG = "FolioDisplay"
    /** Android 14+'s per-app aspect ratio page (Samsung's answers it too), with a `package:` URI. */
    const val ACTION_ASPECT_RATIO = "android.settings.MANAGE_USER_ASPECT_RATIO_SETTINGS"

    /** The default for the apps the owner installed. */
    val byDefault = mutableStateOf(false)
    /** Bumps whenever a per-app choice changes, so a row showing one recomposes. */
    val revision = mutableIntStateOf(0)
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "FolioAppDisplay").apply { isDaemon = true } } /* a thread name */ // english-only
    @Volatile private var apps: List<AppEntry> = emptyList()
    private var loaded = false

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, 0)
    private fun names(context: Context, key: String): Set<String> = prefs(context).getStringSet(key, emptySet()) ?: emptySet()

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        byDefault.value = prefs(context).getBoolean(KEY_DEFAULT, false)
    }

    /** The owner's own choice for [packageName]: true / false, or null for "the default". */
    fun choice(context: Context, packageName: String): Boolean? = when (packageName) {
        in names(context, KEY_ON) -> true
        in names(context, KEY_OFF) -> false
        else -> null
    }

    /** Whether [app] fills the inner screen as things stand: the owner's choice, else the default when it qualifies. */
    fun wants(context: Context, app: AppEntry): Boolean {
        load(context)
        val candidates = if (qualifies(context, app)) setOf(app.packageName) else emptySet()
        return FillPolicy.wants(app.packageName, byDefault.value, candidates, names(context, KEY_ON), names(context, KEY_OFF))
    }

    fun setDefault(context: Context, on: Boolean) {
        load(context)
        prefs(context).edit().putBoolean(KEY_DEFAULT, on).apply()
        byDefault.value = on
        apply(context)
    }

    /** The owner's choice for one app: on, off, or back to the default (null). */
    fun setChoice(context: Context, packageName: String, choice: Boolean?) {
        load(context)
        val on = names(context, KEY_ON).toMutableSet()
        val off = names(context, KEY_OFF).toMutableSet()
        on.remove(packageName); off.remove(packageName)
        when (choice) { true -> on.add(packageName); false -> off.add(packageName); null -> Unit }
        prefs(context).edit().putStringSet(KEY_ON, on).putStringSet(KEY_OFF, off).apply()
        revision.intValue++
        apply(context)
    }

    /** The app's panel: an app that fills stops filling, and the other way round — as the owner's own choice. */
    fun toggle(context: Context, app: AppEntry) = setChoice(context, app.packageName, !wants(context, app))

    /** Samsung's own page for this app's aspect ratio (Settings › Apps › the app › Aspect ratio). */
    fun aspectRatioIntent(packageName: String): Intent =
        Intent(ACTION_ASPECT_RATIO, Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Follow Home's app list: a newly installed app gets the default the moment Home sees it. */
    fun watch(activity: ComponentActivity, state: Flow<LauncherState>) {
        activity.lifecycleScope.launch { state.map { it.apps }.distinctUntilChanged().collect { onApps(activity, it) } }
    }

    fun onApps(context: Context, apps: List<AppEntry>) {
        load(context)
        this.apps = apps
        if (byDefault.value || names(context, KEY_ON).isNotEmpty() || names(context, KEY_OURS).isNotEmpty()) apply(context)
    }

    /** The apps the default reaches: the owner's own profile, not the system's, not this launcher, not shortcuts. */
    private fun qualifies(context: Context, app: AppEntry): Boolean =
        !app.isShortcut && app.user == Process.myUserHandle() && app.packageName != context.packageName && !isSystem(context, app.packageName)

    private fun isSystem(context: Context, packageName: String): Boolean =
        runCatching { context.packageManager.getApplicationInfo(packageName, 0).flags and ApplicationInfo.FLAG_SYSTEM != 0 }.getOrDefault(true)

    /** Bring the device in line with the policy, off the main thread. Each switch restarts that app. */
    private fun apply(context: Context) {
        val app = context.applicationContext
        val snapshot = apps
        worker.execute {
            if (!FoldShizuku.connected) { Log.i(TAG, "fill inner screen: the fold engine is not connected; nothing changed"); return@execute }
            val listed = FoldShizuku.fullScreenPackages()
            if (listed.startsWith("failed")) { Log.w(TAG, "fill inner screen: $listed"); return@execute }
            val current = listed.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            val candidates = snapshot.filter { qualifies(app, it) }.map { it.packageName }.toSet()
            val ours = names(app, KEY_OURS).toMutableSet()
            val plan = FillPolicy.plan(byDefault.value, candidates, names(app, KEY_ON), names(app, KEY_OFF), current, ours)
            if (plan.isEmpty) return@execute
            var took = 0
            for (pkg in plan.enable) {
                val reply = FoldShizuku.setFullScreen(pkg, true)
                if (succeeded(reply)) { ours.add(pkg); took++ } else Log.w(TAG, "fill inner screen: $pkg: $reply")
            }
            for (pkg in plan.reset) {
                val reply = FoldShizuku.setFullScreen(pkg, false)
                if (succeeded(reply)) { ours.remove(pkg); took++ } else Log.w(TAG, "fill inner screen: $pkg: $reply")
            }
            prefs(app).edit().putStringSet(KEY_OURS, ours).apply()
            Log.i(TAG, "fill inner screen: on for ${plan.enable.size}, back to default for ${plan.reset.size}, $took took; ${ours.size} set by Folio")
        }
    }

    /** `am compat`'s own words for a switch that took (or one that was already at the default). */
    private fun succeeded(reply: String) = reply.startsWith("Enabled") || reply.startsWith("Reset") || reply.startsWith("No override")
}

/** Settings › Fold & Displays: the default for the apps the owner installed. */
@Composable
internal fun AppDisplayCard() {
    val context = LocalContext.current
    LaunchedEffect(Unit) { AppDisplay.load(context) }
    val byDefault by AppDisplay.byDefault
    SettingsCard(stringResource(R.string.fold8_fill_title)) {
        SettingsSwitch(stringResource(R.string.fold8_fill_default), byDefault, { AppDisplay.setDefault(context, it) }, "fill-inner-switch")
        CardNote(stringResource(R.string.fold8_fill_default_note))
        if (!FoldShizuku.connected) CardNote(stringResource(R.string.fold8_fill_needs_hinge))
    }
}

/** The app's long-press panel: fill the inner screen or not, and Samsung's own aspect ratio page. */
@Composable
internal fun AppDisplayRows(app: AppEntry, onDismiss: () -> Unit) {
    if (app.isShortcut) return
    val context = LocalContext.current
    LaunchedEffect(Unit) { AppDisplay.load(context) }
    val byDefault by AppDisplay.byDefault
    val revision = AppDisplay.revision.intValue
    val fills = remember(app.packageName, byDefault, revision) { AppDisplay.wants(context, app) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = .08f))) {
        if (FoldShizuku.connected) {
            MenuRow(stringResource(if (fills) R.string.fold8_app_fill_off else R.string.fold8_app_fill_on),
                icon = if (fills) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen) { AppDisplay.toggle(context, app) }
            MenuDivider()
        }
        MenuRow(stringResource(R.string.fold8_app_aspect_ratio), icon = Icons.Rounded.AspectRatio) {
            onDismiss()
            runCatching { context.startActivity(AppDisplay.aspectRatioIntent(app.packageName)) }
                .onFailure { Log.w("FolioDisplay", "aspect ratio page: ${it.javaClass.simpleName}") }
        }
    }
}
