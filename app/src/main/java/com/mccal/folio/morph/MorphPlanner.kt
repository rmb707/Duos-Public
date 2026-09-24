package com.mccal.folio.morph

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Fold8Duo: the pure half of the iOS-style app open and close (IconMorph, SPEC §4.7). No android.* here, so every
 * decision about where a window lands or starts runs as a plain JVM test; [com.mccal.folio.IconMorph] is the Android half.
 */
internal object MorphPlanner {

    /** A rect in pixels, right and bottom exclusive, like android.graphics.Rect. */
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
        val isEmpty: Boolean get() = width <= 0 || height <= 0
        fun offset(dx: Int, dy: Int) = Box(left + dx, top + dy, right + dx, bottom + dy)
        /** The largest move of any edge. */
        fun distanceTo(other: Box) = maxOf(abs(left - other.left), abs(top - other.top), abs(right - other.right), abs(bottom - other.bottom))
    }

    /** How far from square a trusted icon may be. Folio's tiles are square; a page sliding away clips them thin. */
    const val SQUARE_TOLERANCE = .2f

    /** The Home page shown after a swipe home: where the pager rests if that is a Home page, else the last Home page. */
    fun pageAfterReturn(settledPage: Int, lastHomePage: Int, homePages: Int): Int =
        if (settledPage in 0 until homePages) settledPage else lastHomePage.coerceIn(0, (homePages - 1).coerceAtLeast(0))

    /**
     * The tile a closing app should fly into on [page]: its own icon in the dock or on that page, else the folder holding
     * it when that folder is in the dock or on that page. Null when none of them is on screen: flying into the wrong icon
     * is worse than the system's own animation.
     */
    fun tileFor(appId: String, dock: List<String?>, slots: List<String?>, folders: Map<String, List<String>>, page: Int, cellsPerPage: Int): String? {
        fun shown(id: String): Boolean {
            if (id in dock) return true
            val index = slots.indexOf(id)
            return index >= 0 && index / cellsPerPage == page
        }
        if (shown(appId)) return appId
        return folders.entries.firstOrNull { appId in it.value && shown(it.key) }?.key
    }

    /**
     * Whether a rect the launcher last laid out is a whole icon inside the window. A page sliding away clips its icons
     * thin before they go, and one from a page that is gone can lie anywhere, so it must also sit inside the window.
     */
    fun wholeIcon(rect: Box, windowWidth: Int, windowHeight: Int, minSide: Int, maxSide: Int): Boolean {
        if (rect.isEmpty || windowWidth <= 0 || windowHeight <= 0) return false
        if (rect.left < 0 || rect.top < 0 || rect.right > windowWidth || rect.bottom > windowHeight) return false
        val w = rect.width
        val h = rect.height
        if (minOf(w, h) < minSide || maxOf(w, h) > maxSide) return false
        return abs(w - h) <= maxOf(w, h) * SQUARE_TOLERANCE
    }

    /**
     * Whether an icon has really moved since its rect was last sent. The system restarts its spring toward every new
     * rect, so a wobble must not count: on the SM-F971U1 Home reported the same 173 px tile 3 px apart on alternate
     * layout passes while it came back (1494 ↔ 1497), and every swipe home restarted the landing three or four times
     * in its first 90 ms. A page sliding back under the finger moves an icon by tens of pixels, which this still follows.
     */
    fun realMove(sent: Box, now: Box, density: Float): Boolean =
        sent.distanceTo(now) >= maxOf(MOVE_DP * density, minOf(sent.width, sent.height) / 8f)

    private const val MOVE_DP = 6f

    /** Floating-point rect, for a card on its way between an icon and the whole screen. */
    data class BoxF(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
    }

    /**
     * A spring let go at 0 and settling on 1, [seconds] later. [response] is how long one swing would take undamped and
     * [damping] how quickly it dies (1 = no overshoot): iOS's two numbers, so a curve can be matched by eye against it.
     */
    fun spring(seconds: Float, response: Float, damping: Float): Float {
        if (seconds <= 0f) return 0f
        val w = (2.0 * Math.PI / response.coerceAtLeast(.05f)).toFloat()
        val z = damping.coerceIn(.05f, .999f)
        val wd = w * kotlin.math.sqrt(1f - z * z)
        val decay = kotlin.math.exp(-z * w * seconds)
        return 1f - decay * (kotlin.math.cos(wd * seconds) + (z * w / wd) * kotlin.math.sin(wd * seconds))
    }

    /** The card [progress] of the way from [icon] to the whole [width] × [height] screen. */
    fun card(icon: Box, width: Int, height: Int, progress: Float): BoxF {
        val p = progress.coerceIn(0f, 1f)
        fun mix(a: Int, b: Int) = a + (b - a) * p
        return BoxF(mix(icon.left, 0), mix(icon.top, 0), mix(icon.right, width), mix(icon.bottom, height))
    }

    /** An icon's corners (iOS: 22.5% of its side) opening out to the screen's own. */
    fun cornerRadius(iconSide: Float, screenRadius: Float, progress: Float): Float =
        (.225f * iconSide).let { it + (screenRadius - it) * progress.coerceIn(0f, 1f) }

    /**
     * One frame of a real window growing out of its icon (WP-53: the shell's remote transition moves the window's own
     * surface). The window is scaled so its width fills the card and cropped, centred, to the card's height, so the
     * app is seen at true proportions from the first frame — iOS's zoom — and the corners open with the card.
     * [x], [y] and [scale] place the window's surface (its origin, before the crop) relative to its final position;
     * [cropTop] and [cropHeight] are in the window's own pixels; [cornerRadius] too (the surface is scaled after).
     */
    data class LeashFrame(val x: Float, val y: Float, val scale: Float, val cropTop: Int, val cropHeight: Int, val cornerRadius: Float)

    fun leashFrame(icon: Box, windowWidth: Int, windowHeight: Int, screenRadius: Float, progress: Float): LeashFrame {
        val p = progress.coerceIn(0f, 1f)
        if (windowWidth <= 0 || windowHeight <= 0 || icon.isEmpty || p >= 1f) return LeashFrame(0f, 0f, 1f, 0, windowHeight.coerceAtLeast(1), screenRadius * p)
        val card = card(icon, windowWidth, windowHeight, p)
        val scale = (card.width / windowWidth).coerceAtLeast(1e-3f)
        val cropHeight = (card.height / scale).roundToInt().coerceIn(1, windowHeight)
        val cropTop = ((windowHeight - cropHeight) / 2f).roundToInt().coerceIn(0, windowHeight - cropHeight)
        val radius = cornerRadius(minOf(icon.width, icon.height).toFloat(), screenRadius, p)
        return LeashFrame(card.left, card.top - cropTop * scale, scale, cropTop, cropHeight, radius / scale)
    }

    /** The icon's picture is gone a quarter of the way up, so the app is never seen squeezed into icon size. */
    fun iconAlpha(progress: Float): Float = 1f - (progress / .25f).coerceIn(0f, 1f)

    /**
     * Where the system's scale-up should start so the app grows evenly: the window's own shape, as large as fits inside
     * the icon, centred on it. Handing over the icon's square instead squashes the app on the way up, worst on the cover
     * screen, which is more than twice as tall as it is wide.
     */
    fun evenScaleStart(icon: Box, windowWidth: Int, windowHeight: Int): Box {
        if (icon.isEmpty || windowWidth <= 0 || windowHeight <= 0) return icon
        val aspect = windowWidth.toFloat() / windowHeight
        var w = icon.width.toFloat()
        var h = w / aspect
        if (h > icon.height) { h = icon.height.toFloat(); w = h * aspect }
        val left = ((icon.left + icon.right) / 2f - w / 2f).roundToInt()
        val top = ((icon.top + icon.bottom) / 2f - h / 2f).roundToInt()
        return Box(left, top, left + w.roundToInt().coerceAtLeast(1), top + h.roundToInt().coerceAtLeast(1))
    }
}
