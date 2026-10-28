package com.uber.edge.data.repository

import com.uber.edge.data.local.LocalDatabaseStore
import com.uber.edge.data.local.LocalNoteRecord
import com.uber.edge.data.local.LocalTripRecord
import com.uber.edge.domain.clock.HLC
import com.uber.edge.domain.clock.HlcEngine
import com.uber.edge.domain.clock.VectorClock
import com.uber.edge.domain.model.OpState
import com.uber.edge.domain.model.OutboxOp
import com.uber.edge.domain.model.TripStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class TripRepositoryImpl(
    private val store: LocalDatabaseStore,
    private val deviceId: String = "dev_a19c",
    private val hlcEngine: HlcEngine = HlcEngine(deviceId)
) {
    private var vclock = VectorClock().increment(deviceId)
    private var opCounter = 0

    val pendingOpsCount: Flow<Int> = store.pendingOpCount

    suspend fun getTrip(tripId: String): LocalTripRecord? {
        return store.getTrip(tripId)
    }

    /**
     * FR-07: Status transitions written locally first (outbox) in one atomic transaction.
     */
    suspend fun transitionStatus(
        tripId: String,
        newStatus: TripStatus,
        wallClockMs: Long
    ): OutboxOp {
        val currentTrip = store.getTrip(tripId)
        val mergedStatus = if (currentTrip != null) {
            TripStatus.merge(currentTrip.status, newStatus)
        } else newStatus

        // Advance clocks
        val hlc = hlcEngine.now(wallClockMs)
        vclock = vclock.increment(deviceId)
        opCounter++
        val opId = generateOpId(wallClockMs, opCounter)

        val op = OutboxOp(
            opId = opId,
            entity = "trip",
            entityId = tripId,
            field = "status",
            crdt = "max_ordinal",
            valueJson = """{"status":"${mergedStatus.name}","ordinal":${mergedStatus.ordinalValue}}""",
            hlc = hlc,
            vclock = vclock,
            state = OpState.PENDING,
            createdAt = wallClockMs
        )

        val updatedRecord = LocalTripRecord(
            id = tripId,
            status = mergedStatus,
            statusOrdinal = mergedStatus.ordinalValue,
            pickupJson = currentTrip?.pickupJson,
            dropoffJson = currentTrip?.dropoffJson,
            dropoffHlc = currentTrip?.dropoffHlc,
            serverVersion = currentTrip?.serverVersion ?: 0L,
            updatedHlc = hlc.toString()
        )

        // Atomic transaction: write to DB and outbox
        store.transaction {
            store.upsertTrip(updatedRecord)
            store.enqueueOp(op)
        }

        return op
    }

    /**
     * FR-11: Add stop note offline; written to OR-Set and outbox atomically.
     */
    suspend fun addStopNote(
        tripId: String,
        text: String,
        wallClockMs: Long
    ): OutboxOp {
        val hlc = hlcEngine.now(wallClockMs)
        vclock = vclock.increment(deviceId)
        opCounter++
        val tag = "$deviceId:${vclock.get(deviceId)}"
        val opId = generateOpId(wallClockMs, opCounter)

        val op = OutboxOp(
            opId = opId,
            entity = "trip",
            entityId = tripId,
            field = "stopNotes",
            crdt = "or_set_add",
            valueJson = """{"tag":"$tag","text":"$text"}""",
            hlc = hlc,
            vclock = vclock,
            state = OpState.PENDING,
            createdAt = wallClockMs
        )

        val noteRecord = LocalNoteRecord(
            tag = tag,
            tripId = tripId,
            text = text,
            addHlc = hlc.toString()
        )

        store.transaction {
            store.insertNote(noteRecord)
            store.enqueueOp(op)
        }

        return op
    }

    /**
     * Deterministic UUIDv7-style time-ordered identifier.
     */
    private fun generateOpId(timestampMs: Long, sequence: Int): String {
        val timeHex = timestampMs.toString(16).padStart(12, '0')
        val seqHex = sequence.toString(16).padStart(4, '0')
        return "$timeHex-7000-8000-$seqHex-000000000000"
    }

    fun getVectorClock(): VectorClock = vclock

    fun updateServerClocks(remoteHlc: HLC, remoteVClock: VectorClock, wallClockMs: Long) {
        hlcEngine.receive(remoteHlc, wallClockMs)
        vclock = vclock.merge(remoteVClock)
    }
}
