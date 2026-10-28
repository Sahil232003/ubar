package com.uber.edge.data

import com.uber.edge.data.local.LocalDatabaseStore
import com.uber.edge.data.repository.TripRepositoryImpl
import com.uber.edge.data.sync.OutboxSyncWorker
import com.uber.edge.data.sync.SyncHttpTransport
import com.uber.edge.data.sync.SyncRequestDto
import com.uber.edge.data.sync.SyncResponseDto
import com.uber.edge.domain.model.LocationFix
import com.uber.edge.domain.model.OpState
import com.uber.edge.domain.model.TripStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FakeSyncHttpTransport : SyncHttpTransport {
    var lastSentRequest: SyncRequestDto? = null

    override suspend fun postSync(request: SyncRequestDto): SyncResponseDto {
        lastSentRequest = request
        return SyncResponseDto(
            accepted = request.ops.map { it.opId },
            rejected = emptyList(),
            serverVersion = request.sinceServerVersion + 1,
            serverClock = mapOf("dev_a19c" to 57L, "server" to (request.sinceServerVersion + 1)),
            changes = emptyList(),
            mergedState = buildJsonObject {
                put("status", "ARRIVED_AT_PICKUP")
            }
        )
    }
}

class SyncAndOutboxTests {

    @Test
    fun testOfflineStatusTransitionAndOutboxAtomicEnqueue() = runTest {
        val store = LocalDatabaseStore()
        val repo = TripRepositoryImpl(store, deviceId = "dev_a19c")

        val op = repo.transitionStatus(
            tripId = "trp_01J9ZJ4M2Q",
            newStatus = TripStatus.ARRIVED_AT_PICKUP,
            wallClockMs = 1791460401222L
        )

        assertEquals("max_ordinal", op.crdt)
        assertEquals(OpState.PENDING, op.state)

        val localTrip = store.getTrip("trp_01J9ZJ4M2Q")
        assertEquals(TripStatus.ARRIVED_AT_PICKUP, localTrip?.status)
        assertEquals(3, localTrip?.statusOrdinal)

        val pending = store.getPendingOps()
        assertEquals(1, pending.size)
        assertEquals(op.opId, pending[0].opId)
    }

    @Test
    fun testOfflineStopNoteAddition() = runTest {
        val store = LocalDatabaseStore()
        val repo = TripRepositoryImpl(store, deviceId = "dev_a19c")

        val op = repo.addStopNote(
            tripId = "trp_01J9ZJ4M2Q",
            text = "Gate code 4471, use side entrance",
            wallClockMs = 1791460455870L
        )

        assertEquals("or_set_add", op.crdt)

        val activeNotes = store.getActiveNotes("trp_01J9ZJ4M2Q")
        assertEquals(1, activeNotes.size)
        assertEquals("Gate code 4471, use side entrance", activeNotes[0].text)
    }

    @Test
    fun testOutboxSyncWorkerReconciliationAndPruning() = runTest {
        val store = LocalDatabaseStore()
        val repo = TripRepositoryImpl(store, deviceId = "dev_a19c")
        val transport = FakeSyncHttpTransport()

        // 1. Create offline actions
        repo.transitionStatus("trp_01J9ZJ4M2Q", TripStatus.ARRIVED_AT_PICKUP, 1791460401222L)
        repo.addStopNote("trp_01J9ZJ4M2Q", "Gate code 4471", 1791460455870L)

        // Add an old point that should be pruned (>72h old)
        val nowMs = 1791460500000L
        val seventyThreeHoursAgo = nowMs - (73 * 60 * 60 * 1000L)
        store.insertPoints(listOf(
            LocationFix(seventyThreeHoursAgo, 12.971598, 77.594566, 5.0, 10.0, 90.0)
        ))

        val worker = OutboxSyncWorker(
            store = store,
            tripRepository = repo,
            transport = transport
        )

        val success = worker.runSyncCycle(nowMs)
        assertTrue(success)

        // All ops should now be ACKED
        val pendingAfterSync = store.getPendingOps()
        assertEquals(0, pendingAfterSync.size)

        // Last server version stored
        val serverVersion = store.getMeta("last_server_version")
        assertEquals("1", serverVersion)
    }
}
