package com.uber.edge.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class TripStatus(val ordinalValue: Int) {
    OFFLINE(0),
    ONLINE_IDLE(1),
    EN_ROUTE_TO_PICKUP(2),
    ARRIVED_AT_PICKUP(3),
    ON_TRIP(4),
    COMPLETED(5),
    CANCELLED(99);

    companion object {
        fun fromOrdinal(ordinal: Int): TripStatus {
            return entries.firstOrNull { it.ordinalValue == ordinal }
                ?: if (ordinal >= 99) CANCELLED else OFFLINE
        }

        /**
         * Resolves status transition per the PRD lattice rule:
         * max(local.ordinal, remote.ordinal); CANCELLED (ordinal 99) dominates.
         */
        fun merge(local: TripStatus, remote: TripStatus): TripStatus {
            return if (remote.ordinalValue > local.ordinalValue) remote else local
        }
    }
}
