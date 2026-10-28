package com.uber.edge.presentation.state

import com.uber.edge.domain.model.SamplingProfile
import com.uber.edge.domain.model.TripStatus
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf

sealed interface ConnectionUiState {
    data object Live : ConnectionUiState
    data class Reconnecting(val attempt: Int, val nextRetryMs: Long) : ConnectionUiState
    data class Offline(val pendingOps: Int) : ConnectionUiState
}

enum class EtaSource {
    SERVER,
    EDGE
}

data class TripUi(
    val id: String,
    val status: TripStatus,
    val pickupAddress: String,
    val dropoffAddress: String,
    val etaSec: Int,
    val etaSource: EtaSource = EtaSource.SERVER
)

data class PuckUi(
    val lat: Double,
    val lng: Double,
    val bearing: Double,
    val accuracyM: Double,
    val interpolateFromMs: Long = 0L
)

data class RouteUi(
    val version: Long,
    val encodedPolyline: String,
    val remainingMeters: Int
)

data class SurgeLayerUi(
    val version: Long = 0L,
    val cells: PersistentMap<String, Double> = persistentMapOf() // H3 index -> multiplier
)

data class BannerUi(
    val id: String,
    val message: String,
    val level: String = "info" // info | warning | alert
)

/**
 * Section 6.4: Authoritative MapUiState
 * Single immutable state object observed by Compose / SwiftUI.
 */
data class MapUiState(
    val connection: ConnectionUiState = ConnectionUiState.Offline(0),
    val trip: TripUi? = null,
    val driver: PuckUi = PuckUi(12.971598, 77.594566, 0.0, 5.0),
    val route: RouteUi? = null,
    val surge: SurgeLayerUi = SurgeLayerUi(),
    val samplingMode: SamplingProfile = SamplingProfile.PASSIVE,
    val banner: BannerUi? = null
)
