package com.uber.edge.data.sync

import com.uber.edge.data.local.LocalDatabaseStore
import com.uber.edge.data.repository.TripRepositoryImpl
import com.uber.edge.domain.clock.HLC
import com.uber.edge.domain.clock.VectorClock
import com.uber.edge.domain.model.OpState
import com.uber.edge.domain.model.OutboxOp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class SyncOpDto(
    val opId: String,
    val entity: String,
    val entityId: String,
    val field: String,
    val crdt: String,
    val value: JsonElement,
    val hlc: String,
    val vclock: Map<String, Long>
)

@Serializable
data class SyncRequestDto(
    val driverId: String,
    val deviceId: String,
    val sinceServerVersion: Long,
    val clientClock: Map<String, Long>,
    val ops: List<SyncOpDto>
)

@Serializable
data class SyncChangeItemDto(
    val entity: String,
    val entityId: String,
    val field: String,
    val crdt: String,
    val value: JsonElement,
    val hlc: String
)

@Serializable
data class SyncResponseDto(
    val accepted: List<String>,
    val rejected: List<String>,
    val serverVersion: Long,
    val serverClock: Map<String, Long>,
    val changes: List<SyncChangeItemDto>,
    val mergedState: JsonObject
)

interface SyncHttpTransport {
    suspend fun postSync(request: SyncRequestDto): SyncResponseDto
}

class OutboxSyncWorker(
    private val store: LocalDatabaseStore,
    private val tripRepository: TripRepositoryImpl,
    private val transport: SyncHttpTransport,
    private val driverId: String = "drv_7f3a91c2",
    private val deviceId: String = "dev_a19c",
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private var syncJob: Job? = null
    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    fun startPeriodicSync(intervalMs: Long = 10_000L) {
        syncJob?.cancel()
        syncJob = scope.launch {
            while (isActive) {
                runSyncCycle(currentTimeMs())
                delay(intervalMs)
            }
        }
    }

    fun stop() {
        syncJob?.cancel()
    }

    /**
     * Executes one complete two-way delta sync cycle (PRD Section 10.5).
     */
    suspend fun runSyncCycle(wallClockMs: Long): Boolean {
        if (_isSyncing.value) return false
        _isSyncing.value = true

        try {
            val pendingOps = store.getPendingOps(limit = 50)
            if (pendingOps.isEmpty()) {
                _isSyncing.value = false
                return true
            }

            val opIds = pendingOps.map { it.opId }
            store.markOpsInFlight(opIds)

            val sinceVersionStr = store.getMeta("last_server_version") ?: "0"
            val sinceServerVersion = sinceVersionStr.toLongOrNull() ?: 0L

            val syncRequest = SyncRequestDto(
                driverId = driverId,
                deviceId = deviceId,
                sinceServerVersion = sinceServerVersion,
                clientClock = tripRepository.getVectorClock().entries,
                ops = pendingOps.map { op ->
                    SyncOpDto(
                        opId = op.opId,
                        entity = op.entity,
                        entityId = op.entityId,
                        field = op.field,
                        crdt = op.crdt,
                        value = json.parseToJsonElement(op.valueJson),
                        hlc = op.hlc.toString(),
                        vclock = op.vclock.entries
                    )
                }
            )

            // Push to Gateway /v1/sync
            val response = transport.postSync(syncRequest)

            // Atomic local transaction to reconcile accepted ops and changes
            store.transaction {
                // 1. Mark accepted ops as ACKED
                store.markOpsAcked(response.accepted)

                // 2. Advance Vector Clock and HLC
                val serverVClock = VectorClock(response.serverClock)
                val maxChangeHlc = response.changes.maxOfOrNull { HLC.parse(it.hlc) }
                if (maxChangeHlc != null) {
                    tripRepository.updateServerClocks(maxChangeHlc, serverVClock, wallClockMs)
                }

                // 3. Save new server version
                store.setMeta("last_server_version", response.serverVersion.toString())

                // 4. Prune uploaded location points older than 72 hours (NFR-06)
                val seventyTwoHoursAgo = wallClockMs - (72 * 60 * 60 * 1000L)
                store.pruneOldUploadedPoints(seventyTwoHoursAgo)
            }

            return true
        } catch (e: Exception) {
            // Revert in-flight ops back to pending for retry with backoff
            val inFlight = store.getPendingOps()
            store.resetInFlightOps(inFlight.map { it.opId })
            return false
        } finally {
            _isSyncing.value = false
        }
    }

    private fun currentTimeMs(): Long = kotlin.time.TimeSource.Monotonic.markNow().elapsedNow().inWholeMilliseconds + 1791460500000L
}
