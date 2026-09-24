package com.mccal.folio

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.HardwareBuffer
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

/** One frame of the mirrored screen. It lives on the GPU and is only good until the next frame is drawn. */
internal class LiveFrame(val bitmap: Bitmap, val atMs: Long)

/**
 * Fold8Duo: a canvas above every app (SPEC §4.6).
 *
 * The fold effect on Home is a filter over Folio's own pixels, so it has nowhere to draw once another app is in front.
 * This is that somewhere: one full-screen accessibility-overlay window — the service is already running for the shade
 * gestures, and [EverywhereOverlay] already hangs the island from it — holding a SurfaceView that a [Scene] draws into.
 * The window only has a surface while a scene is showing, so between episodes it costs nothing.
 *
 * Rules it keeps whatever a scene does (SPEC §4.5): touch always passes through (I-4); nothing stays up past its
 * scene's limit (I-3); nothing is shown in safe mode.
 *
 * **Live frames.** A scene may ask for the screen underneath ([Scene.wantsLive]). That needs the Shizuku engine: it
 * mirrors the display into an ImageReader here ([FoldShizuku.startMirror]), and first marks this SurfaceView's layer
 * skip-screenshot, or the mirror would show the overlay to itself. The order is fixed — exclude, then mirror — so a
 * scene never gets a frame with itself in it. Without the engine a scene simply gets no frames and draws without them.
 */
internal class OverlayStage(private val service: AccessibilityService) {

    interface Scene {
        val name: String
        /** Size of the drawing surface relative to the screen. The effect is soft, so half is plenty; SurfaceFlinger scales it up. */
        val renderScale: Float get() = .5f
        /** Ask for [LiveFrame]s of what is under the overlay. */
        val wantsLive: Boolean get() = false
        /** The longest this scene may stay up, whatever happens. */
        val maxMs: Long
        /** Draw one frame onto a cleared canvas. False = nothing left to show; the stage takes the window down. */
        fun draw(canvas: Canvas, width: Int, height: Int, nowMs: Long, live: LiveFrame?): Boolean
        fun onGone(reason: String) = Unit
    }

