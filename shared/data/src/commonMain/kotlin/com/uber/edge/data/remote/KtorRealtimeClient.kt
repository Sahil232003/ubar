package com.uber.edge.data.remote

import com.uber.edge.domain.model.DeviceState
import com.uber.edge.domain.model.LocationFix
import com.uber.edge.domain.model.Route
import com.uber.edge.domain.model.RouteManeuver
import com.uber.edge.domain.model.SurgeCell
import com.uber.edge.domain.model.TripStatus
import com.uber.edge.domain.port.RealtimeChannel
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.random.Random

class KtorRealtimeClient(
    private val httpClient: HttpClient,
    private val host: String = "127.0.0.1",
    private val port: Int = 8080,
    private val jwtToken: String = "test_driver_jwt",
    private val driverId: String = "drv_7f3a91c2",
    private val deviceId: String = "dev_a19c",
    private val json: Json = Json { ignoreUnknownKeys = true }
) : RealtimeChannel {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var connectionJob: Job? = null
    private var heartbeatJob: Job? = null
    private var session: DefaultClientWebSocketSession? = null

    private val _status = MutableStateFlow<RealtimeChannel.ConnectionStatus>(RealtimeChannel.ConnectionStatus.Offline(0))
    override val status: StateFlow<RealtimeChannel.ConnectionStatus> = _status.asStateFlow()

    // Bounded channels per PRD Section 6.3:
    // Surge updates are conflatable (DROP_OLDEST), route and trip states are reliable
    private val surgeChannel = Channel<List<SurgeCell>>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val routeChannel = Channel<Route>(capacity = 16, onBufferOverflow = BufferOverflow.SUSPEND)
    private val tripStateChannel = Channel<TripStatus>(capacity = 16, onBufferOverflow = BufferOverflow.SUSPEND)

    override val surgeUpdates: Flow<List<SurgeCell>> = surgeChannel.receiveAsFlow()
    override val routeUpdates: Flow<Route> = routeChannel.receiveAsFlow()
    override val tripStateUpdates: Flow<TripStatus> = tripStateChannel.receiveAsFlow()

    private var clientSeq: Long = 0L
    private var lastServerSeq: Long = 0L
    private var lastPongReceivedMs: Long = 0L

    override suspend fun connect() {
        if (connectionJob?.isActive == true) return
        connectionJob = scope.launch {
            var attempt = 0
            while (isActive) {
                try {
                    val wsUrl = "ws://$host:$port/v1/stream?token=$jwtToken"
                    _status.value = RealtimeChannel.ConnectionStatus.Reconnecting(attempt, computeBackoff(attempt))
                    
                    if (attempt > 0) {
                        delay(computeBackoff(attempt))
                    }

                    httpClient.webSocketSession(urlString = wsUrl).use { ws ->
                        session = ws
                        _status.value = RealtimeChannel.ConnectionStatus.Live
                        attempt = 0 // Connected successfully: reset backoff after stability window
                        lastPongReceivedMs = currentTimeMs()

                        // Send session.resume immediately
                        sendResume()

                        // Start heartbeat
                        startHeartbeat(ws)

                        // Inbound frame processing loop
                        for (frame in ws.incoming) {
                            if (frame is Frame.Text) {
                                handleInboundFrame(frame.readText(), ws)
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    attempt++
                } finally {
                    session = null
                    heartbeatJob?.cancel()
                    _status.value = RealtimeChannel.ConnectionStatus.Offline(0)
                }
            }
        }
    }

    override suspend fun disconnect() {
        connectionJob?.cancel()
        heartbeatJob?.cancel()
        session?.close()
        session = null
        _status.value = RealtimeChannel.ConnectionStatus.Offline(0)
    }

    override suspend fun sendLocationBatch(fixes: List<LocationFix>, deviceState: DeviceState) {
        val currentSession = session ?: return
        clientSeq++

        val payload = LocationBatchPayloadDto(
            driverId = driverId,
            deviceState = DeviceStateDto(
                batteryPct = deviceState.batteryPct,
                charging = deviceState.charging,
                thermal = deviceState.thermal,
                network = deviceState.network,
                appState = deviceState.appState
            ),
            samplingMode = "HIGH_ACCURACY",
            points = fixes.map {
                LocationPointDto(
                    t = it.t,
                    lat = it.lat,
                    lng = it.lng,
                    accM = it.accM,
                    spdMps = it.spdMps,
                    brg = it.brg,
                    alt = it.alt,
                    src = it.src,
                    flt = it.flt
                )
            }
        )

        val envelope = EnvelopeDto(
            v = 1,
            type = "telemetry.location.batch",
            seq = clientSeq,
            ackSeq = lastServerSeq,
            ts = currentTimeMs(),
            traceId = "01J9ZK" + clientSeq.toString(36).uppercase(),
            payload = json.encodeToJsonElement(payload)
        )

        currentSession.send(Frame.Text(json.encodeToString(envelope)))
    }

    private suspend fun sendResume() {
        val currentSession = session ?: return
        clientSeq++
        val resumePayload = SessionResumePayloadDto(
            driverId = driverId,
            deviceId = deviceId,
            lastServerSeq = lastServerSeq,
            lastClientSeqAcked = 0L
        )
        val env = EnvelopeDto(
            v = 1,
            type = "session.resume",
            seq = clientSeq,
            ackSeq = lastServerSeq,
            ts = currentTimeMs(),
            traceId = "01J9ZK_RESUME",
            payload = json.encodeToJsonElement(resumePayload)
        )
        currentSession.send(Frame.Text(json.encodeToString(env)))
    }

    private fun startHeartbeat(ws: DefaultClientWebSocketSession) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(15_000L) // Ping every 15s
                clientSeq++
                val pingEnv = EnvelopeDto(
                    v = 1,
                    type = "system.ping",
                    seq = clientSeq,
                    ackSeq = lastServerSeq,
                    ts = currentTimeMs(),
                    traceId = "01J9ZK_PING_$clientSeq",
                    payload = json.parseToJsonElement("{}")
                )
                ws.send(Frame.Text(json.encodeToString(pingEnv)))

                // Wait up to 10s for pong; if no pong, treat connection as dead
                delay(10_000L)
                if (currentTimeMs() - lastPongReceivedMs > 25_000L) {
                    ws.close()
                    break
                }
            }
        }
    }

    private suspend fun handleInboundFrame(text: String, ws: DefaultClientWebSocketSession) {
        val envelope = json.decodeFromString<EnvelopeDto>(text)

        // Monotonic sequence verification: discard stale or duplicate frames
        if (envelope.seq <= lastServerSeq && envelope.type != "system.pong") {
            return
        }
        lastServerSeq = envelope.seq

        when (envelope.type) {
            "pricing.surge" -> {
                val surgeDto = json.decodeFromJsonElement<SurgePricingPayloadDto>(envelope.payload)
                val domainCells = surgeDto.changed.map { SurgeCell(it.h3, it.mult, it.demandIdx) }
                surgeChannel.trySend(domainCells)
            }
            "route.update" -> {
                val routeDto = json.decodeFromJsonElement<RouteUpdatePayloadDto>(envelope.payload)
                val domainRoute = Route(
                    tripId = routeDto.tripId,
                    routeVersion = routeDto.routeVersion,
                    polyline = routeDto.polyline,
                    precision = routeDto.precision,
                    distanceM = routeDto.distanceM,
                    etaSec = routeDto.etaSec,
                    reason = routeDto.reason,
                    maneuvers = routeDto.maneuvers.map {
                        RouteManeuver(it.idx, it.type, it.street, it.distM)
                    },
                    receivedAt = envelope.ts
                )
                routeChannel.send(domainRoute)
            }
            "system.pong" -> {
                lastPongReceivedMs = currentTimeMs()
            }
        }
    }

    /**
     * Exponential backoff with full jitter:
     * delay = random(0, min(30_000, 500 * 2^attempt))
     */
    private fun computeBackoff(attempt: Int): Long {
        val factor = 1L shl minOf(attempt, 6)
        val maxDelay = minOf(30_000L, 500L * factor)
        return Random.nextLong(0, maxDelay + 1)
    }

    private fun currentTimeMs(): Long = kotlin.time.TimeSource.Monotonic.markNow().elapsedNow().inWholeMilliseconds + 1791460500000L
}
