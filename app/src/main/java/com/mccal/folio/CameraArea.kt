package com.mccal.folio

import android.graphics.Rect
import android.os.Build
import android.view.Display
import android.view.Surface
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView

/**
 * Under-display cameras that Android doesn't report as a cutout. Samsung keeps these only in its
 * window manager ("udcCutout" in `dumpsys window`), so Folio carries the known values per device.
 * Rects are in the display's natural (rotation 0) orientation, in pixels.
 */
internal object CameraArea {
    private data class Known(val modelPrefix: String, val width: Int, val height: Int, val camera: Rect)

    private val known = listOf(
        // Galaxy Z Fold8 (SM-F971*) inner screen: round camera near the top, ~76% across.
        Known("SM-F971", 2448, 1848, Rect(1823, 18, 1901, 96)),
    )

    /** The hidden camera on [display] in its current rotation, or null if none is known. */
    fun hiddenCamera(display: Display?): Rect? {
        display ?: return null
        val mode = display.mode
        val naturalW = mode.physicalWidth
        val naturalH = mode.physicalHeight
        val entry = known.firstOrNull { Build.MODEL.startsWith(it.modelPrefix) &&
            ((it.width == naturalW && it.height == naturalH) || (it.width == naturalH && it.height == naturalW)) } ?: return null
        return rotate(entry.camera, entry.width, entry.height, display.rotation)
    }

    /** Maps a rect from rotation 0 to the given display rotation. */
    internal fun rotate(r: Rect, w: Int, h: Int, rotation: Int): Rect = when (rotation) {
        Surface.ROTATION_90 -> Rect(r.top, w - r.right, r.bottom, w - r.left)
        Surface.ROTATION_180 -> Rect(w - r.right, h - r.bottom, w - r.left, h - r.top)
        Surface.ROTATION_270 -> Rect(h - r.bottom, r.left, h - r.top, r.right)
        else -> Rect(r)
    }
}

/** Insets that keep content clear of a known hidden camera (empty when none). */
@Composable
internal fun rememberHiddenCameraInsets(): WindowInsets {
    val view = LocalView.current
    val config = LocalConfiguration.current
    return remember(config.screenWidthDp, config.screenHeightDp, config.orientation) {
        val rect = CameraArea.hiddenCamera(view.display) ?: return@remember WindowInsets(0, 0, 0, 0)
        val size = view.display.mode
        val (w, h) = if (view.display.rotation % 2 == 0) size.physicalWidth to size.physicalHeight else size.physicalHeight to size.physicalWidth
        // Inset from whichever edge the camera is nearest.
        val toTop = rect.top; val toBottom = h - rect.bottom; val toLeft = rect.left; val toRight = w - rect.right
        when (minOf(toTop, toBottom, toLeft, toRight)) {
            toTop -> WindowInsets(top = rect.bottom)
            toBottom -> WindowInsets(bottom = h - rect.top)
            toLeft -> WindowInsets(left = rect.right)
            else -> WindowInsets(right = w - rect.left)
        }
    }
}