    private val wm = service.getSystemService(WindowManager::class.java)
    private val displays = service.getSystemService(DisplayManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { Thread(it, "FolioStageIo").apply { isDaemon = true } }

    private var root: FrameLayout? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var view: SurfaceView? = null
    private var scene: Scene? = null
    private var looping = false
    @Volatile private var surfaceUp = false
    private var excluded = false
    /** Counts surfaces, so an answer about an old one is recognised as stale. */
    private var surfaceGeneration = 0
    /** Off only while [probe] measures what the mirror sees before and after, or a development demo wants to be in a screenshot. */
    private var autoExclude = true
    /** Development: the next scene's layer is left in screenshots (so it gets no live frames). Cleared when it goes. */
    var debugStayInScreenshots = false
    private val mirror = LiveMirror()
    private val deadMan = Runnable { hide("time limit") }

    val showing: Scene? get() = scene
    /** True once live frames can be trusted not to contain the overlay itself. */
    val liveReady: Boolean get() = mirror.running

    fun start() { current = WeakReference(this) }

    fun stop() {
        hide("service stopped")
        root?.let { runCatching { wm.removeView(it) } }
        root = null; view = null
        if (current.get() === this) current.clear()
    }

    fun show(next: Scene): Boolean {
        if (scene === next) return true
        if (SafeMode.active) return false
        scene?.let { it.onGone("replaced by ${next.name}") }
        if (!ensureWindow()) { scene = null; return false }
        scene = next
        if (!next.wantsLive) mirror.stop()
        root?.visibility = View.VISIBLE
        applySize()
        main.removeCallbacks(deadMan)
        main.postDelayed(deadMan, next.maxMs)
        if (!looping) { looping = true; Choreographer.getInstance().postFrameCallback(frames) }
        wantLive()
        return true
    }

    fun hide(reason: String) {
        val was = scene ?: return
        scene = null
        main.removeCallbacks(deadMan)
        mirror.stop()
        root?.visibility = View.GONE
        debugStayInScreenshots = false
        Log.i(TAG, "${was.name}: gone ($reason)")
        was.onGone(reason)
    }

    /** WP-56: a haptic through the stage's own view (a View honours Android's touch-feedback setting; no permission needed). */
    fun haptic(play: (View) -> Boolean) { (view ?: root)?.let { main.post { runCatching { play(it) } } } }

    /** The default display's size in pixels, whichever panel that is right now. */
    fun screenSize(): Point = Point().also { p ->
        @Suppress("DEPRECATION") displays.getDisplay(Display.DEFAULT_DISPLAY)?.getRealSize(p)
    }

    private fun ensureWindow(): Boolean {
        if (root != null) return true
        val surface = SurfaceView(service).apply {
            setZOrderOnTop(true)
            holder.setFormat(PixelFormat.TRANSLUCENT)
            holder.addCallback(surfaceCallback)
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applySize() }
        }
        val container = FrameLayout(service).apply {
            visibility = View.GONE
            addView(surface, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "FolioFoldStage"
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0                         // the whole panel, bars and cutout included
        }
        return runCatching { wm.addView(container, params); root = container; view = surface; windowParams = params; true }
            .onFailure { Log.w(TAG, "could not add the stage window", it) }.getOrDefault(false)
    }

    /**
     * Votes for the lit panel's fastest mode while a scene is up. Both panels are adaptive and idle well below 120 Hz:
     * measured on the SM-F971U1's lock screen, the same 3 s sweep ran at a clean 120 fps or at 70-90 with dozens of late
     * frames, depending only on the rate the panel happened to be at. A hinge moves too fast for 60 Hz to follow it.
     * The vote only counts while the window has a surface, so it ends with the scene.
     */
    private fun voteForFastestMode() {
        val params = windowParams ?: return
        val container = root ?: return
        val display = displays.getDisplay(Display.DEFAULT_DISPLAY) ?: return
        val now = display.mode
        val fastest = display.supportedModes
            .filter { it.physicalWidth == now.physicalWidth && it.physicalHeight == now.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return
        if (params.preferredDisplayModeId == fastest.modeId) return
        params.preferredDisplayModeId = fastest.modeId
        runCatching { wm.updateViewLayout(container, params) }
    }

    private fun applySize() {
        val s = scene ?: return
        voteForFastestMode()
        val size = screenSize()
        if (size.x <= 0 || size.y <= 0) return
        val w = ((size.x * s.renderScale).toInt() and 1.inv()).coerceAtLeast(2)
        val h = ((size.y * s.renderScale).toInt() and 1.inv()).coerceAtLeast(2)
        view?.holder?.setFixedSize(w, h)
    }

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            surfaceUp = true; excluded = false
            val generation = ++surfaceGeneration
            // Ask for the panel's full rate: the hinge moves fast, and a fold followed at 60 Hz reads as steps.
            runCatching { holder.surface.setFrameRate(120f, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE, Surface.CHANGE_FRAME_RATE_ALWAYS) }
            val layer = view?.surfaceControl ?: return
            if (!autoExclude || debugStayInScreenshots || !FoldShizuku.connected) return
            io.execute {
                val problem = FoldShizuku.excludeFromCapture(layer)
                main.post {
                    if (generation != surfaceGeneration || !surfaceUp) return@post
                    excluded = problem.isEmpty()
                    if (!excluded) Log.w(TAG, "the overlay could not be kept out of the mirror, so no live frames: $problem")
                    wantLive()
                }
            }
        }
        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            // A fold or a rotation: the mirror has to be the new panel's shape, or the picture would be letterboxed.
            if (mirror.active && (mirror.width != width || mirror.height != height)) { mirror.stop(); wantLive() }
        }
        override fun surfaceDestroyed(holder: SurfaceHolder) { surfaceUp = false; excluded = false; mirror.stop() }
    }

    private fun wantLive() {
        val s = scene ?: return
        if (!s.wantsLive || !surfaceUp || !excluded || mirror.active || !FoldShizuku.connected) return
        val frame = view?.holder?.surfaceFrame ?: return
        if (frame.width() > 1 && frame.height() > 1) mirror.start(frame.width(), frame.height())
    }

    private val frames = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val s = scene
            if (s != null && surfaceUp) drawFrame(s)
            if (scene != null) Choreographer.getInstance().postFrameCallback(this) else looping = false
        }
    }

    private fun drawFrame(s: Scene) {
        val holder = view?.holder ?: return
        val canvas = runCatching { holder.lockHardwareCanvas() }.getOrNull() ?: return
        var more = false
        try {
            canvas.drawColor(Color.TRANSPARENT, BlendMode.CLEAR)
            more = s.draw(canvas, canvas.width, canvas.height, SystemClock.uptimeMillis(), mirror.latest())
        } catch (t: Throwable) {
            Log.w(TAG, "${s.name}: drawing failed; taking the overlay down", t)
        } finally {
            runCatching { holder.unlockCanvasAndPost(canvas) }
        }
        if (!more && scene === s) hide("finished")
    }

    /**
     * The screen, arriving as GPU buffers. At most two are held: the one being drawn and the one before it, which is only
     * let go once its successor has been drawn too — the GPU may still be reading a buffer when the canvas is posted.
     */
    private inner class LiveMirror {
        private var reader: ImageReader? = null
        private var shown: Image? = null
        private var shownBitmap: Bitmap? = null
        private var shownAt = 0L
        private var previous: Image? = null
        private var ticket = 0
        private var starting = false
        var running = false; private set
        var width = 0; private set
        var height = 0; private set
        val active get() = running || starting

        fun start(w: Int, h: Int) {
            if (active) return
            val mine = ++ticket
            val askedAt = SystemClock.uptimeMillis()
            val created = runCatching { ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 4, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE) }
                .onFailure { Log.w(TAG, "no image reader for the mirror", it) }.getOrNull() ?: return
            reader = created; width = w; height = h; starting = true
            var first = true
            created.setOnImageAvailableListener({
                if (first) { first = false; Log.i(TAG, "mirror: first frame ${SystemClock.uptimeMillis() - askedAt} ms after asking") }
            }, main)
            io.execute {
                val problem = FoldShizuku.startMirror(created.surface, w, h)
                main.post {
                    if (mine != ticket) { if (problem.isEmpty()) io.execute { FoldShizuku.stopMirror() }; return@post }
                    starting = false
                    running = problem.isEmpty()
                    if (running) Log.i(TAG, "mirror ${w}x$h running, ${SystemClock.uptimeMillis() - askedAt} ms after asking")
                    else { Log.w(TAG, "no mirror, so no frost over apps: $problem"); release() }
                }
            }
        }

        fun latest(): LiveFrame? {
            val from = reader ?: return null
            if (!running) return null
            val next = runCatching { from.acquireLatestImage() }.getOrNull()
            if (next != null) {
                val buffer = next.hardwareBuffer
                val bitmap = buffer?.let { runCatching { Bitmap.wrapHardwareBuffer(it, ColorSpace.get(ColorSpace.Named.SRGB)) }.getOrNull() }
                buffer?.close()
                if (bitmap == null) next.close()
                else {
                    previous?.close()
                    previous = shown
                    shown = next; shownBitmap = bitmap; shownAt = SystemClock.uptimeMillis()
                }
            }
            return shownBitmap?.let { LiveFrame(it, shownAt) }
        }

        fun stop() {
            if (!active && reader == null) return
            ticket++
            val wasUp = running || starting
            running = false; starting = false
            val closing = reader
            val images = listOfNotNull(shown, previous)
            reader = null; shown = null; shownBitmap = null; previous = null; width = 0; height = 0
            // Stop the producer before its consumer goes away, in order, off the main thread.
            io.execute {
                if (wasUp) FoldShizuku.stopMirror()
                main.post { images.forEach { runCatching { it.close() } }; runCatching { closing?.close() } }
            }
        }

        private fun release() {
            listOfNotNull(shown, previous).forEach { runCatching { it.close() } }
            runCatching { reader?.close() }
            reader = null; shown = null; shownBitmap = null; previous = null; width = 0; height = 0
        }
    }

    /** Development: saves what the overlay itself has just drawn (with its alpha) as a PNG under the app's external files. */
    fun capture(tag: String) {
        val surface = view ?: return
        val frame = surface.holder.surfaceFrame
        if (!surfaceUp || frame.width() < 2 || frame.height() < 2) return
        val bitmap = Bitmap.createBitmap(frame.width(), frame.height(), Bitmap.Config.ARGB_8888)
        runCatching {
            android.view.PixelCopy.request(surface, bitmap, { result ->
                io.execute {
                    runCatching {
                        val dir = java.io.File(service.getExternalFilesDir(null), "stage").apply { mkdirs() }
                        val file = java.io.File(dir, "$tag.png")
                        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        Log.i(TAG, "capture: $tag -> ${file.absolutePath} (${bitmap.width}x${bitmap.height}, PixelCopy result $result)")
                    }.onFailure { Log.w(TAG, "capture: $tag failed", it) }
                }
            }, main)
        }.onFailure { Log.w(TAG, "capture: $tag could not start", it) }
    }

    // ------------------------------------------------------------------------------------------------ the probe

    /**
     * WP-12's probe, runnable with nobody holding the phone: does the mirror exist on this build, how long until its
     * first frame, and does marking the overlay skip-screenshot really keep it out? It fills the screen with one
     * colour, mirrors, counts that colour, excludes the layer, mirrors again, and counts again. Development builds.
     */
    fun probe(report: (String) -> Unit) {
        if (scene != null) { report("FAIL the stage is busy with ${scene?.name}"); return }
        if (!FoldShizuku.connected) { report("NOT RUN the Shizuku fold engine is not connected (${FoldShizuku.status})"); return }
        autoExclude = false
        val flood = object : Scene {
            override val name = "probe"
            override val maxMs = 12_000L
            override fun draw(canvas: Canvas, width: Int, height: Int, nowMs: Long, live: LiveFrame?): Boolean { canvas.drawColor(PROBE_COLOR); return true }
        }
        if (!show(flood)) { autoExclude = true; report("FAIL the stage window could not be shown"); return }
        io.execute {
            val lines = mutableListOf<String>()
            try {
                val deadline = SystemClock.uptimeMillis() + 3_000
                while (!surfaceUp && SystemClock.uptimeMillis() < deadline) Thread.sleep(20)
                if (!surfaceUp) { lines += "FAIL the stage surface never came up"; return@execute }
                Thread.sleep(250)                                   // a few frames of the flood on screen
                val size = screenSize()
                val w = (size.x / 4) and 1.inv(); val h = (size.y / 4) and 1.inv()
                val before = measure(w, h)
                lines += "mirror with the overlay NOT excluded: $before"
                val layer = view?.surfaceControl
                val problem = if (layer == null) "no layer" else FoldShizuku.excludeFromCapture(layer)
                lines += "excludeFromCapture: ${problem.ifEmpty { "ok" }}"
                Thread.sleep(150)
                val after = measure(w, h)
                lines += "mirror with the overlay excluded:     $after"
                val verdict = when {
                    before.error != null -> "FAIL no mirror: ${before.error}"
                    before.share < .9f -> "INCONCLUSIVE the flood was not in the first mirror (${before.percent}); nothing to exclude"
                    problem.isNotEmpty() -> "FAIL the layer could not be excluded: $problem"
                    after.error != null -> "FAIL second mirror: ${after.error}"
                    after.share > .02f -> "FAIL the excluded overlay is still in the mirror (${after.percent})"
                    else -> "PASS mirror works (first frame ${before.firstFrameMs} ms / ${after.firstFrameMs} ms) and skip-screenshot keeps the overlay out (${before.percent} -> ${after.percent})"
                }
                lines += verdict
                lines += "service: ${FoldShizuku.describeNow()}"
            } catch (t: Throwable) {
                lines += "FAIL ${t.javaClass.simpleName}: ${t.message}"
            } finally {
                FoldShizuku.stopMirror()
                main.post { autoExclude = true; hide("probe done"); lines.forEach(report) }
            }
        }
    }

    private class Measured(val share: Float, val firstFrameMs: Long, val error: String?) {
        val percent get() = "${"%.1f".format(share * 100)}% flood"
        override fun toString() = error ?: "$percent, first frame after $firstFrameMs ms"
    }

    /** Mirrors into a CPU-readable reader and reports how much of the first frame is the probe's colour. Blocking. */
    private fun measure(w: Int, h: Int): Measured {
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        try {
            val askedAt = SystemClock.uptimeMillis()
            val problem = FoldShizuku.startMirror(reader.surface, w, h)
            if (problem.isNotEmpty()) return Measured(0f, 0, problem)
            var image: Image? = null
            while (image == null && SystemClock.uptimeMillis() - askedAt < 2_000) { image = runCatching { reader.acquireLatestImage() }.getOrNull(); if (image == null) Thread.sleep(8) }
            val firstFrameMs = SystemClock.uptimeMillis() - askedAt
            image ?: return Measured(0f, firstFrameMs, "no frame within 2 s")
            val plane = image.planes[0]
            val pixels = plane.buffer
            var hits = 0; var seen = 0
            var y = 0
            while (y < h) {
                var x = 0
                while (x < w) {
                    val at = y * plane.rowStride + x * plane.pixelStride
                    val r = pixels.get(at).toInt() and 0xFF; val g = pixels.get(at + 1).toInt() and 0xFF; val b = pixels.get(at + 2).toInt() and 0xFF
                    if (r > 200 && g < 70 && b > 200) hits++
                    seen++
                    x += 8
                }
                y += 8
            }
            image.close()
            return Measured(if (seen == 0) 0f else hits.toFloat() / seen, firstFrameMs, null)
        } finally {
            FoldShizuku.stopMirror()
            Thread.sleep(60)
            runCatching { reader.close() }
        }
    }

    companion object {
        const val TAG = "FolioStage"
        private const val PROBE_COLOR = 0xFFFF00FF.toInt()
        private var current = WeakReference<OverlayStage>(null)
        /** The stage, while the accessibility service is running. */
        fun get(): OverlayStage? = current.get()
    }
}
