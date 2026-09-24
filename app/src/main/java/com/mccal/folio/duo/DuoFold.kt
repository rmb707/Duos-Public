package com.mccal.folio.duo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mccal.folio.CardNote
import com.mccal.folio.FoldGeometry
import com.mccal.folio.IosSegmented
import com.mccal.folio.LauncherModel
import com.mccal.folio.LauncherState
import com.mccal.folio.R
import com.mccal.folio.SettingsSwitch

/**
 * Fold8Duo: the fold animation is a choice (Settings › Fold & Displays › Fold animation).
 *
 * [FoldStyle.SWEEP] is the look the owner accepted on 2026-09-19: one soft dark front crossing the whole screen, so the
 * panels always swap under dark (DuoShader in FoldTransition.kt, VeilPainter in FoldOverlay.kt).
 *
 * [FoldStyle.DUO] is the iPhone Duo's own look, as far as one lit panel at a time allows: the picture stays where it
 * is, the half that moves is a pane of glass showing it in perspective, blurred and darker the further it is from
 * flat, and comes into focus as the phone opens; a still of the outgoing panel carries the picture across the swap.
 * The numbers and the reasoning are in [DuoPaneModel] and [DuoTiming]; this file is the two renderers of that model —
 * [DuoPaneShader] for Home (a RenderEffect over Folio's own pixels) and [DuoPanePainter] for the overlay stage over
 * other apps (a Paint over the mirrored screen, transparent wherever the effect has nothing to say) — and the setting.
 */
internal enum class FoldStyle { SWEEP, DUO }

/**
 * The chosen style. Kept in the `folio` prefs like the other Fold8Duo settings, so the over-app overlay (another
 * component, with no Compose state) reads the same value ([read]); Home and Settings observe [current].
 */
internal object FoldStyles {
    private const val PREFS = "folio"
    private const val KEY = "fold8duo.foldStyle"
    val current = mutableStateOf(FoldStyle.SWEEP)
    private var loaded = false

    fun load(context: Context): FoldStyle {
        if (!loaded) { loaded = true; current.value = read(context) }
        return current.value
    }

    /** The saved choice, for code with no Compose state. */
    fun read(context: Context): FoldStyle =
        runCatching { FoldStyle.valueOf(context.getSharedPreferences(PREFS, 0).getString(KEY, null) ?: "") }.getOrDefault(FoldStyle.SWEEP)

    fun set(context: Context, style: FoldStyle) {
        current.value = style; loaded = true
        context.getSharedPreferences(PREFS, 0).edit().putString(KEY, style.name).apply()
    }
}

/**
 * What the iPhone Duo renderers draw for one frame, from `FoldTimeline.duoFrame` (FoldTransition.kt): the moving
 * pane's tilt from flat, a whole-screen darkness around the panel swap, and how much of the position-lock still shows.
 */
internal data class DuoFrame(val tiltDeg: Float, val dusk: Float, val still: Float) {
    val idle: Boolean get() = tiltDeg <= 0f && dusk <= 0f && still <= 0f
    companion object { val NONE = DuoFrame(0f, 0f, 0f) }
}

/** The pane's place on a canvas: where its hinge is along the fold axis, which side of it the pane lies on, its length. */
internal data class DuoPane(val axisHorizontal: Boolean, val hingePx: Float, val paneAfterHinge: Boolean, val lengthPx: Float) {
    companion object {
        /** The moving half of the inner screen, or the whole cover hinged on its hinge-side edge. */
        fun of(geometry: FoldGeometry, cover: Boolean, width: Float, height: Float): DuoPane {
            val extent = if (geometry.horizontal) height else width
            return if (cover) DuoPane(geometry.horizontal, if (geometry.movingAfterHinge) extent else 0f, !geometry.movingAfterHinge, extent)
            else DuoPane(geometry.horizontal, geometry.hingePx, geometry.movingAfterHinge,
                if (geometry.movingAfterHinge) extent - geometry.hingePx else geometry.hingePx)
        }
    }
}

