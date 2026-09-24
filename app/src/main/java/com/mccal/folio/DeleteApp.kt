package com.mccal.folio

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource

/*
 * Fold8Duo: Delete App in an app's long-press menu, red like iPhone's, right under Remove from Home / Add to Home
 * (WP-48, the owner's request, 2026-09-21). Folio never removes anything itself: the row opens Android's own uninstall
 * confirmation, which names the app and removes it only if you confirm there. Hidden while a Focus locks Home editing,
 * like the rest of the editing rows. AppContextMenu.kt only calls in.
 *
 * Work profile: the app's own user goes along (Intent.EXTRA_USER), as AOSP's Launcher3 does, so a work app is removed
 * from the work profile and nowhere else. Android accepts that from Home for a profile of the same user; if a work
 * profile's admin forbids uninstalling, Android's screen says so.
 */
internal object DeleteApp {
    /** Whether [app]'s menu offers Delete App (DeleteAppRule.kt). Asks Android about the app in its own profile. */
    fun canOffer(context: Context, app: AppEntry): Boolean {
        val isFolio = app.packageName == context.packageName
        val info = if (app.isShortcut || isFolio || !app.available) null else runCatching {
            context.getSystemService(LauncherApps::class.java).getApplicationInfo(app.packageName, 0, app.user)
        }.getOrNull()
        return DeleteAppRule.offer(app.isShortcut, isFolio, app.available, installed = info != null,
            system = info != null && info.flags and ApplicationInfo.FLAG_SYSTEM != 0,
            updatedSystem = info != null && info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0)
    }

    /** Android's uninstall confirmation for [app] in its own profile (REQUEST_DELETE_PACKAGES lets Folio ask). */
    fun request(context: Context, app: AppEntry) {
        val intent = Intent(Intent.ACTION_DELETE, Uri.fromParts("package", app.packageName, null))
            .putExtra(Intent.EXTRA_USER, app.user)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        runCatching { context.startActivity(intent) }
            .onFailure { IslandEvents.notice(context, context.getString(R.string.fold8_delete_app_failed, app.label), app.icon) }
    }
}

/** Looked up once per menu: a single quick question to Android about one app. */
@Composable
internal fun rememberCanDelete(app: AppEntry): Boolean {
    val context = LocalContext.current
    return remember(app.id, app.available) { DeleteApp.canOffer(context, app) }
}

/** The row and the divider under it, for the long-press menu's editing rows. */
@Composable
internal fun DeleteAppMenuRow(app: AppEntry, onDismiss: () -> Unit) {
    val context = LocalContext.current
    MenuRow(stringResource(R.string.fold8_delete_app), Icons.Rounded.DeleteOutline, destructive = true) {
        DeleteApp.request(context, app)
        onDismiss()
    }
    MenuDivider()
}
