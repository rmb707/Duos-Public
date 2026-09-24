package com.mccal.folio.duo

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mccal.folio.R
import kotlinx.coroutines.delay

/**
 * Fold8Duo (WP-70/71): the phone's assistant where iOS has Siri — Claude when the owner makes Claude the assistant
 * (its app answers ACTION_ASSIST and brings its own speech recogniser), Gemini otherwise. Three doors, all standard
 * intents, nothing faked: [ask] hands text to the assistant's app as the prompt (shared text); [listen] brings the
 * assistant up over whatever is on screen; [type] the same, ready to type into (iOS 18's "Type to Siri"). While the
 * assistant is up over Home, [AssistantGlow] breathes the edge light — Claude's warm colours or Apple Intelligence's —
 * until Home has focus again.
 */
internal object Assistant {
    const val CLAUDE = "com.anthropic.claude"
    const val GEMINI = "com.google.android.apps.bard"
    /** The Google app hosts Gemini's assistant session on this phone. */
    const val GOOGLE_APP = "com.google.android.googlequicksearchbox"
    private const val TAG = "FolioAssistant"

    /** The edge glow on Home: on from the moment the assistant is called, off once Home has focus again. */
    val glow = mutableStateOf(false)
    @Volatile var glowSince = 0L; private set

    /** The package holding the phone's assistant role (Settings › Default apps › Digital assistant app), or null. */
    fun packageName(context: Context): String? =
        runCatching { Settings.Secure.getString(context.contentResolver, "assistant") }.getOrNull()
            ?.substringBefore('/')?.takeIf { it.isNotBlank() }

    fun isClaude(context: Context) = packageName(context) == CLAUDE

    /** What to call it in a label. */
    fun name(context: Context): String = when (val pkg = packageName(context)) {   // brand names // english-only
        CLAUDE -> "Claude"; GEMINI, GOOGLE_APP -> "Gemini"; null -> "Assistant"   // english-only
        else -> runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault("Assistant")   // english-only
    }

    fun installed(context: Context, packageName: String): Boolean = runCatching { context.packageManager.getApplicationInfo(packageName, 0) }.isSuccess

    /** The app that takes a typed question: the assistant's own when it is Claude or Gemini, else Claude if present, else Gemini. */
    fun askTarget(context: Context): String? = when (packageName(context)) {
        CLAUDE -> CLAUDE
        GEMINI, GOOGLE_APP -> GEMINI
        else -> listOf(CLAUDE, GEMINI).firstOrNull { installed(context, it) }
    }?.takeIf { installed(context, it) }

    /** Ask by text: [text] arrives in the app as the prompt (shared text). False if there is no app to ask. */
    fun ask(context: Context, text: String): Boolean = askTarget(context)?.let { share(context, it, text) } ?: false

