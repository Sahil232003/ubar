package com.uber.edge.presentation.viewmodel

import com.uber.edge.domain.filter.ConstantVelocityKalmanFilter
import com.uber.edge.domain.model.DeviceState
import com.uber.edge.domain.model.LocationFix
import com.uber.edge.domain.model.Route
import com.uber.edge.domain.model.SamplingProfile
import com.uber.edge.domain.model.TripStatus
import com.uber.edge.domain.policy.AdaptiveSamplingEngine
import com.uber.edge.domain.policy.MotionState
import com.uber.edge.domain.port.RealtimeChannel
import com.uber.edge.presentation.state.ConnectionUiState
import com.uber.edge.presentation.state.MapUiState
import com.uber.edge.presentation.state.PuckUi
import com.uber.edge.presentation.state.RouteUi
import com.uber.edge.presentation.state.SurgeLayerUi
import com.uber.edge.presentation.state.TripUi
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class MapViewModel(
    private val realtimeChannel: RealtimeChannel,
    private val kalmanFilter: ConstantVelocityKalmanFilter = ConstantVelocityKalmanFilter(),
    private val samplingEngine: AdaptiveSamplingEngine = AdaptiveSamplingEngine(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    private val activeTripStatus = MutableStateFlow(TripStatus.OFFLINE)
    private val activeRoute = MutableStateFlow<Route?>(null)
    private val activeSurge = MutableStateFlow<Map<String, Double>>(emptyMap())
    private val rawLocation = MutableStateFlow(LocationFix(0L, 12.971598, 77.594566, 5.0, 0.0, 0.0))

    init {
        observeStreams()
    }

    private fun observeStreams() {
        // Hot Path Stream Combination:
        // Combine all telemetry streams, apply Kalman filter off-main-thread, and conflate to <= 60Hz
        combine(
            rawLocation,
            activeRoute,
            activeSurge,
            realtimeChannel.status,
            activeTripStatus
        ) { fix, route, surge, connStatus, tripStatus ->
            // Kalman filter GPS smoothing (ENU)
            val filtered = kalmanFilter.filter(fix, route) ?: fix

            val connUi = when (connStatus) {
                is RealtimeChannel.ConnectionStatus.Live -> ConnectionUiState.Live
                is RealtimeChannel.ConnectionStatus.Reconnecting -> ConnectionUiState.Reconnecting(connStatus.attempt, connStatus.nextRetryMs)
                is RealtimeChannel.ConnectionStatus.Offline -> ConnectionUiState.Offline(connStatus.pendingOps)
            }

            val samplingProfile = samplingEngine.evaluate(
                tripStatus = tripStatus,
                motionState = if (filtered.spdMps > 1.0) MotionState.IN_VEHICLE else MotionState.STILL,
                batteryPct = 80,
                thermal = "nominal",
                charging = false,
                stationaryDurationSec = if (filtered.spdMps <= 0.5) 10L else 0L,
                currentTimeMs = fix.t
            )

            MapUiState(
                connection = connUi,
                trip = if (tripStatus != TripStatus.OFFLINE) {
                    TripUi(
                        id = "trp_01J9ZJ4M2Q",
                        status = tripStatus,
                        pickupAddress = "MG Road",
                        dropoffAddress = "Koramangala 5th Block",
                        etaSec = route?.etaSec ?: 742
                    )
                } else null,
                driver = PuckUi(
                    lat = filtered.lat,
                    lng = filtered.lng,
                    bearing = filtered.brg,
                    accuracyM = filtered.accM,
                    interpolateFromMs = fix.t
                ),
                route = route?.let { RouteUi(it.routeVersion, it.polyline, it.distanceM) },
                surge = SurgeLayerUi(cells = surge.toPersistentMap()),
                samplingMode = samplingProfile
            )
        }
        .distinctUntilChanged()
        .conflate() // PRD Section 6.3: intermediate states dropped, never queued
        .flowOn(Dispatchers.Default)
        .onEach { newState ->
            _uiState.value = newState
        }
        .launchIn(scope)

        // Observe Inbound Remote Streams
        realtimeChannel.routeUpdates
            .onEach { activeRoute.value = it }
            .launchIn(scope)

        realtimeChannel.surgeUpdates
            .onEach { cells ->
                val current = activeSurge.value.toMutableMap()
                cells.forEach { current[it.h3] = it.mult }
                activeSurge.value = current
            }
            .launchIn(scope)
    }

    fun onFixReceived(fix: LocationFix) {
        rawLocation.value = fix
    }

    fun goOnline() {
        activeTripStatus.value = TripStatus.ONLINE_IDLE
        scope.launch {
            realtimeChannel.connect()
        }
    }

    fun goOffline() {
        activeTripStatus.value = TripStatus.OFFLINE
        scope.launch {
            realtimeChannel.disconnect()
        }
    }

    fun transitionTrip(newStatus: TripStatus) {
        activeTripStatus.value = TripStatus.merge(activeTripStatus.value, newStatus)
    }
}
