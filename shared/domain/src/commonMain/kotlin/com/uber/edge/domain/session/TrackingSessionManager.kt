package com.uber.edge.domain.session

import com.uber.edge.domain.filter.ConstantVelocityKalmanFilter
import com.uber.edge.domain.model.DeviceState
import com.uber.edge.domain.model.LocationFix
import com.uber.edge.domain.model.Route
import com.uber.edge.domain.model.SamplingProfile
import com.uber.edge.domain.model.TripStatus
import com.uber.edge.domain.policy.AdaptiveSamplingEngine
import com.uber.edge.domain.policy.MotionState
import com.uber.edge.domain.port.EtaModel
import com.uber.edge.domain.port.LocationSource
import com.uber.edge.domain.port.RealtimeChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface TrackingSessionState {
    data object Stopped : TrackingSessionState
    data object Starting : TrackingSessionState
    data class Active(
        val profile: SamplingProfile,
        val tripStatus: TripStatus,
        val isBackgrounded: Boolean
    ) : TrackingSessionState
    data class Interrupted(val reason: String) : TrackingSessionState
}

class TrackingSessionManager(
    private val locationSource: LocationSource,
    private val realtimeChannel: RealtimeChannel,
    private val kalmanFilter: ConstantVelocityKalmanFilter,
    private val samplingEngine: AdaptiveSamplingEngine,
    private val etaModel: EtaModel,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val _sessionState = MutableStateFlow<TrackingSessionState>(TrackingSessionState.Stopped)
    val sessionState: StateFlow<TrackingSessionState> = _sessionState.asStateFlow()

    private var trackingJob: Job? = null
    private var telemetryUploadJob: Job? = null

    private var currentTripStatus = TripStatus.OFFLINE
    private var currentRoute: Route? = null
    private var isAppBackgrounded = false
    private var currentDeviceState = DeviceState(batteryPct = 100, charging = false)

    // Bounded ring buffer for Edge-AI (last 120s of speed fixes, < 50 KB per PRD Section 11.2)
    private val speedHistory120s = ArrayDeque<Double>(120)
    private val pendingUploadBatch = mutableListOf<LocationFix>()

    fun startSession(initialTripStatus: TripStatus = TripStatus.ONLINE_IDLE) {
        if (_sessionState.value is TrackingSessionState.Active) return

        _sessionState.value = TrackingSessionState.Starting
        currentTripStatus = initialTripStatus

        scope.launch {
            try {
                // Connect WebSocket transport
                realtimeChannel.connect()

                val initialProfile = samplingEngine.evaluate(
                    tripStatus = currentTripStatus,
                    motionState = MotionState.STILL,
                    batteryPct = currentDeviceState.batteryPct,
                    thermal = currentDeviceState.thermal,
                    charging = currentDeviceState.charging,
                    stationaryDurationSec = 0,
                    currentTimeMs = 1000L
                )

                _sessionState.value = TrackingSessionState.Active(
                    profile = initialProfile,
                    tripStatus = currentTripStatus,
                    isBackgrounded = isAppBackgrounded
                )

                startTrackingPipeline(initialProfile)
                startTelemetryUploader()
            } catch (e: Exception) {
                _sessionState.value = TrackingSessionState.Interrupted(e.message ?: "Failed to start session")
            }
        }
    }

    private fun startTrackingPipeline(profile: SamplingProfile) {
        trackingJob?.cancel()
        trackingJob = locationSource.fixes(profile)
            .onEach { rawFix ->
                // Stage 1: Kalman filter GPS smoothing & outlier rejection
                val filteredFix = kalmanFilter.filter(rawFix, currentRoute) ?: return@onEach

                // Update speed ring buffer (retain max 120 fixes)
                synchronized(speedHistory120s) {
                    if (speedHistory120s.size >= 120) speedHistory120s.removeFirst()
                    speedHistory120s.add(filteredFix.spdMps)
                }

                // Add to pending upload batch
                synchronized(pendingUploadBatch) {
                    pendingUploadBatch.add(filteredFix)
                }
            }
            .launchIn(scope)
    }

    private fun startTelemetryUploader() {
        telemetryUploadJob?.cancel()
        telemetryUploadJob = scope.launch {
            while (isActive) {
                val currentState = _sessionState.value
                val cadenceSec = if (currentState is TrackingSessionState.Active) {
                    currentState.profile.uploadCadenceSec
                } else 15

                delay(cadenceSec * 1000L)

                val toUpload = synchronized(pendingUploadBatch) {
                    val copy = pendingUploadBatch.toList()
                    pendingUploadBatch.clear()
                    copy
                }

                if (toUpload.isNotEmpty()) {
                    realtimeChannel.sendLocationBatch(
                        fixes = toUpload,
                        deviceState = currentDeviceState.copy(
                            appState = if (isAppBackgrounded) "background" else "foreground"
                        )
                    )
                }
            }
        }
    }

    fun stopSession() {
        scope.launch {
            // Section 9.2: Flush pending outbox batch (<= 10s), close WS, stop updates
            telemetryUploadJob?.cancel()
            val remaining = synchronized(pendingUploadBatch) {
                val copy = pendingUploadBatch.toList()
                pendingUploadBatch.clear()
                copy
            }
            if (remaining.isNotEmpty()) {
                realtimeChannel.sendLocationBatch(remaining, currentDeviceState)
            }

            trackingJob?.cancel()
            realtimeChannel.disconnect()
            kalmanFilter.reset()
            _sessionState.value = TrackingSessionState.Stopped
        }
    }

    fun onTripStatusChanged(newStatus: TripStatus) {
        currentTripStatus = TripStatus.merge(currentTripStatus, newStatus)
        recomputeProfile()
    }

    fun onRouteUpdated(route: Route) {
        currentRoute = route
    }

    fun onAppStateChanged(backgrounded: Boolean) {
        isAppBackgrounded = backgrounded
        recomputeProfile()
    }

    fun onDeviceStateChanged(state: DeviceState) {
        currentDeviceState = state
        recomputeProfile()
    }

    private fun recomputeProfile() {
        val currentState = _sessionState.value as? TrackingSessionState.Active ?: return
        val newProfile = samplingEngine.evaluate(
            tripStatus = currentTripStatus,
            motionState = MotionState.IN_VEHICLE,
            batteryPct = currentDeviceState.batteryPct,
            thermal = currentDeviceState.thermal,
            charging = currentDeviceState.charging,
            stationaryDurationSec = 0,
            currentTimeMs = 1000L
        )

        if (newProfile != currentState.profile) {
            _sessionState.value = currentState.copy(
                profile = newProfile,
                tripStatus = currentTripStatus,
                isBackgrounded = isAppBackgrounded
            )
            startTrackingPipeline(newProfile)
        }
    }
}
