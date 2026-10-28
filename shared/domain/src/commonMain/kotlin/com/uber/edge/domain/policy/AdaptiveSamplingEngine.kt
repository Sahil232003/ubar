package com.uber.edge.domain.policy

import com.uber.edge.domain.model.SamplingProfile
import com.uber.edge.domain.model.TripStatus

enum class MotionState {
    STILL,
    WALKING,
    IN_VEHICLE,
    UNKNOWN
}

class AdaptiveSamplingEngine(
    private val hysteresisHoldTimeMs: Long = 20_000L
) {
    private var currentProfile: SamplingProfile = SamplingProfile.PASSIVE
    private var candidateProfile: SamplingProfile? = null
    private var candidateStartTimeMs: Long = 0L

    fun evaluate(
        tripStatus: TripStatus,
        motionState: MotionState,
        batteryPct: Int,
        thermal: String,
        charging: Boolean,
        stationaryDurationSec: Long,
        currentTimeMs: Long
    ): SamplingProfile {
        val target = computeRawTarget(
            tripStatus = tripStatus,
            motionState = motionState,
            batteryPct = batteryPct,
            thermal = thermal,
            charging = charging,
            stationaryDurationSec = stationaryDurationSec
        )

        // Rule: Immediate upgrade, but hysteresis delay on downgrade
        if (target.intervalMs < currentProfile.intervalMs) {
            // Upgrading accuracy immediately
            currentProfile = target
            candidateProfile = null
            return currentProfile
        }

        if (target == currentProfile) {
            candidateProfile = null
            return currentProfile
        }

        // Downgrade requested: apply hysteresis
        if (candidateProfile != target) {
            candidateProfile = target
            candidateStartTimeMs = currentTimeMs
        } else if (currentTimeMs - candidateStartTimeMs >= hysteresisHoldTimeMs) {
            currentProfile = target
            candidateProfile = null
        }

        return currentProfile
    }

    private fun computeRawTarget(
        tripStatus: TripStatus,
        motionState: MotionState,
        batteryPct: Int,
        thermal: String,
        charging: Boolean,
        stationaryDurationSec: Long
    ): SamplingProfile {
        if (tripStatus == TripStatus.OFFLINE) {
            return SamplingProfile.PASSIVE
        }

        // Low power overrides: battery < 15%, thermal serious/critical, or stationary > 60s
        val isThermalSevere = thermal.equals("serious", ignoreCase = true) || thermal.equals("critical", ignoreCase = true)
        val isCriticalBattery = batteryPct < 15 && !charging
        val isStationaryProlonged = stationaryDurationSec > 60L || motionState == MotionState.STILL

        if (isCriticalBattery || isThermalSevere || isStationaryProlonged) {
            return SamplingProfile.LOW_POWER
        }

        return when (tripStatus) {
            TripStatus.EN_ROUTE_TO_PICKUP,
            TripStatus.ARRIVED_AT_PICKUP,
            TripStatus.ON_TRIP -> SamplingProfile.HIGH_ACCURACY

            TripStatus.ONLINE_IDLE -> SamplingProfile.BALANCED

            TripStatus.OFFLINE,
            TripStatus.COMPLETED,
            TripStatus.CANCELLED -> SamplingProfile.PASSIVE
        }
    }
}
