package com.mccal.folio

import android.app.ApplicationExitInfo
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which reports earn a "Duos closed unexpectedly" on the next launch. A launcher sitting in the background is closed
 * by Android to free memory all the time; telling someone that was a crash would only alarm them.
 */
class WorthAskingTest {
    private val dir: File = Files.createTempDirectory("reports").toFile()
    private fun report(name: String, firstLine: String) = File(dir, name).apply { writeText("$firstLine\nWhen: now\n") }

    @Test fun `a crash and a freeze are worth asking about`() {
        assertTrue(Diagnostics.worthAsking(report("crash-1000.txt", "Duos crashed")))
        assertTrue(Diagnostics.worthAsking(report("exit-1001.txt", "Duos ${Diagnostics.label(ApplicationExitInfo.REASON_ANR)}")))
        assertTrue(Diagnostics.worthAsking(report("exit-1002.txt", "Duos ${Diagnostics.label(ApplicationExitInfo.REASON_CRASH_NATIVE)}")))
        assertTrue(Diagnostics.worthAsking(report("exit-1003.txt", "Folio ${Diagnostics.label(ApplicationExitInfo.REASON_ANR)}")))
    }

    @Test fun `being closed to free memory is not, and neither is a restart`() {
        assertFalse(Diagnostics.worthAsking(report("exit-1004.txt", "Duos ${Diagnostics.label(ApplicationExitInfo.REASON_LOW_MEMORY)}")))
        assertFalse(Diagnostics.worthAsking(report("restart-1005.txt", "Duos restarted")))
    }
}
