package com.uber.edge.domain.crdt

import com.uber.edge.domain.clock.HLC
import com.uber.edge.domain.clock.VectorClock
import com.uber.edge.domain.model.TripStatus
import kotlinx.serialization.Serializable

/**
 * Last-Writer-Wins Register backed by HLC.
 */
@Serializable
data class LwwRegister<T>(
    val value: T,
    val hlc: HLC
) {
    fun merge(other: LwwRegister<T>): LwwRegister<T> {
        return if (other.hlc > this.hlc) other else this
    }
}

/**
 * Observed-Remove Set (OR-Set) with Add-Wins semantics.
 * Concurrent adds and removes resolve in favor of additions.
 */
@Serializable
data class OrSetElement<T>(
    val tag: String,
    val value: T,
    val addHlc: HLC
)

@Serializable
data class OrSet<T>(
    val adds: Map<String, OrSetElement<T>> = emptyMap(),
    val removes: Map<String, HLC> = emptyMap()
) {
    fun add(tag: String, value: T, hlc: HLC): OrSet<T> {
        val newAdds = adds.toMutableMap()
        newAdds[tag] = OrSetElement(tag, value, hlc)
        return OrSet(newAdds, removes)
    }

    fun remove(tag: String, hlc: HLC): OrSet<T> {
        val newRemoves = removes.toMutableMap()
        newRemoves[tag] = hlc
        return OrSet(adds, newRemoves)
    }

    fun values(): List<T> {
        return adds.filterKeys { tag -> !removes.containsKey(tag) }.values.map { it.value }
    }

    fun merge(other: OrSet<T>): OrSet<T> {
        val mergedAdds = (adds.keys + other.adds.keys).associateWith { tag ->
            val a = adds[tag]
            val b = other.adds[tag]
            when {
                a != null && b != null -> if (b.addHlc > a.addHlc) b else a
                a != null -> a
                else -> b!!
            }
        }

        val mergedRemoves = (removes.keys + other.removes.keys).associateWith { tag ->
            val a = removes[tag]
            val b = other.removes[tag]
            when {
                a != null && b != null -> if (b > a) b else a
                a != null -> a
                else -> b!!
            }
        }

        return OrSet(mergedAdds, mergedRemoves)
    }
}

/**
 * Grow-Only Counter (G-Counter) per node.
 */
@Serializable
data class GCounter(
    val counts: Map<String, Long> = emptyMap()
) {
    val total: Long get() = counts.values.sum()

    fun increment(nodeId: String, amount: Long = 1L): GCounter {
        require(amount >= 0) { "G-Counter increment must be non-negative" }
        val current = counts[nodeId] ?: 0L
        val updated = counts.toMutableMap()
        updated[nodeId] = current + amount
        return GCounter(updated)
    }

    fun merge(other: GCounter): GCounter {
        val allKeys = counts.keys + other.counts.keys
        val merged = allKeys.associateWith { key ->
            maxOf(this.counts[key] ?: 0L, other.counts[key] ?: 0L)
        }
        return GCounter(merged)
    }
}

/**
 * Pure Domain Trip State merging CRDT per Section 10.4 of PRD.
 */
@Serializable
data class StopNote(
    val tag: String,
    val text: String
)

@Serializable
data class LocationPoint(
    val lat: Double,
    val lng: Double,
    val label: String? = null
)

@Serializable
data class TripState(
    val id: String,
    val status: TripStatus,
    val pickup: LwwRegister<LocationPoint>?,
    val dropoff: LwwRegister<LocationPoint>?,
    val stopNotes: OrSet<StopNote> = OrSet(),
    val odometer: GCounter = GCounter(),
    val vclock: VectorClock = VectorClock(),
    val serverVersion: Long = 0L
) {
    companion object {
        fun merge(local: TripState, remote: TripState): TripState {
            require(local.id == remote.id) { "Cannot merge states for different trip IDs" }

            val mergedStatus = TripStatus.merge(local.status, remote.status)

            val mergedPickup = when {
                local.pickup != null && remote.pickup != null -> local.pickup.merge(remote.pickup)
                local.pickup != null -> local.pickup
                else -> remote.pickup
            }

            val mergedDropoff = when {
                local.dropoff != null && remote.dropoff != null -> local.dropoff.merge(remote.dropoff)
                local.dropoff != null -> local.dropoff
                else -> remote.dropoff
            }

            val mergedNotes = local.stopNotes.merge(remote.stopNotes)
            val mergedOdometer = local.odometer.merge(remote.odometer)
            val mergedVclock = local.vclock.merge(remote.vclock)
            val mergedServerVersion = maxOf(local.serverVersion, remote.serverVersion)

            return TripState(
                id = local.id,
                status = mergedStatus,
                pickup = mergedPickup,
                dropoff = mergedDropoff,
                stopNotes = mergedNotes,
                odometer = mergedOdometer,
                vclock = mergedVclock,
                serverVersion = mergedServerVersion
            )
        }
    }
}
