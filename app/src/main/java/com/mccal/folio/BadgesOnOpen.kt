package com.mccal.folio

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * Fold8Duo: badges clear when you open the app from Folio, like iPhone (WP-48, the owner's request, 2026-09-21). A
 * switch beside Folio's other badge options, on unless you turn it off. The rules are in BadgeOnOpenRules.kt; here is
 * where they meet Folio: the launch paths call [BadgesOnOpen.opened], and MainActivity leaves the cleared keys out of its
 * badge counts next to Clear Badge's. In memory only, like Clear Badge: if Folio restarts, badges show again.
 *
 * What counts as opening the app: its icon, wherever Folio draws one (Home, the dock, folders, the App Library,
 * Spotlight, Today, Icon Stacks, the Discover page's dock, the dock over other apps). A quick action or a notification
 * opens one particular thing inside the app, so those leave the badge alone, as Folio's recent-apps list does.
 */
internal object BadgesOnOpen {
    private const val PREFS = "folio"
    private const val KEY_ENABLED = "fold8duo.clearBadgesOnOpen"

    /** The notifications each opened app had showing, by key, as they were then. */
    val seen = MutableStateFlow<Map<String, BadgeOnOpenRules.Seen>>(emptyMap())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var watching = false

    fun enabled(context: Context) = context.getSharedPreferences(PREFS, 0).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, 0).edit().putBoolean(KEY_ENABLED, on).apply()
        // Off means Folio's own badge counts again, straight away, for everything.
        if (!on) seen.value = emptyMap()
    }

    /** [packageName] was just opened from Folio: the notifications it has showing stop counting toward its badge. */
    fun opened(context: Context, packageName: String) {
        if (!enabled(context)) return
        val notes = IslandListenerService.notifications.value.map { it.badgeNote() }
        if (notes.none { it.packageName == packageName }) return
        seen.update { BadgeOnOpenRules.open(it, notes, packageName) }
        watch()
    }

    /**
     * Forgets each notification as it goes, even while another app is in front, so the same one posted again later is
     * new and badges again. Started by the first app opened; notifications keep changing in the listener either way.
     */
    private fun watch() {
        if (watching) return
        watching = true
        scope.launch {
            IslandListenerService.notifications.collect { items ->
                // Notification access lost, or Screenshot Mode hiding everything: an empty list then says nothing about
                // which notifications went.
                if (!IslandListenerService.connected.value || ScreenshotMode.on.value) return@collect
                val notes = items.map { it.badgeNote() }
                seen.update { BadgeOnOpenRules.forgetGone(it, notes) }
            }
        }
    }
}

/** A notification as BadgeOnOpenRules sees it: what it says is fingerprinted from its title and text. */
internal fun NotificationItem.badgeNote() = BadgeOnOpenRules.Note(key, packageName, postTime, clearable, (title to text).hashCode())

/** The notification keys that opening their apps has cleared, for Folio's badge counts (MainActivity). */
@Composable
internal fun rememberClearedOnOpen(items: List<NotificationItem>): Set<String> {
    val seen by BadgesOnOpen.seen.collectAsStateWithLifecycle()
    return remember(items, seen) { if (seen.isEmpty()) emptySet() else BadgeOnOpenRules.cleared(items.map { it.badgeNote() }, seen) }
}

/** Settings › Icons & Side Bar › App Icons, with the other badge options. */
@Composable
internal fun ClearBadgesOnOpenSwitch() {
    val context = LocalContext.current
    var on by remember { mutableStateOf(BadgesOnOpen.enabled(context)) }
    SettingsSwitch(stringResource(R.string.fold8_clear_badges_on_open), on, { on = it; BadgesOnOpen.setEnabled(context, it) }, "clear-badges-on-open-switch")
}
