package com.mccal.folio.priv

import android.os.SystemClock
import java.util.concurrent.Executors

/**
 * Fold8Duo (WP-59, probe P-24): what the window manager does with rotation in the seconds after a fold or unfold, so
 * "an app open on the front screen comes up in the wrong orientation when I open the phone" can be read from the
 * owner's ordinary folds instead of staged ones. Runs in the shell-side engine; on every fold-state change it samples
 * `dumpsys window displays` a few times over three seconds and logs one line per sample through the engine's log
 * (→ `adb logcat -s FolioHinge`, "engine: rotation …"): each display's rotation, the last orientation requested and
 * by which window, the app in front, the rotation sensor's proposal, and the user rotation. Reads only; changes nothing.
 */
internal class RotationTrace(private val log: (String) -> Unit) {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "FolioRotationTrace").apply { isDaemon = true } }
    @Volatile private var episode = 0

    /** A new fold state (any thread). A newer state cuts the previous episode short. */
    fun onFoldState(state: String) {
        val mine = ++episode
        worker.execute {
            val started = SystemClock.uptimeMillis()
            for (at in SAMPLE_AT_MS) {
                val wait = started + at - SystemClock.uptimeMillis()
                if (wait > 0) Thread.sleep(wait)
                if (episode != mine) return@execute
                log("rotation $state +${at}ms: ${sample()}")
            }
        }
    }

    private fun sample(): String {
        val dump = runCatching {
            val process = ProcessBuilder("sh", "-c", "dumpsys window displays 2>&1").start()
            val text = process.inputStream.bufferedReader().readText()
            process.waitFor()
            text
        }.getOrElse { return "failed: ${it.javaClass.simpleName}" }
        val out = StringBuilder()
        var display = "?"
        var rotation = "?"; var orientation = "?"; var source = "?"; var app = "-"; var proposed = "?"; var user = "?"
        fun flush() {
            if (display != "?") out.append("d$display rot=$rotation ori=$orientation by=$source app=$app sensor=$proposed user=$user; ")
            rotation = "?"; orientation = "?"; source = "?"; app = "-"; proposed = "?"; user = "?"
        }
        for (raw in dump.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("Display: mDisplayId=") -> { flush(); display = line.removePrefix("Display: mDisplayId=").takeWhile { it.isDigit() } }
                line.startsWith("mRotation=") -> rotation = line.removePrefix("mRotation=").takeWhile { it.isDigit() }
                line.startsWith("mLastOrientation=") -> orientation = line.removePrefix("mLastOrientation=").takeWhile { it == '-' || it.isDigit() }
                line.startsWith("mLastOrientationSource=") -> source = line.removePrefix("mLastOrientationSource=").substringBefore('@').take(48)
                line.startsWith("mFocusedApp=") -> app = APP.find(line)?.groupValues?.get(1) ?: "none"
                line.startsWith("mUserRotationMode=") -> user = USER.find(line)?.groupValues?.get(1) ?: "?"
                line.startsWith("mProposedRotation=") -> proposed = line.removePrefix("mProposedRotation=").substringBefore(' ').ifEmpty { "?" } + proposed.removePrefix("?")
                line.startsWith("mEnabled=") -> proposed = if (line.removePrefix("mEnabled=").startsWith("true")) "?" else "?(off)"
                line.startsWith("mFoldState=") || line.contains(" mFoldState=") -> FOLD.find(line)?.groupValues?.get(1)?.let { state ->
                    val tag = "fold=$state "
                    if (!out.contains(tag)) out.append(tag)
                }
            }
        }
        flush()
        return out.toString().trim()
    }

    private companion object {
        val SAMPLE_AT_MS = longArrayOf(0, 250, 700, 1500, 3000)
        val APP = Regex("ActivityRecord\\{\\S+ u\\d+ (\\S+) ")
        val USER = Regex("mUserRotationMode=(\\S+ mUserRotation=\\S+)")
        val FOLD = Regex("mFoldState=(\\w+)")
    }
}
