package com.mccal.folio.priv

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.IBinder
import android.view.Surface
import android.view.SurfaceControl

/**
 * Fold8Duo: the screen, mirrored into a Surface the app owns, so the fold effect can frost what is really under it when
 * that is somebody else's app (SPEC §4.6, WP-12).
 *
 * Only the shell user may do this without a MediaProjection prompt (`CAPTURE_VIDEO_OUTPUT`, docs/decisions.md D1), so it
 * lives in the Shizuku service. It is the technique scrcpy uses: a virtual display that mirrors display 0. SurfaceFlinger
 * composes every frame straight into the app's buffer on the GPU — nothing is copied and nothing crosses Binder per
 * frame — and the mirror follows the logical display across the fold, because display 0 is whichever panel is lit.
 *
 * Hidden APIs throughout, so each lookup is a cascade, says what it found, and fails into "no mirror": the effect then
 * draws its dark front without frost, which needs no pixels at all.
 */
internal class DisplayMirror(private val log: (String) -> Unit) {
    private var virtual: VirtualDisplay? = null
    private var legacy: IBinder? = null
    /** Which route worked last, for the record. */
    var route = "none"; private set

    /** "" when the mirror is running, else why it is not. */
    @Synchronized fun start(surface: Surface, width: Int, height: Int): String {
        stop()
        if (!surface.isValid || width <= 0 || height <= 0) return "invalid surface (${width}x$height)"
        val viaManager = runCatching { startViaDisplayManager(surface, width, height) }
        if (viaManager.isSuccess) { route = "DisplayManager.createVirtualDisplay"; return "" }
        val viaControl = runCatching { startViaSurfaceControl(surface, width, height) }
        if (viaControl.isSuccess) { route = "SurfaceControl.createDisplay"; return "" }
        route = "none"
        return "no mirror route: ${rootCause(viaManager.exceptionOrNull())}; ${rootCause(viaControl.exceptionOrNull())}"
    }

    @Synchronized fun stop() {
        virtual?.let { runCatching { it.release() } }; virtual = null
        legacy?.let { token -> runCatching { surfaceControl.getMethod("destroyDisplay", IBinder::class.java).invoke(null, token) } }; legacy = null
    }

    /** Android 11+: `DisplayManager.createVirtualDisplay(name, width, height, displayIdToMirror, surface)`, static and hidden. */
    private fun startViaDisplayManager(surface: Surface, width: Int, height: Int) {
        val create = DisplayManager::class.java.getMethod("createVirtualDisplay", String::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Surface::class.java)
        virtual = create.invoke(null, NAME, width, height, DEFAULT_DISPLAY, surface) as VirtualDisplay
    }

    /** Before Android 14: a bare SurfaceFlinger display pointed at the default display's layer stack. */
    private fun startViaSurfaceControl(surface: Surface, width: Int, height: Int) {
        val token = surfaceControl.getMethod("createDisplay", String::class.java, Boolean::class.javaPrimitiveType).invoke(null, NAME, false) as IBinder
        runCatching {
            val info = Class.forName("android.hardware.display.DisplayManagerGlobal").let { global ->
                global.getMethod("getDisplayInfo", Int::class.javaPrimitiveType).invoke(global.getMethod("getInstance").invoke(null), DEFAULT_DISPLAY)!!
            }
            val w = info.javaClass.getField("logicalWidth").getInt(info)
            val h = info.javaClass.getField("logicalHeight").getInt(info)
            val stack = info.javaClass.getField("layerStack").getInt(info)
            val rect = android.graphics.Rect::class.java
            surfaceControl.getMethod("openTransaction").invoke(null)
            try {
                surfaceControl.getMethod("setDisplaySurface", IBinder::class.java, Surface::class.java).invoke(null, token, surface)
                surfaceControl.getMethod("setDisplayProjection", IBinder::class.java, Int::class.javaPrimitiveType, rect, rect)
                    .invoke(null, token, 0, android.graphics.Rect(0, 0, w, h), android.graphics.Rect(0, 0, width, height))
                surfaceControl.getMethod("setDisplayLayerStack", IBinder::class.java, Int::class.javaPrimitiveType).invoke(null, token, stack)
            } finally { surfaceControl.getMethod("closeTransaction").invoke(null) }
        }.onFailure { runCatching { surfaceControl.getMethod("destroyDisplay", IBinder::class.java).invoke(null, token) }; throw it }
        legacy = token
    }

    /**
     * Keeps [layer] out of screenshots and mirrors (SurfaceFlinger then composes it on real panels only), so an overlay
     * drawn from the mirror never sees itself. "" when done, else why not.
     */
    fun exclude(layer: SurfaceControl): String = runCatching {
        if (!layer.isValid) return "the layer is gone"
        val transaction = SurfaceControl.Transaction()
        SurfaceControl.Transaction::class.java.getMethod("setSkipScreenshot", SurfaceControl::class.java, Boolean::class.javaPrimitiveType)
            .invoke(transaction, layer, true)
        transaction.apply()
        transaction.close()
        ""
    }.getOrElse { "setSkipScreenshot: ${rootCause(it)} " }.also {
        // This process's copy of the handle keeps the layer alive in SurfaceFlinger after the app has let go of it:
        // seen on the SM-F971U1 as one orphaned "SurfaceView[FolioFoldStage]" layer left behind per episode.
        runCatching { layer.release() }
    }.trim()

    private fun rootCause(t: Throwable?): String {
        var c = t ?: return "?"
        while (c.cause != null && c.cause !== c) c = c.cause!!
        return "${c.javaClass.simpleName}: ${c.message}"
    }

    private companion object {
        const val NAME = "fold8duo-mirror"
        const val DEFAULT_DISPLAY = 0
        val surfaceControl: Class<*> get() = SurfaceControl::class.java
    }
}