/** Settings › Fold & Displays › Fold animation: the style, and upstream's Screenshot morph switch for the sweep. */
@Composable
internal fun FoldStyleChoice(state: LauncherState, model: LauncherModel) {
    val context = LocalContext.current
    FoldStyles.load(context)
    val style = FoldStyles.current.value
    IosSegmented(listOf(FoldStyle.SWEEP to stringResource(R.string.fold8_style_sweep), FoldStyle.DUO to stringResource(R.string.fold8_style_duo)),
        style, { FoldStyles.set(context, it) }, Modifier.padding(vertical = 6.dp), tag = "fold-style")
    CardNote(stringResource(if (style == FoldStyle.DUO) R.string.fold8_style_duo_note else R.string.fold8_style_sweep_note))
    if (style == FoldStyle.SWEEP) {
        SettingsSwitch(stringResource(R.string.screenshot_morph), state.foldSnapshot, model::setFoldSnapshot, "fold-snapshot-switch")
        if (state.foldSnapshot) CardNote(stringResource(R.string.takes_a_quick_in_memory_snapshot_of_foli))
    }
}

/** Sets the pane uniforms shared by both shaders. [pxPerMm]: this canvas's pixels per mm of screen. */
@RequiresApi(33)
private fun RuntimeShader.setPane(width: Float, height: Float, pane: DuoPane, frame: DuoFrame, pxPerMm: Float, intensity: Float) {
    val tilt = frame.tiltDeg.coerceIn(0f, 180f)
    val gain = intensity.coerceIn(.3f, 1.5f)
    val paneLen = pane.lengthPx.coerceAtLeast(1f)
    setFloatUniform("size", width, height)
    setFloatUniform("axis", if (pane.axisHorizontal) 1f else 0f)
    setFloatUniform("hingePos", pane.hingePx)
    setFloatUniform("paneSide", if (pane.paneAfterHinge) 1f else -1f)
    setFloatUniform("paneLen", paneLen)
    setFloatUniform("tilt", DuoPaneModel.radians(tilt))
    setFloatUniform("projTilt", DuoPaneModel.radians(tilt.coerceAtMost(DuoPaneModel.PROJECTION_MAX_TILT_DEG)))
    setFloatUniform("eyeDist", DuoPaneModel.VIEW_DISTANCE_MM * pxPerMm)
    // The film's curves (DuoPaneModel): the free edge's blur radius and the mid-pane darkness for this tilt; the
    // shader spreads them across the pane.
    setFloatUniform("blurEdge", DuoPaneModel.blurRadius(paneLen, tilt, 1f, gain))
    setFloatUniform("blurAtHinge", DuoPaneModel.BLUR_AT_HINGE)
    setFloatUniform("darkMid", DuoPaneModel.darkMid(tilt, gain))
    setFloatUniform("steepDark", DuoPaneModel.steepDark(tilt))
    setFloatUniform("darkCap", DuoPaneModel.DARK_CAP)
    setFloatUniform("dusk", frame.dusk.coerceIn(0f, 1f))
    setFloatUniform("paneAlpha", DuoPaneModel.paneAlpha(tilt))
}

/**
 * A runtime shader that may not compile on this device's driver: then it is null, logged once, and the caller draws
 * without it. Home and the accessibility service construct these; neither may go down over a shader.
 */
private fun runtimeShader(what: String, source: String): RuntimeShader? =
    if (Build.VERSION.SDK_INT < 33) null
    else runCatching { RuntimeShader(source) }.onFailure { android.util.Log.w(TAG, "$what: shader did not compile; drawing without it", it) }.getOrNull()

private const val TAG = "FolioDuo"

/** The Duo pane over Home's own pixels: a RenderEffect on the layer that holds the live UI and the still. */
@RequiresApi(33)
internal class DuoPaneShader {
    private val shader = runtimeShader("home pane", HOME_SOURCE)

    /**
     * [overlay]: drawn at half size over the sharp screen (FoldSmoothness) — output only what the pane and the dusk change.
     * Null when the shader is unavailable: the caller then shows the screen as it is.
     */
    fun effect(width: Float, height: Float, frame: DuoFrame, cover: Boolean, geometry: FoldGeometry, pxPerMm: Float,
        intensity: Float, overlay: Boolean = false): androidx.compose.ui.graphics.RenderEffect? {
        val s = shader ?: return null
        s.setPane(width, height, DuoPane.of(geometry, cover, width, height), frame, pxPerMm, intensity)
        s.setFloatUniform("overlay", if (overlay) 1f else 0f)
        return RenderEffect.createRuntimeShaderEffect(s, "content").asComposeRenderEffect()
    }
}

