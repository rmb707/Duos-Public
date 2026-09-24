package com.mccal.folio.priv

import android.content.Intent
import android.graphics.Rect
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.view.Choreographer
import android.view.SurfaceControl
import androidx.annotation.RequiresApi
import com.mccal.folio.morph.MorphPlanner
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Fold8Duo: an app's real window growing out of its icon (WP-53, phase 1b; SPEC §4.7, M-12).
 *
 * A third-party Home cannot animate the window it launches: Android hands it a start rect and plays its own zoom. The
 * shell user can — it holds `CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS`, the permission Pixel's own launcher animates
 * with. So the launch is made from here, as `am start` makes it, with ActivityOptions carrying a remote transition
 * this process owns. When the window manager starts the transition it hands this process the app window's leash, and
 * [Runner] moves, scales and crops that leash from the icon's rect to the whole screen on the spec's spring
 * (`open.spring`: 0.40 s, damping 0.85), corners opening from the icon's to the screen's, then tells the system it is
 * done. The app draws its first frame before the transition starts, so it is the app itself that grows — no card, no
 * snapshot, no hand-over.
 *
 * Every hidden class and method is found by name and shape at runtime (SPEC §4.3: never compile-time stubs); anything
 * missing is a named "no", and the caller launches the ordinary way. Whatever happens once the system has called
 * [Runner.startAnimation] — an exception, a stall — the transition is finished, on a watchdog if need be, because a
 * remote transition left unfinished freezes the screen until the window manager's own timeout.
 */
@RequiresApi(33)
internal class RemoteLaunch(private val log: (String) -> Unit) {
    /** How the last [launch] went, for logs and the probe. */
    @Volatile var route = "untried"; private set

    private val frames = HandlerThread("FolioLaunchFrames").apply { start() } /* a thread name */ // english-only
    private val handler = Handler(frames.looper)

    private class Api {
        val remoteTransition: Class<*> = Class.forName("android.window.IRemoteTransition")
        val stub: Class<*> = Class.forName("android.window.IRemoteTransition\$Stub")
        val descriptor: String = stub.getDeclaredField("DESCRIPTOR").also { it.isAccessible = true }.get(null) as String
        val txStart = code("TRANSACTION_startAnimation")
        val txMerge = code("TRANSACTION_mergeAnimation")
        val txConsumed = runCatching { code("TRANSACTION_onTransitionConsumed") }.getOrDefault(-1)
        val txTakeOver = runCatching { code("TRANSACTION_takeOverAnimation") }.getOrDefault(-1)
        val infoClass: Class<*> = Class.forName("android.window.TransitionInfo")
        val infoCreator = infoClass.getField("CREATOR").get(null) as android.os.Parcelable.Creator<*>
        val finishedStub: Class<*> = Class.forName("android.window.IRemoteTransitionFinishedCallback\$Stub")
        val asFinished: Method = finishedStub.getMethod("asInterface", IBinder::class.java)
        val remoteTransitionClass: Class<*> = Class.forName("android.window.RemoteTransition")
        val makeRemote: Method = android.app.ActivityOptions::class.java.getMethod("makeRemoteTransition", remoteTransitionClass)
        val setCornerRadius: Method? = runCatching {
            SurfaceControl.Transaction::class.java.getMethod("setCornerRadius", SurfaceControl::class.java, Float::class.javaPrimitiveType)
        }.getOrNull()
        val tasks: Any = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)!!
        val startActivityAsUser: Method = tasks.javaClass.methods.filter { it.name == "startActivityAsUser" }
            .maxByOrNull { it.parameterTypes.size } ?: error("no startActivityAsUser")

