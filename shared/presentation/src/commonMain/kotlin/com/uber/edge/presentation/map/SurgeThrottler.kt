package com.uber.edge.presentation.map

import com.uber.edge.presentation.state.SurgeLayerUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Throttles high-frequency surge pricing updates to at most once per 500 ms
 * per PRD Section 11.3 (Layout Flattening & Frame-Drop Prevention).
 */
class SurgeThrottler(
    private val minIntervalMs: Long = 500L,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    private var lastEmittedTimeMs: Long = 0L
    private var pendingSurge: SurgeLayerUi? = null
    private var delayedJob: Job? = null

    private val _throttledSurgeGeoJson = MutableSharedFlow<String>(replay = 1)
    val throttledSurgeGeoJson: Flow<String> = _throttledSurgeGeoJson.asSharedFlow()

    fun onSurgeUpdated(surge: SurgeLayerUi, currentTimeMs: Long) {
        val elapsed = currentTimeMs - lastEmittedTimeMs

        if (elapsed >= minIntervalMs) {
            delayedJob?.cancel()
            delayedJob = null
            lastEmittedTimeMs = currentTimeMs
            pendingSurge = null
            _throttledSurgeGeoJson.tryEmit(GeoJsonBuilders.buildSurgeGeoJson(surge))
        } else {
            pendingSurge = surge
            if (delayedJob == null) {
                val remainingDelay = minIntervalMs - elapsed
                delayedJob = scope.launch {
                    delay(remainingDelay)
                    pendingSurge?.let {
                        lastEmittedTimeMs = currentTimeMs + remainingDelay
                        _throttledSurgeGeoJson.tryEmit(GeoJsonBuilders.buildSurgeGeoJson(it))
                        pendingSurge = null
                    }
                    delayedJob = null
                }
            }
        }
    }
}
