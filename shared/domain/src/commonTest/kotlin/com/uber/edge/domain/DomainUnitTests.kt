package com.uber.edge.domain

import com.uber.edge.domain.clock.HLC
import com.uber.edge.domain.clock.HlcEngine
import com.uber.edge.domain.clock.VectorClock
import com.uber.edge.domain.crdt.GCounter
import com.uber.edge.domain.crdt.LocationPoint
import com.uber.edge.domain.crdt.LwwRegister
import com.uber.edge.domain.crdt.OrSet
import com.uber.edge.domain.crdt.StopNote
import com.uber.edge.domain.crdt.TripState
import com.uber.edge.domain.model.TripStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DomainUnitTests {

    @Test
    fun testTripStatusLatticeAndCancelledDominance() {
        // Monotonic progression: higher ordinal wins
        assertEquals(TripStatus.ONLINE_IDLE, TripStatus.merge(TripStatus.OFFLINE, TripStatus.ONLINE_IDLE))
        assertEquals(TripStatus.ARRIVED_AT_PICKUP, TripStatus.merge(TripStatus.EN_ROUTE_TO_PICKUP, TripStatus.ARRIVED_AT_PICKUP))
        assertEquals(TripStatus.ON_TRIP, TripStatus.merge(TripStatus.ON_TRIP, TripStatus.ARRIVED_AT_PICKUP))

        // CANCELLED (ordinal 99) dominates any other state
        assertEquals(TripStatus.CANCELLED, TripStatus.merge(TripStatus.EN_ROUTE_TO_PICKUP, TripStatus.CANCELLED))
        assertEquals(TripStatus.CANCELLED, TripStatus.merge(TripStatus.CANCELLED, TripStatus.ON_TRIP))
        assertEquals(TripStatus.CANCELLED, TripStatus.merge(TripStatus.OFFLINE, TripStatus.CANCELLED))
    }

    @Test
    fun testHlcMonotonicityAndCausality() {
        val hlcA = HlcEngine("nodeA")
        val hlcB = HlcEngine("nodeB")

        val t1 = hlcA.now(1000L)
        val t2 = hlcA.now(1000L) // same physical ms -> counter increments
        assertTrue(t2 > t1)
        assertEquals(0, t1.c)
        assertEquals(1, t2.c)

        // Remote message passing
        val t3 = hlcB.receive(t2, 1000L)
        assertTrue(t3 > t2)

        // Physical clock backwards drift
        val t4 = hlcA.now(900L) // clock drifted back
        assertTrue(t4.l >= t2.l)
    }

    @Test
    fun testVectorClockCausalityAndConcurrency() {
        var v1 = VectorClock().increment("A") // {A: 1}
        var v2 = VectorClock().increment("B") // {B: 1}

        // v1 and v2 are concurrent
        assertTrue(v1.isConcurrentWith(v2))
        assertFalse(v1.isLessThanOrEqual(v2))

        val vMerged = v1.merge(v2) // {A: 1, B: 1}
        assertTrue(v1.isLessThanOrEqual(vMerged))
        assertTrue(v2.isLessThanOrEqual(vMerged))
        assertTrue(vMerged.isGreaterThan(v1))
        assertTrue(vMerged.isGreaterThan(v2))
    }

    @Test
    fun testLwwRegisterMerge() {
        val hlc1 = HLC(1000L, 0, "nodeA")
        val hlc2 = HLC(1005L, 0, "nodeB")

        val reg1 = LwwRegister("Initial Dropoff", hlc1)
        val reg2 = LwwRegister("Updated Dropoff", hlc2)

        // Commutativity: merge(a, b) == merge(b, a)
        assertEquals("Updated Dropoff", reg1.merge(reg2).value)
        assertEquals("Updated Dropoff", reg2.merge(reg1).value)
    }

    @Test
    fun testOrSetAddWinsSemantics() {
        val hlc = HLC(1000L, 0, "nodeA")
        var setA = OrSet<StopNote>()
        var setB = OrSet<StopNote>()

        val note1 = StopNote("tag1", "Gate code 4471")
        val note2 = StopNote("tag2", "Call on arrival")

        // Node A adds note 1
        setA = setA.add(note1.tag, note1, hlc)

        // Node B adds note 2
        setB = setB.add(note2.tag, note2, hlc)

        // Concurrent merge: both notes survive
        val merged = setA.merge(setB)
        assertEquals(2, merged.values().size)
        assertTrue(merged.values().any { it.text == "Gate code 4471" })
        assertTrue(merged.values().any { it.text == "Call on arrival" })
    }

    @Test
    fun testGCounterMerge() {
        var c1 = GCounter().increment("nodeA", 50)
        var c2 = GCounter().increment("nodeB", 100)

        val merged = c1.merge(c2)
        assertEquals(150L, merged.total)
    }

    @Test
    fun testTripStateFullCrdtMergeLaws() {
        val hlcA = HLC(1000L, 0, "dev_a19c")
        val hlcServer = HLC(1020L, 0, "server")

        val localState = TripState(
            id = "trp_01J9ZJ4M2Q",
            status = TripStatus.ARRIVED_AT_PICKUP, // ordinal 3
            pickup = LwwRegister(LocationPoint(12.971598, 77.594566, "MG Road"), hlcA),
            dropoff = LwwRegister(LocationPoint(12.935192, 77.624480, "Koramangala"), hlcA)
        )

        val serverState = TripState(
            id = "trp_01J9ZJ4M2Q",
            status = TripStatus.EN_ROUTE_TO_PICKUP, // ordinal 2
            pickup = LwwRegister(LocationPoint(12.971598, 77.594566, "MG Road"), hlcA),
            dropoff = LwwRegister(LocationPoint(12.935200, 77.624500, "Koramangala 5th Block"), hlcServer) // updated by support
        )

        // Merge: local status (ARRIVED, 3 > 2) wins, server dropoff (hlcServer > hlcA) wins!
        val merged = TripState.merge(localState, serverState)
        assertEquals(TripStatus.ARRIVED_AT_PICKUP, merged.status)
        assertEquals("Koramangala 5th Block", merged.dropoff?.value?.label)

        // Commutativity: merge(local, server) == merge(server, local)
        val reverseMerged = TripState.merge(serverState, localState)
        assertEquals(merged.status, reverseMerged.status)
        assertEquals(merged.dropoff?.value?.label, reverseMerged.dropoff?.value?.label)
    }
}