        private fun code(name: String): Int = stub.getDeclaredField(name).also { it.isAccessible = true }.getInt(null)
    }

    private val api: Api? = runCatching { Api() }
        .onFailure { log("real open unavailable (${it.javaClass.simpleName}: ${it.message}); apps open the ordinary way") }.getOrNull()

    /**
     * Starts [intent] for [userId] with the app's window growing out of [from] (screen px). "" = started (the animation
     * then runs by itself), anything else says why not and nothing was started.
     */
    fun launch(intent: Intent, userId: Int, from: Rect, screenRadius: Float): String {
        val api = api ?: return "hidden API not found".also { route = it }
        return runCatching {
            val runner = Runner(api, from, screenRadius)
            val remote = Proxy.newProxyInstance(api.remoteTransition.classLoader, arrayOf(api.remoteTransition)) { _, method, args ->
                when (method.name) {
                    "asBinder" -> runner
                    "equals" -> args?.get(0) === runner
                    "hashCode" -> runner.hashCode()
                    "toString" -> "FolioRemoteTransition"
                    else -> null
                }
            }
            val transition = newRemoteTransition(api, remote)
            val options = api.makeRemote.invoke(null, transition) as android.app.ActivityOptions
            val bundle = options.toBundle()
            val result = startAsUser(api, intent, bundle, userId)
            if (result < 0) return@runCatching "startActivityAsUser said $result".also { route = it }
            route = "started ($result), waiting for the transition"
            ""
        }.getOrElse { "launch failed: ${it.javaClass.simpleName}: ${it.cause?.message ?: it.message}".also { route = it; log(it) } }
    }

    private fun newRemoteTransition(api: Api, remote: Any): Any {
        // Prefer the fullest constructor (IRemoteTransition, IApplicationThread, String); the app thread may be null.
        val ctors = api.remoteTransitionClass.constructors.sortedByDescending { it.parameterTypes.size }
        for (ctor in ctors) {
            val args = ctor.parameterTypes.map { type ->
                when {
                    type.isAssignableFrom(api.remoteTransition) -> remote
                    type == String::class.java -> "Folio open morph"        /* a debug name */ // english-only
                    else -> null
                }
            }.toTypedArray()
            val made = runCatching { ctor.newInstance(*args) }.getOrNull()
            if (made != null) return made
        }
        error("no RemoteTransition constructor took our runner")
    }

    /** As `am start` does it: the shell is the caller, the package is the shell's, the options carry the transition. */
    private fun startAsUser(api: Api, intent: Intent, options: android.os.Bundle, userId: Int): Int {
        val m = api.startActivityAsUser
        val types = m.parameterTypes
        var firstString = true
        var lastInt = types.indexOfLast { it == Int::class.javaPrimitiveType }
        val args = types.mapIndexed { i, type ->
            when {
                type == Intent::class.java -> intent
                type == android.os.Bundle::class.java -> options
                type == String::class.java -> if (firstString) { firstString = false; SHELL_PACKAGE } else null
                type == Int::class.javaPrimitiveType -> if (i == lastInt) userId else 0
                type == Boolean::class.javaPrimitiveType -> false
                else -> null
            }
        }.toTypedArray()
        return m.invoke(api.tasks, *args) as Int
    }

    /**
     * The transition's animator. The window manager calls [onTransact] on a Binder thread with the app window's leash;
     * the frames are stepped on [frames] so the Binder call returns at once.
     */
    private inner class Runner(private val api: Api, private val from: Rect, private val screenRadius: Float) : Binder() {
        private var finished = false

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            when (code) {
                api.txStart -> {
                    data.enforceInterface(api.descriptor)
                    val token = data.readStrongBinder()
                    val info = data.readTypedObject(api.infoCreator)
                    val start = data.readTypedObject(SurfaceControl.Transaction.CREATOR)
                    val finish = data.readStrongBinder()
                    handler.post { animate(token, info, start, finish) }
                    return true
                }
                api.txMerge -> { log("real open: another transition asked to merge; not merged"); return true }
                api.txConsumed -> { data.enforceInterface(api.descriptor); log("real open: transition consumed (aborted=${runCatching { data.readStrongBinder(); data.readInt() != 0 }.getOrNull()})"); return true }
                api.txTakeOver -> { log("real open: take-over asked; not supported"); return true }
            }
            return super.onTransact(code, data, reply, flags)
        }

        private fun animate(token: IBinder?, info: Any?, start: SurfaceControl.Transaction?, finishBinder: IBinder?) {
            val startedAt = SystemClock.uptimeMillis()
            val finishCallback = finishBinder?.let { runCatching { api.asFinished.invoke(null, it) }.getOrNull() }
            fun finish(why: String) {
                if (finished) return
                finished = true
                log("real open: finished ($why) ${SystemClock.uptimeMillis() - startedAt} ms after the transition started")
                runCatching {
                    val method = finishCallback?.javaClass?.methods?.firstOrNull { it.name == "onTransitionFinished" }
                    method?.invoke(finishCallback, null, null)
                }.onFailure { log("real open: could not finish the transition (${it.cause?.message ?: it.message})") }
            }
            // Whatever happens below, the system must hear the end of this transition.
            handler.postDelayed({ finish("watchdog") }, WATCHDOG_MS)
            try {
                val change = openingChange(info) ?: run { start?.apply(); finish("no opening window in the transition"); return }
                val leash = change.leash
                val end = change.endBounds
                val width = end.width().coerceAtLeast(1); val height = end.height().coerceAtLeast(1)
                val icon = MorphPlanner.Box(from.left - end.left, from.top - end.top, from.right - end.left, from.bottom - end.top)
                log("real open: window ${end.toShortString()} from icon ${from.toShortString()}; ${changesDescription(info)}")
                val first = MorphPlanner.leashFrame(icon, width, height, screenRadius, 0f)
                start?.let { t -> place(t, leash, end, first); t.setAlpha(leash, 1f); t.setVisibility(leash, true); t.apply() }
                var last = 0L
                val step = object : Runnable {
                    override fun run() {
                        if (finished) return
                        val now = SystemClock.uptimeMillis()
                        val seconds = (now - startedAt) / 1000f
                        val p = MorphPlanner.spring(seconds, RESPONSE_S, DAMPING)
                        val done = seconds >= MAX_S || p >= 0.998f
                        val frame = MorphPlanner.leashFrame(icon, width, height, screenRadius, if (done) 1f else p)
                        SurfaceControl.Transaction().use { t ->
                            if (done) { t.setPosition(leash, end.left.toFloat(), end.top.toFloat()); t.setScale(leash, 1f, 1f); t.setCrop(leash, null)
                                api.setCornerRadius?.invoke(t, leash, 0f) }
                            else place(t, leash, end, frame)
                            t.apply()
                        }
                        if (done) { finish("landed"); return }
                        last = now
                        nextFrame(this)
                    }
                }
                nextFrame(step)
            } catch (t: Throwable) {
                log("real open: animation failed (${t.javaClass.simpleName}: ${t.message}); finishing at once")
                runCatching { start?.apply() }
                finish("failure")
            }
        }

        private fun nextFrame(step: Runnable) {
            val posted = runCatching { Choreographer.getInstance().postFrameCallback { step.run() }; true }.getOrDefault(false)
            if (!posted) handler.postDelayed(step, 8)
        }

        private fun place(t: SurfaceControl.Transaction, leash: SurfaceControl, end: Rect, f: MorphPlanner.LeashFrame) {
            t.setPosition(leash, end.left + f.x, end.top + f.y)
            t.setScale(leash, f.scale, f.scale)
            t.setCrop(leash, Rect(0, f.cropTop, end.width(), f.cropTop + f.cropHeight))
            api.setCornerRadius?.let { runCatching { it.invoke(t, leash, f.cornerRadius) } }
        }
    }

    private class Change(val leash: SurfaceControl, val endBounds: Rect)

    /** The window that is opening or coming to the front, with a task behind it: the app. Home's own change is left alone. */
    private fun openingChange(info: Any?): Change? {
        info ?: return null
        val changes = info.javaClass.getMethod("getChanges").invoke(info) as? List<*> ?: return null
        for (c in changes) {
            c ?: continue
            val mode = runCatching { c.javaClass.getMethod("getMode").invoke(c) as Int }.getOrNull() ?: continue
            if (mode != TRANSIT_OPEN && mode != TRANSIT_TO_FRONT) continue
            val task = runCatching { c.javaClass.getMethod("getTaskInfo").invoke(c) }.getOrNull() ?: continue
            val leash = runCatching { c.javaClass.getMethod("getLeash").invoke(c) as? SurfaceControl }.getOrNull() ?: continue
            val end = runCatching { c.javaClass.getMethod("getEndAbsBounds").invoke(c) as? Rect }.getOrNull() ?: continue
            if (end.isEmpty) continue
            return Change(leash, Rect(end)).also { log("real open: opening task ${runCatching { task.javaClass.getField("taskId").getInt(task) }.getOrNull()}") }
        }
        return null
    }

    private fun changesDescription(info: Any?): String = runCatching {
        val changes = info!!.javaClass.getMethod("getChanges").invoke(info) as List<*>
        changes.joinToString(" ") { c ->
            val mode = c!!.javaClass.getMethod("getMode").invoke(c)
            val task = runCatching { c.javaClass.getMethod("getTaskInfo").invoke(c) != null }.getOrDefault(false)
            "[mode=$mode task=$task]"
        }
    }.getOrDefault("?")

    private companion object {
        const val SHELL_PACKAGE = "com.android.shell"
        const val TRANSIT_OPEN = 1
        const val TRANSIT_TO_FRONT = 3
        /** SPEC open.spring. */
        const val RESPONSE_S = .40f
        const val DAMPING = .85f
        /** SPEC morph.maxMs: hard cap, then snap. */
        const val MAX_S = .6f
        const val WATCHDOG_MS = 1_500L
    }
}