/**
 * The Duo pane over another app, on the overlay stage. With a mirror frame it is the whole look (perspective, blur,
 * dark, the still, the dusk) over the mirrored pixels, transparent wherever the effect has nothing to say, so the real
 * app shows through sharp and current. Without one it is the dark, the still and the dusk alone.
 */
internal class DuoPanePainter {
    private val gpu = if (Build.VERSION.SDK_INT >= 33) Gpu.create() else null
    private val plain = Paint()
    private val stillPaint = Paint().apply { isFilterBitmap = true }

    /**
     * [live]: the mirrored screen, or null. [still] and [stillRect]: the position-lock still and where it lies on this
     * canvas, 1:1 (it may hang off the edge), or null.
     */
    fun draw(canvas: Canvas, width: Int, height: Int, frame: DuoFrame, cover: Boolean, geometry: FoldGeometry, pxPerMm: Float,
        intensity: Float, live: Bitmap?, still: Bitmap?, stillRect: RectF?) {
        if (width <= 0 || height <= 0 || frame.idle) return
        if (gpu != null && Build.VERSION.SDK_INT >= 33) { gpu.draw(canvas, width, height, frame, cover, geometry, pxPerMm, intensity, live, still, stillRect); return }
        // Android 12: no runtime shaders. The still, the dark along the pane, and the dusk; no blur, no perspective.
        if (still != null && stillRect != null && frame.still > 0f) {
            stillPaint.alpha = (frame.still * 255).toInt().coerceIn(0, 255)
            canvas.drawBitmap(still, null, stillRect, stillPaint)
        }
        val pane = DuoPane.of(geometry, cover, width.toFloat(), height.toFloat())
        if (frame.tiltDeg > 0f) {
            val stops = FloatArray(STOPS) { it / (STOPS - 1f) }
            val colors = IntArray(STOPS) { i ->
                Color.argb((DuoPaneModel.dark(frame.tiltDeg, stops[i], intensity) * 255).toInt().coerceIn(0, 255), 0, 0, 0)
            }
            val from = pane.hingePx
            val to = pane.hingePx + if (pane.paneAfterHinge) pane.lengthPx else -pane.lengthPx
            plain.shader = if (pane.axisHorizontal) LinearGradient(0f, from, 0f, to, colors, stops, Shader.TileMode.CLAMP)
                else LinearGradient(from, 0f, to, 0f, colors, stops, Shader.TileMode.CLAMP)
            canvas.save()
            if (pane.axisHorizontal) canvas.clipRect(0f, minOf(from, to), width.toFloat(), maxOf(from, to))
            else canvas.clipRect(minOf(from, to), 0f, maxOf(from, to), height.toFloat())
            canvas.drawPaint(plain)
            canvas.restore()
            plain.shader = null
        }
        if (frame.dusk > 0f) canvas.drawColor(Color.argb((frame.dusk * 255).toInt().coerceIn(0, 255), 0, 0, 0))
    }

