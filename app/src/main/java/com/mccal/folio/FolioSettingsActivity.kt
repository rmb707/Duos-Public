package com.mccal.folio

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Entry point Android's own screens use to open Folio's settings: the gear on Folio's Accessibility page,
 * long-pressing a Folio Quick Settings tile, and the live wallpaper's Settings button. It hands off to
 * Home, which opens the settings sheet, and closes itself.
 */
class FolioSettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_APPLICATION_PREFERENCES)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }
}
