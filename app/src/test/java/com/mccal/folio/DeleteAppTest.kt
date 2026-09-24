package com.mccal.folio

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.os.Process
import android.os.UserHandle
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Delete App against Android's own classes (Robolectric, no phone): what it offers, and exactly what it asks Android. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeleteAppTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val icon = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
    private fun app(pkg: String, label: String = "Notes") = AppEntry("$pkg/.Main", label, icon)

    private fun installed(pkg: String, flags: Int) = shadowOf(context.getSystemService(LauncherApps::class.java))
        .addApplicationInfo(Process.myUserHandle(), pkg, ApplicationInfo().apply { packageName = pkg; this.flags = flags })

    @Test fun `offered for apps Android can uninstall, and only those`() {
        installed("org.example.notes", 0)
        installed("org.example.system", ApplicationInfo.FLAG_SYSTEM)
        installed("org.example.updated", ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)
        installed(context.packageName, 0)
        assertTrue(DeleteApp.canOffer(context, app("org.example.notes")))
        assertFalse(DeleteApp.canOffer(context, app("org.example.system")))
        assertTrue(DeleteApp.canOffer(context, app("org.example.updated")))
        assertFalse("not installed", DeleteApp.canOffer(context, app("org.example.gone")))
        assertFalse("Folio itself", DeleteApp.canOffer(context, app(context.packageName)))
        assertFalse("unavailable", DeleteApp.canOffer(context, app("org.example.notes").copy(available = false)))
        val shortcut = AppEntry("org.example.notes/${SHORTCUT_CLASS_PREFIX}compose", "New note", icon)
        assertFalse("a pinned shortcut", DeleteApp.canOffer(context, shortcut))
    }

    @Test fun `it opens Android's uninstall confirmation for that app in its own profile`() {
        val app = app("org.example.notes")
        DeleteApp.request(context, app)
        val intent = shadowOf(context).nextStartedActivity
        assertEquals(Intent.ACTION_DELETE, intent.action)
        assertEquals("package:org.example.notes", intent.dataString)
        assertEquals(app.user, intent.getParcelableExtra(Intent.EXTRA_USER, UserHandle::class.java))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertFalse("never for every user", intent.hasExtra("android.intent.extra.UNINSTALL_ALL_USERS"))
    }
}
