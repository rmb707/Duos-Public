package com.mccal.folio

/**
 * Fold8Duo: which apps the App Library's Recently Added shows (WP-48). iPhone keeps an app there for a while after it
 * arrives, opened or not, so this goes by when Android first installed it. [NewApps] can't answer that: it only hears
 * of installs that finish while Folio runs, and forgets each app the first time it's opened. Its blue dot still shows on
 * the apps here that haven't been opened, since they're drawn by the list's own rows. Pure, so it runs as a JVM test;
 * LibraryList.kt asks Android for the install times.
 */
internal object RecentlyAdded {
    /** A week: the same time an app that hasn't been opened keeps its blue dot. */
    const val WINDOW_MS = 7L * 24 * 60 * 60 * 1000
    const val MAX = 8
    /** A clock set back a little (network time catching up) mustn't hide an app installed a moment ago. */
    const val SKEW_MS = 60L * 60 * 1000

    /**
     * The apps installed within [window] before [now], newest first, at most [max]; apps installed at the same moment
     * keep the order they came in. An app with no known install time is left out, and so is one "installed" more than
     * [SKEW_MS] in the future (a clock that was set back by days).
     */
    fun <T> pick(apps: List<T>, now: Long, installedAt: (T) -> Long?, window: Long = WINDOW_MS, max: Int = MAX): List<T> =
        apps.mapNotNull { app ->
            installedAt(app)?.takeIf { it > 0 && it - now <= SKEW_MS && now - it < window }?.let { app to it }
        }
            .sortedByDescending { it.second }
            .take(max.coerceAtLeast(0))
            .map { it.first }
}
