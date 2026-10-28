package com.uber.edge.domain.clock

import kotlinx.serialization.Serializable

/**
 * Vector Clock per PRD Section 10.3.
 * Tracks causal history across distributed nodes (device vs server).
 */
@Serializable
data class VectorClock(
    val entries: Map<String, Long> = emptyMap()
) {
    fun get(nodeId: String): Long = entries[nodeId] ?: 0L

    fun increment(nodeId: String): VectorClock {
        val updated = entries.toMutableMap()
        updated[nodeId] = get(nodeId) + 1L
        return VectorClock(updated)
    }

    fun merge(other: VectorClock): VectorClock {
        val allKeys = entries.keys + other.entries.keys
        val merged = allKeys.associateWith { key ->
            maxOf(this.get(key), other.get(key))
        }
        return VectorClock(merged)
    }

    /**
     * Returns true if this vector clock is causally strictly less than or equal to other.
     * (∀ k, this[k] <= other[k])
     */
    fun isLessThanOrEqual(other: VectorClock): Boolean {
        val allKeys = entries.keys + other.entries.keys
        return allKeys.all { key -> this.get(key) <= other.get(key) }
    }

    /**
     * Returns true if this vector clock is strictly greater than other.
     */
    fun isGreaterThan(other: VectorClock): Boolean {
        return other.isLessThanOrEqual(this) && this != other
    }

    /**
     * Returns true if neither vector clock dominates the other (concurrent execution).
     */
    fun isConcurrentWith(other: VectorClock): Boolean {
        return !this.isLessThanOrEqual(other) && !other.isLessThanOrEqual(this)
    }
}
