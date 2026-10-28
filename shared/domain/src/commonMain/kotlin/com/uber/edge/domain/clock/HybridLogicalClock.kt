package com.uber.edge.domain.clock

import kotlinx.serialization.Serializable

/**
 * Hybrid Logical Clock (HLC) per PRD Section 10.3 and Kulkarni et al.
 * Representation: <physicalMs>:<counter 4 hex>:<nodeId>
 */
@Serializable
data class HLC(
    val l: Long,
    val c: Int,
    val nodeId: String
) : Comparable<HLC> {

    override fun compareTo(other: HLC): Int {
        if (this.l != other.l) return this.l.compareTo(other.l)
        if (this.c != other.c) return this.c.compareTo(other.c)
        return this.nodeId.compareTo(other.nodeId)
    }

    override fun toString(): String {
        val hexCounter = c.toString(16).padStart(4, '0')
        return "$l:$hexCounter:$nodeId"
    }

    companion object {
        fun parse(serialized: String): HLC {
            val parts = serialized.split(":")
            require(parts.size == 3) { "Invalid HLC string: $serialized" }
            return HLC(
                l = parts[0].toLong(),
                c = parts[1].toInt(16),
                nodeId = parts[2]
            )
        }

        fun initial(nodeId: String): HLC = HLC(0L, 0, nodeId)
    }
}

class HlcEngine(
    private val nodeId: String,
    initialClock: HLC = HLC.initial(nodeId),
    private val onSkewWarning: ((skewMs: Long) -> Unit)? = null
) {
    var current: HLC = initialClock
        private set

    /**
     * Local event clock tick.
     */
    fun now(wallClockMs: Long): HLC {
        val pt = wallClockMs
        current = if (pt > current.l) {
            HLC(l = pt, c = 0, nodeId = nodeId)
        } else {
            HLC(l = current.l, c = current.c + 1, nodeId = nodeId)
        }
        return current
    }

    /**
     * Remote event receive tick.
     */
    fun receive(remote: HLC, wallClockMs: Long): HLC {
        val pt = wallClockMs
        val lPrime = maxOf(current.l, remote.l, pt)
        val cPrime = when {
            lPrime == current.l && lPrime == remote.l -> maxOf(current.c, remote.c) + 1
            lPrime == current.l -> current.c + 1
            lPrime == remote.l -> remote.c + 1
            else -> 0
        }

        val skew = lPrime - pt
        if (skew > 60_000L) {
            onSkewWarning?.invoke(skew)
        }

        current = HLC(l = lPrime, c = cPrime, nodeId = nodeId)
        return current
    }
}
