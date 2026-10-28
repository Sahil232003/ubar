package com.uber.edge.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class LocationFix(
    val t: Long,
    val lat: Double,
    val lng: Double,
    val accM: Double,
    val spdMps: Double,
    val brg: Double,
    val alt: Double? = null,
    val src: String = "fused",
    val flt: String? = "kalman_v2"
)

@Serializable
data class RouteManeuver(
    val idx: Int,
    val type: String,
    val street: String,
    val distM: Int
)

@Serializable
data class Route(
    val tripId: String,
    val routeVersion: Long,
    val polyline: String,
    val precision: Int = 5,
    val distanceM: Int,
    val etaSec: Int,
    val reason: String? = null,
    val maneuvers: List<RouteManeuver> = emptyList(),
    val receivedAt: Long = 0L
)

@Serializable
data class SurgeCell(
    val h3: String,
    val mult: Double,
    val demandIdx: Double
)

@Serializable
data class DeviceState(
    val batteryPct: Int,
    val charging: Boolean,
    val thermal: String = "nominal", // nominal | moderate | serious | critical
    val network: String = "cellular_4g",
    val appState: String = "foreground" // foreground | background
)

enum class SamplingProfile(
    val intervalMs: Long,
    val minDistanceM: Float,
    val maxBatchDelayMs: Long,
    val uploadCadenceSec: Int
) {
    HIGH_ACCURACY(intervalMs = 1000L, minDistanceM = 5.0f, maxBatchDelayMs = 0L, uploadCadenceSec = 2),
    BALANCED(intervalMs = 5000L, minDistanceM = 20.0f, maxBatchDelayMs = 15000L, uploadCadenceSec = 15),
    LOW_POWER(intervalMs = 30000L, minDistanceM = 100.0f, maxBatchDelayMs = 60000L, uploadCadenceSec = 60),
    PASSIVE(intervalMs = 0L, minDistanceM = 0.0f, maxBatchDelayMs = 0L, uploadCadenceSec = 0);
}