    @RequiresApi(33)
    private class Gpu private constructor(private val shader: RuntimeShader) {
        companion object { fun create(): Gpu? = runtimeShader("stage pane", STAGE_SOURCE)?.let { Gpu(it) } }
        private val paint = Paint().apply { this.shader = this@Gpu.shader }
        private val blank = BitmapShader(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        private val fit = Matrix()

        fun draw(canvas: Canvas, width: Int, height: Int, frame: DuoFrame, cover: Boolean, geometry: FoldGeometry, pxPerMm: Float,
            intensity: Float, live: Bitmap?, still: Bitmap?, stillRect: RectF?) {
            shader.setPane(width.toFloat(), height.toFloat(), DuoPane.of(geometry, cover, width.toFloat(), height.toFloat()), frame, pxPerMm, intensity)
            if (live != null && live.width > 0 && live.height > 0) {
                val mirror = BitmapShader(live, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                mirror.filterMode = BitmapShader.FILTER_MODE_LINEAR
                fit.setScale(width.toFloat() / live.width, height.toFloat() / live.height)
                mirror.setLocalMatrix(fit)
                shader.setInputShader("frame", mirror)
                shader.setFloatUniform("live", 1f)
            } else {
                shader.setInputShader("frame", blank)
                shader.setFloatUniform("live", 0f)
            }
            if (still != null && stillRect != null && frame.still > 0f && still.width > 0 && still.height > 0) {
                // The still's own pixels map 1:1 onto its rect: the shader samples it at (p − rect.xy).
                val picture = BitmapShader(still, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                picture.filterMode = BitmapShader.FILTER_MODE_LINEAR
                shader.setInputShader("still", picture)
                shader.setFloatUniform("stillMix", frame.still.coerceIn(0f, 1f))
                shader.setFloatUniform("stillRect", stillRect.left, stillRect.top, still.width.toFloat(), still.height.toFloat())
            } else {
                shader.setInputShader("still", blank)
                shader.setFloatUniform("stillMix", 0f)
                shader.setFloatUniform("stillRect", 0f, 0f, 0f, 0f)
            }
            canvas.drawPaint(paint)
        }
    }

    private companion object { const val STOPS = 16 }
}

// ------------------------------------------------------------------------------------------------------- the shader

private const val PANE_UNIFORMS = """
    uniform float2 size;
    uniform float axis;      // 0: the hinge runs top to bottom (compare x); 1: side to side (compare y)
    uniform float hingePos;  // the hinge along that axis, px
    uniform float paneSide;  // +1: the pane lies past the hinge along the axis; -1: before it
    uniform float paneLen;   // hinge to the pane's free edge, px
    uniform float tilt;      // the pane's tilt from the plane, radians (0 = flat: no effect)
    uniform float projTilt;  // the tilt the projection uses (capped), radians
    uniform float eyeDist;   // eye to plane, px
    uniform float blurEdge;  // blur radius at the free edge for this tilt, px (DuoPaneModel.blurRadius)
    uniform float blurAtHinge; // the hinge's share of it
    uniform float darkMid;   // darkness mid-pane for this tilt (DuoPaneModel.darkMid)
    uniform float steepDark; // extra dimming of the whole pane once turned well past the vertical
    uniform float darkCap;
    uniform float dusk;      // whole-screen darkness around the panel swap
    uniform float paneAlpha; // how much the pane's picture replaces the sharp screen (eased in over the first degrees)
"""

/** The per-pixel pane geometry and look (DuoPaneModel.source, blurRadius, dark), for either main(). Expects `float2 p`. */
private const val PANE_MATH = """
    float coord = axis < 0.5 ? p.x : p.y;
    float d = (coord - hingePos) * paneSide;             // px from the hinge along the pane; negative off it
    float onPane = step(0.0, d) * step(0.0001, tilt);
    d = min(d, paneLen);
    float e = d / paneLen;                               // 0 at the hinge, 1 at the free edge
    float depth = max(eyeDist - d * sin(projTilt), 1.0); // the glass point is this far from the eye along the normal
    float src = min(paneLen, d * cos(projTilt) * eyeDist / depth); // where on the plane the eye's ray lands
    float sc = hingePos + paneSide * src;
    float2 q = axis < 0.5 ? float2(sc, p.y) : float2(p.x, sc);
    float radius = blurEdge * (blurAtHinge + (1.0 - blurAtHinge) * e);
    float dark = max(min(darkCap, darkMid * (0.4 + 0.6 * pow(e, 1.35)) / 0.6354), steepDark);
"""

/**
 * A round blur the size the film shows (up to a tenth of the pane): a Vogel disk of 16 to 48 taps, one per px of
 * radius, turned by a per-pixel angle so the sparse taps read as fine frosted-glass grain rather than ghost copies (as
 * marcoazeem/duo-open does; the angle comes from interleaved gradient noise, which is even and calm, not white noise).
 * Measured on the SM-F971U1 at half resolution over the whole cover: 117–120 fps. Minimal AGSL on purpose — a fixed
 * loop with a weight mask, no break — so it compiles on this device's driver.
 */
private const val BLUR_TEMPLATE = """
    half4 blurV(float2 q, float r, float2 seed) {
        if (r < 0.75) return SAMPLE(q);
        float tapsF = clamp(r, 16.0, 48.0);
        float rot = fract(52.9829189 * fract(0.06711056 * seed.x + 0.00583715 * seed.y)) * 6.28318;
        half4 sum = half4(0.0);
        float wsum = 0.0;
        for (int i = 0; i < 48; i++) {
            float fi = float(i);
            float w = 1.0 - step(tapsF, fi);
            float rr = r * sqrt((fi + 0.5) / tapsF);
            float a = fi * 2.39996 + rot;
            sum += SAMPLE(q + rr * float2(cos(a), sin(a))) * half(w);
            wsum += w;
        }
        return sum / half(max(wsum, 1.0));
    }
"""

/** Home: opaque output over Folio's own layer. */
private val HOME_SOURCE = """
    uniform shader content;
    uniform float overlay;   // 1 = drawn at half size over the sharp screen (FoldSmoothness): only what the pane and the dusk change
""" + PANE_UNIFORMS + BLUR_TEMPLATE.replace("SAMPLE", "content.eval") + """
    half4 main(float2 p) {
""" + PANE_MATH + """
        if (onPane < 0.5) {
            if (overlay > 0.5) return half4(0.0, 0.0, 0.0, dusk);
            half4 c = content.eval(p);
            return half4(c.rgb * (1.0 - dusk), c.a);
        }
        half4 cf = blurV(q, radius, p);
        float k = (1.0 - dark) * (1.0 - dusk);
        half4 pane = half4(cf.rgb * k, cf.a);
        if (overlay > 0.5) return half4(pane.rgb * paneAlpha, 1.0 - (1.0 - paneAlpha) * (1.0 - dusk));
        half4 own = content.eval(p);
        return mix(half4(own.rgb * (1.0 - dusk), own.a), pane, half(paneAlpha));
    }
"""

/** The stage: premultiplied output with alpha, over the real app. `frame` is the mirror; `still` the position-lock still. */
private val STAGE_SOURCE = """
    uniform shader frame;
    uniform shader still;
    uniform float live;       // 1 = frame has the screen's pixels
    uniform float stillMix;   // 0..1
    uniform float4 stillRect; // where the still lies on this canvas: x, y, w, h (px); its pixels are 1:1
""" + PANE_UNIFORMS + """
    half4 pick(float2 q) {
        half4 c = frame.eval(q);
        if (stillMix > 0.001) {
            float2 r = q - stillRect.xy;
            if (r.x >= 0.0 && r.y >= 0.0 && r.x < stillRect.z && r.y < stillRect.w) c = mix(c, still.eval(r), half(stillMix));
        }
        return c;
    }
""" + BLUR_TEMPLATE.replace("SAMPLE", "pick") + """
    half4 main(float2 p) {
""" + PANE_MATH + """
        // Under everything the real app shows through. Over it: the still where it lies, then black at dusk.
        half4 below = half4(0.0);
        if (stillMix > 0.001) {
            float2 r = p - stillRect.xy;
            if (r.x >= 0.0 && r.y >= 0.0 && r.x < stillRect.z && r.y < stillRect.w) {
                half4 s = still.eval(r);
                below = half4(s.rgb * half(stillMix), half(stillMix));
            }
        }
        below = half4(below.rgb * (1.0 - dusk), below.a + dusk * (1.0 - below.a));
        if (onPane < 0.5) return below;
        if (live < 0.5) {
            // No pixels to warp or blur: the pane is a black veil over the real app, as dark as the glass would be.
            float v = dark * paneAlpha;
            return half4(below.rgb * (1.0 - v), below.a + v * (1.0 - below.a));
        }
        half4 cf = blurV(q, radius, p);
        float k = (1.0 - dark) * (1.0 - dusk);
        half3 pane = cf.rgb * k;
        return half4(pane * paneAlpha + below.rgb * (1.0 - paneAlpha), paneAlpha + below.a * (1.0 - paneAlpha));
    }
"""
