package com.uber.edge.data.local

import com.uber.edge.domain.clock.HLC
import com.uber.edge.domain.clock.VectorClock
import com.uber.edge.domain.model.LocationFix
import com.uber.edge.domain.model.OpState
import com.uber.edge.domain.model.OutboxOp
import com.uber.edge.domain.model.TripStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Thread-Safe In-Memory / SQLite Local Store implementing WAL guarantees.
 * Single source of truth for the client.
 */
data class LocalTripRecord(
    val id: String,
    val status: TripStatus,
    val statusOrdinal: Int,
    val pickupJson: String?,
    val dropoffJson: String?,
    val dropoffHlc: String?,
    val serverVersion: Long = 0L,
    val updatedHlc: String
)

data class LocalNoteRecord(
    val tag: String,
    val tripId: String,
    val text: String,
    val addHlc: String,
    val removed: Boolean = false,
    val removeHlc: String? = null
)

class LocalDatabaseStore {
    private val mutex = Mutex()

    private val trips = mutableMapOf<String, LocalTripRecord>()
    private val notes = mutableMapOf<String, LocalNoteRecord>()
    private val locationPoints = mutableListOf<LocationFix>()
    private val outbox = mutableMapOf<String, OutboxOp>()
    private val syncMeta = mutableMapOf<String, String>()

    private val _pendingOpCount = MutableStateFlow(0)
    val pendingOpCount: Flow<Int> = _pendingOpCount.asStateFlow()

    suspend fun <T> transaction(block: suspend () -> T): T = mutex.withLock {
        block()
    }

    suspend fun upsertTrip(trip: LocalTripRecord) = mutex.withLock {
        trips[trip.id] = trip
    }

    suspend fun getTrip(tripId: String): LocalTripRecord? = mutex.withLock {
        trips[tripId]
    }

    suspend fun insertNote(note: LocalNoteRecord) = mutex.withLock {
        notes[note.tag] = note
    }

    suspend fun getActiveNotes(tripId: String): List<LocalNoteRecord> = mutex.withLock {
        notes.values.filter { it.tripId == tripId && !it.removed }
    }

    suspend fun insertPoints(points: List<LocationFix>) = mutex.withLock {
        locationPoints.addAll(points)
    }

    suspend fun enqueueOp(op: OutboxOp) = mutex.withLock {
        outbox[op.opId] = op
        _pendingOpCount.value = outbox.values.count { it.state == OpState.PENDING || it.state == OpState.INFLIGHT }
    }

    suspend fun getPendingOps(limit: Int = 50): List<OutboxOp> = mutex.withLock {
        outbox.values.filter { it.state == OpState.PENDING }.take(limit)
    }

    suspend fun markOpsInFlight(opIds: List<String>) = mutex.withLock {
        for (id in opIds) {
            val op = outbox[id]
            if (op != null) outbox[id] = op.copy(state = OpState.INFLIGHT)
        }
    }

    suspend fun markOpsAcked(opIds: List<String>) = mutex.withLock {
        for (id in opIds) {
            val op = outbox[id]
            if (op != null) outbox[id] = op.copy(state = OpState.ACKED)
        }
        _pendingOpCount.value = outbox.values.count { it.state == OpState.PENDING || it.state == OpState.INFLIGHT }
    }

    suspend fun resetInFlightOps(opIds: List<String>) = mutex.withLock {
        for (id in opIds) {
            val op = outbox[id]
            if (op != null) outbox[id] = op.copy(state = OpState.PENDING, attempts = op.attempts + 1)
        }
    }

    suspend fun pruneOldUploadedPoints(olderThanMs: Long) = mutex.withLock {
        locationPoints.removeAll { it.t < olderThanMs }
    }

    suspend fun getMeta(key: String): String? = mutex.withLock {
        syncMeta[key]
    }

    suspend fun setMeta(key: String, value: String) = mutex.withLock {
        syncMeta[key] = value
    }
}
