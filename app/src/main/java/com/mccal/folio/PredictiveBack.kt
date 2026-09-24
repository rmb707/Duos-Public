package com.mccal.folio

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.CancellationException

/**
 * Predictive back (Android 14+, on by default when targeting API 36): [onProgress] follows the back swipe from 0 to 1
 * so the surface can shrink toward where it came from; [onBack] runs when the swipe is released, [onCancel] when it's
 * abandoned. On older Android, or with gesture navigation off, only [onBack] runs.
 */
@Composable
internal fun PredictiveBack(enabled: Boolean = true, onProgress: (Float) -> Unit, onCancel: () -> Unit, onBack: () -> Unit) {
    val step by rememberUpdatedState(onProgress)
    val cancel by rememberUpdatedState(onCancel)
    val back by rememberUpdatedState(onBack)
    PredictiveBackHandler(enabled) { events ->
        try {
            events.collect { event -> step(event.progress) }
            back()
        } catch (e: CancellationException) {
            cancel()
            throw e
        }
    }
}
