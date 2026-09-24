package com.mccal.folio.priv

import android.content.ComponentName

/**
 * What is in front, and whether a call is up, asked of the system at the moment an opening starts. Only works as the
 * shell user (`MANAGE_ACTIVITY_TASKS`), which is who the engine runs as. Hidden APIs, so every lookup is by reflection,
 * is tried once, and degrades to "don't know" — which means "don't hold off", the behaviour before this existed.
 */
internal class ForegroundWatch(private val log: (String) -> Unit) {
    private val topActivity: (() -> ComponentName?)? = runCatching {
        val service = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)!!
        val focused = service.javaClass.getMethod("getFocusedRootTaskInfo")
        val read: () -> ComponentName? = {
            focused.invoke(service)?.let { info -> info.javaClass.getField("topActivity").get(info) as? ComponentName }
        }
        read()      // prove it once, here, so a failure is logged at start-up and not in the middle of an opening
        read
    }.onFailure { log("cannot see the app in front (${it.javaClass.simpleName}: ${it.message}); early light never holds off for a camera") }.getOrNull()

    private val audioMode: (() -> Int)? = runCatching {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "audio")
        val audio = Class.forName("android.media.IAudioService\$Stub").getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)!!
        val mode = audio.javaClass.getMethod("getMode")
        val read: () -> Int = { mode.invoke(audio) as Int }
        read()
        read
    }.onFailure { log("cannot see the call state (${it.javaClass.simpleName}: ${it.message}); early light never holds off for a call") }.getOrNull()

    val describe: String get() = "front=${if (topActivity != null) "ok" else "unavailable"} call=${if (audioMode != null) "ok" else "unavailable"}"

    /** A reason to leave this opening to One UI, or null. Never throws; a few milliseconds at most. */
    fun holdOff(): String? = runCatching {
        HoldOffPolicy.reason(topActivity?.invoke()?.packageName, audioMode?.invoke() ?: 0)
    }.getOrNull()
}
