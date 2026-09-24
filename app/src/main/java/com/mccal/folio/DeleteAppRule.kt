package com.mccal.folio

/**
 * Fold8Duo: when an app's long-press menu offers Delete App (WP-48). Only for something Android can uninstall, and the
 * uninstall itself is always Android's own confirmation (DeleteApp.kt). Pure, so it runs as a JVM test.
 */
internal object DeleteAppRule {
    /**
     * - A pinned shortcut isn't an app; its menu already says Delete Shortcut.
     * - Folio stays, like Settings on iPhone: taking Home away from inside Home is left to Android's Settings.
     * - An app that isn't available (mid-update, or in a paused work profile) waits until it is.
     * - [installed]: Android could describe the app in its profile. If it couldn't, there's nothing to offer.
     * - A system app ([system], ApplicationInfo.FLAG_SYSTEM) can't be removed. One that has been updated
     *   ([updatedSystem], FLAG_UPDATED_SYSTEM_APP) can have its updates removed, and Android's dialog says exactly that.
     */
    fun offer(isShortcut: Boolean, isFolio: Boolean, available: Boolean, installed: Boolean, system: Boolean, updatedSystem: Boolean): Boolean =
        !isShortcut && !isFolio && available && installed && (!system || updatedSystem)
}
