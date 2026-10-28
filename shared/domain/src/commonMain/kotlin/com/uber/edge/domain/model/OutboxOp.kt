package com.uber.edge.domain.model

import com.uber.edge.domain.clock.HLC
import com.uber.edge.domain.clock.VectorClock
import kotlinx.serialization.Serializable

@Serializable
enum class OpState {
    PENDING,
    INFLIGHT,
    ACKED,
    REJECTED
}

@Serializable
data class OutboxOp(
    val opId: String,
    val entity: String, // "trip", "driver"
    val entityId: String,
    val field: String,  // "status", "dropoff", "stopNotes", "odometer"
    val crdt: String,   // "max_ordinal", "lww", "or_set_add", "or_set_remove", "g_counter"
    val valueJson: String,
    val hlc: HLC,
    val vclock: VectorClock,
    val state: OpState = OpState.PENDING,
    val attempts: Int = 0,
    val createdAt: Long = 0L
)
