package com.mccal.folio.priv

import android.hardware.HardwareBuffer

/**
 * Fold8Duo: the picture the system already keeps of an app that is in the background — the one Recents shows — so an
 * app that is opened again can grow out of its icon looking like itself (SPEC §4.7, WP-13/WP-31; probe P-10).
 *
 * iOS does exactly this: it shows the saved picture while the app wakes up. It is the right source here too, and the
 * display mirror is not: while the icon is still growing the app must stay hidden so Home can be seen around the card,
 * and a hidden app is not on the screen to be mirrored.
 *
 * Shell user only (`READ_FRAME_BUFFER`, `MANAGE_ACTIVITY_TASKS`). Hidden APIs whose shapes have moved between releases,
 * so every call is found by name and tried by shape, and any failure is simply "no picture": the card then shows the
 * icon on a plain ground, like Android's own splash screen.
 */
internal class TaskSnapshots(private val log: (String) -> Unit) {
    private val tasks: Any? = runCatching {
        Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
    }.onFailure { log("no task service (${it.javaClass.simpleName}: ${it.message}); apps open without their last picture") }.getOrNull()
    private var said = false

    /** Which route answered last, for the record. */
    var route = "untried"; private set

    /** The last picture of [packageName]'s task for [userId], or null. */
    fun of(packageName: String, userId: Int): HardwareBuffer? = runCatching {
        val service = tasks ?: return null
        val taskId = taskIdOf(service, packageName, userId) ?: run { route = "no recent task for $packageName"; return null }
        val snapshot = snapshotOf(service, taskId) ?: run { route = "task $taskId has no snapshot"; return null }
        if (secure(snapshot)) { route = "task $taskId: its picture holds protected content; not shown"; return null }
        val buffer = bufferOf(snapshot)
        route = if (buffer != null) "task $taskId ${buffer.width}x${buffer.height} via $bufferRoute" else "task $taskId: a snapshot, but no way to its buffer"
        buffer
    }.onFailure { if (!said) { said = true; log("task snapshots unavailable (${rootCause(it)})") }; route = "failed: ${rootCause(it)}" }.getOrNull()

    private fun taskIdOf(service: Any, packageName: String, userId: Int): Int? {
        val recent = service.javaClass.methods.first { it.name == "getRecentTasks" && it.parameterTypes.size == 3 }
            .invoke(service, 30, 0, userId) ?: return null
        val list = recent.javaClass.getMethod("getList").invoke(recent) as? List<*> ?: return null
        for (info in list) {
            info ?: continue
            val intent = info.javaClass.getField("baseIntent").get(info) as? android.content.Intent
            val real = runCatching { info.javaClass.getField("realActivity").get(info) as? android.content.ComponentName }.getOrNull()
            if (intent?.component?.packageName == packageName || real?.packageName == packageName) {
                val id = info.javaClass.getField("taskId").getInt(info)
                if (id >= 0) return id                      // -1: the task is only a memory, its picture is gone
            }
        }
        return null
    }

    private var bufferRoute = "?"

    /** SPEC I-7: a picture with protected content in it is never shown, whatever the system would let us read. */
    private fun secure(snapshot: Any): Boolean = listOf("containsSecureLayers", "hasProtectedContent").any { name ->
        runCatching { snapshot.javaClass.getMethod(name).invoke(snapshot) as? Boolean }.getOrNull() == true
    }

    /**
     * Measured on the SM-F971U1 (Android 17): `getHardwareBuffer()` answers null for a perfectly valid snapshot
     * (`isBufferValid()` true, 1248x1972); the supported way in is now `wrapToBitmap()`. The field is the last resort.
     */
    private fun bufferOf(snapshot: Any): HardwareBuffer? {
        (runCatching { snapshot.javaClass.getMethod("getHardwareBuffer").invoke(snapshot) as? HardwareBuffer }.getOrNull())
            ?.let { bufferRoute = "getHardwareBuffer"; return it }
        (runCatching { (snapshot.javaClass.getMethod("wrapToBitmap").invoke(snapshot) as? android.graphics.Bitmap)?.hardwareBuffer }.getOrNull())
            ?.let { bufferRoute = "wrapToBitmap"; return it }
        return runCatching {
            snapshot.javaClass.getDeclaredField("mSnapshot").apply { isAccessible = true }.get(snapshot) as? HardwareBuffer
        }.getOrNull()?.also { bufferRoute = "mSnapshot" }
    }

    /**
     * Where a task's picture lives has moved: `IActivityTaskManager.getTaskSnapshot(taskId, lowRes[, takeIfNeeded])` up
     * to Android 15; `android.window.TaskSnapshotManager.getTaskSnapshot(taskId, resolution)` on the SM-F971U1's
     * Android 17 (found by [discover], 2026-09-19). Newest first; the first that answers is remembered.
     */
    private fun snapshotOf(service: Any, taskId: Int): Any? {
        var last: Throwable? = null
        runCatching { viaManager(taskId) }.onSuccess { return it }.onFailure { last = it }
        for (method in service.javaClass.methods.filter { it.name == "getTaskSnapshot" }.sortedBy { it.parameterTypes.size }) {
            val args: Array<Any?> = when (method.parameterTypes.map { it.name }) {
                listOf("int", "boolean") -> arrayOf(taskId, false)                       // (taskId, isLowResolution)
                listOf("int", "boolean", "boolean") -> arrayOf(taskId, false, false)     // (…, takeSnapshotIfNeeded)
                else -> continue
            }
            runCatching { return method.invoke(service, *args) }.onFailure { last = it }
        }
        throw last ?: NoSuchMethodException("no getTaskSnapshot anywhere")
    }

    private fun viaManager(taskId: Int): Any? {
        val manager = Class.forName("android.window.TaskSnapshotManager")
        val instance = manager.methods.first { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.parameterTypes.isEmpty() && it.returnType == manager }.invoke(null)
        val get = manager.getMethod("getTaskSnapshot", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        // The second argument is a resolution preference whose constants are not ours to know: ask for each, best first.
        for (resolution in intArrayOf(1, 0, 2)) get.invoke(instance, taskId, resolution)?.let { return it }
        return null
    }

    /**
     * Probe P-10: every method with "napshot" in its name on the places a task's picture has lived, with its shape.
     * One long line, for the log, so the next Android that moves it can be answered from a logcat.
     */
    fun discover(): String = buildString {
        fun shapes(label: String, target: Class<*>?) {
            target ?: run { append("$label: absent; "); return }
            val found = target.methods.filter { "napshot" in it.name }.joinToString { m -> "${m.name}(${m.parameterTypes.joinToString(",") { it.simpleName }}):${m.returnType.simpleName}" }
            append("$label: ${found.ifEmpty { "none" }}; ")
        }
        shapes("IActivityTaskManager", tasks?.javaClass)
        shapes("IActivityManager", runCatching { Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)?.javaClass }.getOrNull())
        shapes("TaskSnapshotManager", runCatching { Class.forName("android.window.TaskSnapshotManager") }.getOrNull())
        shapes("ActivityTaskManager", runCatching { Class.forName("android.app.ActivityTaskManager") }.getOrNull())
        shapes("ActivityManagerWrapper", runCatching { Class.forName("com.android.systemui.shared.system.ActivityManagerWrapper") }.getOrNull())
    }

    private fun rootCause(t: Throwable): String {
        var c = t
        while (c.cause != null && c.cause !== c) c = c.cause!!
        return "${c.javaClass.simpleName}: ${c.message}"
    }
}
