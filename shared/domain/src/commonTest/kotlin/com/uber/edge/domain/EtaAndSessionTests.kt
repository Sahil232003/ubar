package com.uber.edge.domain

import com.uber.edge.domain.ai.EtaCorrectionEngine
import com.uber.edge.domain.filter.ConstantVelocityKalmanFilter
import com.uber.edge.domain.model.DeviceState
import com.uber.edge.domain.model.LocationFix
import com.uber.edge.domain.model.SamplingProfile
import com.uber.edge.domain.model.TripStatus
import com.uber.edge.domain.policy.AdaptiveSamplingEngine
import com.uber.edge.domain.port.LocationSource
import com.uber.edge.domain.port.RealtimeChannel
import com.uber.edge.domain.session.TrackingSessionManager
import com.uber.edge.domain.session.TrackingSessionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FakeLocationSource : LocationSource {
    private val _fixes = MutableSharedFlow<LocationFix>(extraBufferCapacity = 64)
    override fun fixes(profile: SamplingProfile): Flow<LocationFix> = _fixes.asSharedFlow()
    override suspend fun lastKnown(): LocationFix? = null
    fun emit(fix: LocationFix) = _fixes.tryEmit(fix)
}

class FakeRealtimeChannel : RealtimeChannel {
    private val _status = MutableStateFlow<RealtimeChannel.ConnectionStatus>(RealtimeChannel.ConnectionStatus.Live)
    override val status: StateFlow<RealtimeChannel.ConnectionStatus> = _status.asStateFlow()
    override val surgeUpdates = MutableSharedFlow<List<com.uber.edge.domain.model.SurgeCell>>()
    override val routeUpdates = MutableSharedFlow<com.uber.edge.domain.model.Route>()
    override val tripStateUpdates = MutableSharedFlow<TripStatus>()

    val uploadedBatches = mutableListOf<List<LocationFix>>()

    override suspend fun connect() {
        _status.value = RealtimeChannel.ConnectionStatus.Live
    }

    override suspend fun disconnect() {
        _status.value = RealtimeChannel.ConnectionStatus.Offline(0)
    }

    override suspend fun sendLocationBatch(fixes: List<LocationFix>, deviceState: DeviceState) {
        uploadedBatches.add(fixes)
    }
}

class EtaAndSessionTests {

    @Test
    fun testEtaFeaturesExtractionAndTrafficDelayPrediction() = runTest {
        val engine = EtaCorrectionEngine()

        // Synthetic speed series representing slow crawl in traffic (average ~3 m/s)
        val slowTraffic = List(120) { 3.0 + (it % 3) * 0.5 }

        // Remaining distance: 5000m, Server ETA: 400s (implies 12.5 m/s expected speed)
        val prediction = engine.predictCorrection(
            speedHistoryMps = slowTraffic,
            remainingDistanceM = 5000,
            serverEtaSec = 400,
            serverEtaAgeSec = 10,
            stopCountOver20s = 3
        )

        // Correction must be positive (traffic delay added) with strong confidence
        assertTrue(prediction.etaCorrectionSec > 0, "ETA correction should be positive for slow traffic")
        assertTrue(prediction.confidence >= 0.70, "Confidence should meet edge display threshold")
        assertEquals("eta-lite-1.3.0", prediction.modelVersion)
    }

    @Test
    fun testEtaFastHighwaySpeedReduction() = runTest {
        val engine = EtaCorrectionEngine()

        // Fast highway driving (22 m/s = ~80 km/h)
        val fastTraffic = List(120) { 22.0 }

        // Expected speed was 12 m/s
        val prediction = engine.predictCorrection(
            speedHistoryMps = fastTraffic,
            remainingDistanceM = 10000,
            serverEtaSec = 800,
            serverEtaAgeSec = 5,
            stopCountOver20s = 0
        )

        // ETA should be reduced (negative correction)
        assertTrue(prediction.etaCorrectionSec < 0, "ETA correction should reduce ETA when moving faster")
    }

    @Test
    fun testTrackingSessionLifecycle() = runTest {
        val locationSource = FakeLocationSource()
        val channel = FakeRealtimeChannel()
        val filter = ConstantVelocityKalmanFilter()
        val sampling = AdaptiveSamplingEngine()
        val etaModel = EtaCorrectionEngine()

        val sessionManager = TrackingSessionManager(
            locationSource = locationSource,
            realtimeChannel = channel,
            kalmanFilter = filter,
            samplingEngine = sampling,
            etaModel = etaModel
        )

        assertEquals(TrackingSessionState.Stopped, sessionManager.sessionState.value)

        // Start session
        sessionManager.startSession(TripStatus.ONLINE_IDLE)
        val state = sessionManager.sessionState.value
        assertTrue(state is TrackingSessionState.Active)
        assertEquals(SamplingProfile.BALANCED, (state as TrackingSessionState.Active).profile)

        // Emit fixes
        locationSource.emit(LocationFix(1000L, 12.971598, 77.594566, 5.0, 10.0, 90.0))
        locationSource.emit(LocationFix(2000L, 12.971650, 77.594600, 5.0, 10.0, 90.0))

        // Transition to ON_TRIP -> upgrades to HIGH_ACCURACY
        sessionManager.onTripStatusChanged(TripStatus.ON_TRIP)
        val activeState = sessionManager.sessionState.value as TrackingSessionState.Active
        assertEquals(SamplingProfile.HIGH_ACCURACY, activeState.profile)

        // Stop session -> flushes batch and stops
        sessionManager.stopSession()
        assertEquals(TrackingSessionState.Stopped, sessionManager.sessionState.value)
    }
}
