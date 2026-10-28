package com.uber.edge.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class EnvelopeDto(
    val v: Int = 1,
    val type: String,
    val seq: Long,
    val ackSeq: Long = 0L,
    val ts: Long,
    val traceId: String,
    val payload: JsonElement
)

@Serializable
data class LocationPointDto(
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
data class DeviceStateDto(
    val batteryPct: Int,
    val charging: Boolean,
    val thermal: String,
    val network: String,
    val appState: String
)

@Serializable
data class EdgeEtaDto(
    val etaCorrectionSec: Int,
    val modelVersion: String,
    val confidence: Double
)

@Serializable
data class LocationBatchPayloadDto(
    val driverId: String,
    val tripId: String? = null,
    val deviceState: DeviceStateDto,
    val samplingMode: String,
    val points: List<LocationPointDto>,
    val edge: EdgeEtaDto? = null
)

@Serializable
data class SurgeItemDto(
    val h3: String,
    val mult: Double,
    val demandIdx: Double
)

@Serializable
data class SurgePricingPayloadDto(
    val cityId: String,
    val h3Res: Int = 8,
    val layerVersion: Long,
    val baseVersion: Long,
    val validUntil: Long,
    val changed: List<SurgeItemDto>,
    val removed: List<String> = emptyList()
)

@Serializable
data class RouteManeuverDto(
    val idx: Int,
    val type: String,
    val street: String,
    val distM: Int
)

@Serializable
data class RouteUpdatePayloadDto(
    val tripId: String,
    val routeVersion: Long,
    val reason: String? = null,
    val polyline: String,
    val precision: Int = 5,
    val distanceM: Int,
    val etaSec: Int,
    val maneuvers: List<RouteManeuverDto> = emptyList()
)

@Serializable
data class SessionResumePayloadDto(
    val driverId: String,
    val deviceId: String,
    val lastServerSeq: Long,
    val lastClientSeqAcked: Long
)
