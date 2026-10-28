package com.uber.edge.domain.port

import com.uber.edge.domain.model.DeviceState
import com.uber.edge.domain.model.LocationFix
import com.uber.edge.domain.model.OutboxOp
import com.uber.edge.domain.model.Route
import com.uber.edge.domain.model.SamplingProfile
import com.uber.edge.domain.model.SurgeCell
import com.uber.edge.domain.model.TripStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface LocationSource {
    fun fixes(profile: SamplingProfile): Flow<LocationFix>
    suspend fun lastKnown(): LocationFix?
}

interface LocationFilter {
    fun filter(fix: LocationFix, activeRoute: Route? = null): LocationFix?
    fun reset()
}

interface EtaModel {
    data class Prediction(
        val etaCorrectionSec: Int,
        val confidence: Double,
        val modelVersion: String = "eta-lite-1.3.0"
    )
    suspend fun predictCorrection(
        speedHistoryMps: List<Double>,
        remainingDistanceM: Int,
        serverEtaSec: Int,
        serverEtaAgeSec: Int,
        stopCountOver20s: Int
    ): Prediction
}

interface RealtimeChannel {
    sealed interface ConnectionStatus {
        data object Live : ConnectionStatus
        data class Reconnecting(val attempt: Int, val nextRetryMs: Long) : ConnectionStatus
        data class Offline(val pendingOps: Int) : ConnectionStatus
    }

    val status: StateFlow<ConnectionStatus>
    val surgeUpdates: Flow<List<SurgeCell>>
    val routeUpdates: Flow<Route>
    val tripStateUpdates: Flow<TripStatus>

    suspend fun connect()
    suspend fun disconnect()
    suspend fun sendLocationBatch(fixes: List<LocationFix>, deviceState: DeviceState)
}

interface OutboxRepository {
    suspend fun enqueue(op: OutboxOp)
    suspend fun pendingOps(): List<OutboxOp>
    suspend fun markInFlight(opIds: List<String>)
    suspend fun markAcked(opIds: List<String>)
    suspend fun incrementAttempts(opIds: List<String>)
}
