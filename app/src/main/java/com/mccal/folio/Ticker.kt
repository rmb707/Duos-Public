package com.mccal.folio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

/**
 * One shared wall clock for every live icon, chronometer and clock, instead of one loop each.
 * Ticks only while something on screen collects it (lifecycle-aware), aligned to the boundary.
 */
internal object Ticker {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private fun ticking(periodMs: Long) = flow {
        while (true) {
            val now = System.currentTimeMillis()
            emit(now)
            delay(periodMs - now % periodMs)
        }
    }

    val seconds: StateFlow<Long> = ticking(1_000L).stateIn(scope, SharingStarted.WhileSubscribed(0), System.currentTimeMillis())
    val minutes: StateFlow<Long> = ticking(60_000L).stateIn(scope, SharingStarted.WhileSubscribed(0), System.currentTimeMillis())
}

@Composable internal fun rememberSecondTick(): State<Long> = Ticker.seconds.collectAsStateWithLifecycle()
@Composable internal fun rememberMinuteTick(): State<Long> = Ticker.minutes.collectAsStateWithLifecycle()