    /** Share [text] to [packageName] as the prompt. */
    fun share(context: Context, packageName: String, text: String): Boolean =
        start(context, Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(packageName)
            .putExtra(Intent.EXTRA_TEXT, text.trim()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    /** The assistant listening, over whatever is on screen. */
    fun listen(context: Context): Boolean = summon(context, keyboard = false)

    /** The assistant ready to type into (iOS 18's Type to Siri). */
    fun type(context: Context): Boolean = summon(context, keyboard = true)

    private fun summon(context: Context, keyboard: Boolean): Boolean {
        val holder = packageName(context) ?: GOOGLE_APP
        val assist = Intent(Intent.ACTION_ASSIST).setPackage(holder)
            .putExtra(Intent.EXTRA_ASSIST_INPUT_HINT_KEYBOARD, keyboard).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val voice = Intent(Intent.ACTION_VOICE_COMMAND).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val app = askTarget(context)?.let { context.packageManager.getLaunchIntentForPackage(it)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        val took = start(context, assist) || start(context, voice) || start(context, app)
        if (took) { glowSince = SystemClock.uptimeMillis(); glow.value = true }
        return took
    }

    private fun start(context: Context, intent: Intent?): Boolean {
        if (intent == null) return false
        return runCatching { context.startActivity(intent); true }
            .getOrElse { Log.i(TAG, "no taker for ${intent.action} (${intent.`package`}): ${it.javaClass.simpleName}"); false }
    }
}

/** Apple Intelligence's colours, cycled slowly around the screen's edge. */
private val APPLE = listOf(Color(0xFF3B82F6), Color(0xFF8B5CF6), Color(0xFFEC4899), Color(0xFFF97316), Color(0xFFFACC15), Color(0xFF22D3EE))
/** Claude's: terracotta, amber and cream. */
private val CLAUDE_WARM = listOf(Color(0xFFD97757), Color(0xFFF59E0B), Color(0xFFF4E3D3), Color(0xFFE8A87C), Color(0xFFC2410C), Color(0xFFFBBF24))

/**
 * The edge light iOS shows while Siri listens: a soft, breathing, slowly turning band of colour hugging the screen's
 * edge, drawn over Home while the assistant's window is up. Off the moment Home has focus again (the assistant went
 * away), after two seconds if nothing ever took focus (the call did not land), or after two minutes regardless.
 */
@Composable
internal fun AssistantGlow() {
    val on by Assistant.glow
    val context = LocalContext.current
    val focused = LocalWindowInfo.current.isWindowFocused
    var lostFocus by remember { mutableStateOf(false) }
    LaunchedEffect(on) { if (on) { lostFocus = false; delay(120_000); Assistant.glow.value = false } }
    LaunchedEffect(on, focused) {
        if (!on) return@LaunchedEffect
        if (!focused) { lostFocus = true; return@LaunchedEffect }
        if (lostFocus) { Assistant.glow.value = false; return@LaunchedEffect }
        delay(2_000)
        Assistant.glow.value = false
    }
    AnimatedVisibility(on, enter = fadeIn(tween(220)), exit = fadeOut(tween(380))) {
        val palette = remember(on) { if (Assistant.isClaude(context)) CLAUDE_WARM else APPLE }
        val transition = rememberInfiniteTransition(label = "assistant glow")
        val phase by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(7_000, easing = LinearEasing)), label = "turn")
        val breath by transition.animateFloat(.6f, 1f, infiniteRepeatable(tween(1_500, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath")
        val blur = with(LocalDensity.current) { 24.dp.toPx() }
        Canvas(Modifier.fillMaxSize().graphicsLayer {
            alpha = breath
            if (Build.VERSION.SDK_INT >= 31) renderEffect = BlurEffect(blur, blur, TileMode.Decal)
        }) {
            val n = palette.size
            val turned = phase * n
            val k = turned.toInt() % n
            val f = turned - turned.toInt()
            // n + 1 stops so the sweep closes on itself; shifting the colours by a fraction turns the whole band.
            val colors = List(n + 1) { i -> lerp(palette[(i + k) % n], palette[(i + k + 1) % n], f) }
            val stroke = 30.dp.toPx()
            // The band is centred on the screen's edge: half of it is off screen, the inner half feathers inward.
            drawRoundRect(brush = Brush.sweepGradient(colors, center = Offset(size.width / 2f, size.height / 2f)),
                cornerRadius = CornerRadius(48.dp.toPx()), style = Stroke(width = stroke))
        }
    }
}

/**
 * Spotlight's sparkle, at the end of the search field: with a query it asks the assistant that; empty, it opens the
 * assistant ready to type; a long press has it listen. Hidden when there is nothing to ask.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AssistantFieldButton(query: String, onDone: () -> Unit) {
    val context = LocalContext.current
    val target = remember(context) { Assistant.askTarget(context) }
    if (target == null) return
    val name = remember(context) { Assistant.name(context) }
    val haptic = LocalHapticFeedback.current
    val asks = query.isNotBlank()
    Icon(Icons.Rounded.AutoAwesome, contentDescription = stringResource(if (asks) R.string.fold8_assistant_ask else R.string.fold8_assistant_type, name),
        tint = Color.White.copy(alpha = .82f),
        modifier = Modifier.size(40.dp).clip(CircleShape).combinedClickable(
            onClick = { onDone(); if (asks) Assistant.ask(context, query) else Assistant.type(context) },
            onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onDone(); Assistant.listen(context) }
        ).padding(9.dp))
}
