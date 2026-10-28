package com.uber.edge.presentation.map

import com.uber.edge.presentation.state.PuckUi
import kotlin.math.max
import kotlin.math.min

/**
 * High-performance 60 fps Puck Interpolator per PRD Section 11.3.
 * Runs between GPS fixes (typically ~1 Hz) using linear interpolation on (lat, lng)
 * and shortest-arc angular interpolation on bearing.
 */
class PuckInterpolator(
    private val defaultStepDurationMs: Long = 1000L
) {
    private var previousPuck: PuckUi? = null
    private var targetPuck: PuckUi? = null
    private var transitionStartMs: Long = 0L
    private var transitionDurationMs: Long = defaultStepDurationMs

    fun updateTarget(newPuck: PuckUi, timestampMs: Long) {
        if (targetPuck == null) {
            previousPuck = newPuck
            targetPuck = newPuck
            transitionStartMs = timestampMs
            transitionDurationMs = defaultStepDurationMs
            return
        }

        // Current interpolated position becomes the new start
        previousPuck = evaluate(timestampMs)
        targetPuck = newPuck

        val deltaT = timestampMs - transitionStartMs
        transitionDurationMs = if (deltaT in 200..3000) deltaT else defaultStepDurationMs
        transitionStartMs = timestampMs
    }

    fun evaluate(frameTimestampMs: Long): PuckUi {
        val prev = previousPuck ?: return PuckUi(12.971598, 77.594566, 0.0, 5.0)
        val target = targetPuck ?: return prev

        val elapsed = max(0L, frameTimestampMs - transitionStartMs)
        val progress = min(1.0, elapsed.toDouble() / transitionDurationMs)

        // Linear interpolation for coordinates
        val interpLat = prev.lat + (target.lat - prev.lat) * progress
        val interpLng = prev.lng + (target.lng - prev.lng) * progress

        // Shortest-arc interpolation for heading rotation
        val interpBearing = interpolateShortestArc(prev.bearing, target.bearing, progress)

        // Accuracy halo interpolation
        val interpAccuracy = prev.accuracyM + (target.accuracyM - prev.accuracyM) * progress

        return PuckUi(
            lat = interpLat,
            lng = interpLng,
            bearing = interpBearing,
            accuracyM = interpAccuracy,
            interpolateFromMs = frameTimestampMs
        )
    }

    companion object {
        /**
         * Calculates shortest-arc angular difference and interpolates.
         * Prevents the marker from rotating 350 degrees when moving from 5 deg to 355 deg.
         */
        fun interpolateShortestArc(fromDegrees: Double, toDegrees: Double, progress: Double): Double {
            val delta = ((toDegrees - fromDegrees + 540.0) % 360.0) - 180.0
            val result = (fromDegrees + delta * progress + 360.0) % 360.0
            return result
        }
    }
}
