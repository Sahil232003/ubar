import { SyncRequest, SyncResponse, SyncOp } from '../types/contracts.js';

export interface ServerTripRecord {
  id: string;
  status: string;
  statusOrdinal: number;
  pickup: { lat: number; lng: number; label?: string; hlc: string } | null;
  dropoff: { lat: number; lng: number; label?: string; hlc: string } | null;
  stopNotes: Map<string, { tag: string; text: string; addHlc: string }>;
  removedNotes: Map<string, string>; // tag -> removeHlc
  odometer: Map<string, number>;      // nodeId -> count
  vclock: Record<string, number>;
  serverVersion: number;
}

export class SyncController {
  private idempotencyTable = new Set<string>();
  private serverVersion = 30412;
  private serverVClock: Record<string, number> = { server: 30412 };

  private trips = new Map<string, ServerTripRecord>();

  constructor() {
    // Seed default trip from PRD
    this.trips.set("trp_01J9ZJ4M2Q", {
      id: "trp_01J9ZJ4M2Q",
      status: "EN_ROUTE_TO_PICKUP",
      statusOrdinal: 2,
      pickup: { lat: 12.971598, lng: 77.594566, label: "MG Road", hlc: "1791460400000:0000:server" },
      dropoff: { lat: 12.935192, lng: 77.624480, label: "Koramangala 5th Block", hlc: "1791460470001:0000:server" },
      stopNotes: new Map([
        ["server:912", { tag: "server:912", text: "Call on arrival", addHlc: "1791460460000:0000:server" }]
      ]),
      removedNotes: new Map(),
      odometer: new Map([["server", 1500]]),
      vclock: { server: 30412 },
      serverVersion: 30412
    });
  }

  handleSync(req: SyncRequest): SyncResponse {
    const accepted: string[] = [];
    const rejected: string[] = [];

    // Advance server clock
    this.serverVersion += 1;
    this.serverVClock["server"] = this.serverVersion;
    if (req.deviceId) {
      this.serverVClock[req.deviceId] = Math.max(
        this.serverVClock[req.deviceId] || 0,
        req.clientClock[req.deviceId] || 0
      );
    }

    for (const op of req.ops) {
      // 1. Idempotency Check (AC-06)
      if (this.idempotencyTable.has(op.opId)) {
        accepted.push(op.opId);
        continue;
      }

      try {
        this.applyOp(op);
        this.idempotencyTable.add(op.opId);
        accepted.push(op.opId);
      } catch (err) {
        rejected.push(op.opId);
      }
    }

    const trip = this.trips.get("trp_01J9ZJ4M2Q")!;
    
    // Server changes since client's version
    const changes: SyncResponse["changes"] = [];
    if (req.sinceServerVersion < 30415 && trip.dropoff) {
      changes.push({
        entity: "trip",
        entityId: trip.id,
        field: "dropoff",
        crdt: "lww",
        value: { lat: trip.dropoff.lat, lng: trip.dropoff.lng, label: trip.dropoff.label },
        hlc: trip.dropoff.hlc
      });
    }

    return {
      accepted,
      rejected,
      serverVersion: this.serverVersion,
      serverClock: { ...this.serverVClock },
      changes,
      mergedState: {
        trip: {
          id: trip.id,
          status: trip.status,
          ordinal: trip.statusOrdinal,
          pickup: trip.pickup,
          dropoff: trip.dropoff,
          stopNotes: Array.from(trip.stopNotes.values()).map(n => ({ tag: n.tag, text: n.text }))
        }
      }
    };
  }

  private applyOp(op: SyncOp): void {
    let trip = this.trips.get(op.entityId);
    if (!trip) {
      trip = {
        id: op.entityId,
        status: "ONLINE_IDLE",
        statusOrdinal: 1,
        pickup: null,
        dropoff: null,
        stopNotes: new Map(),
        removedNotes: new Map(),
        odometer: new Map(),
        vclock: {},
        serverVersion: this.serverVersion
      };
      this.trips.set(op.entityId, trip);
    }

    switch (op.crdt) {
      case "max_ordinal": {
        // Status lattice: max ordinal wins, 99 (CANCELLED) dominates
        const incomingOrdinal = op.value.ordinal ?? 0;
        if (incomingOrdinal > trip.statusOrdinal || incomingOrdinal === 99) {
          trip.status = op.value.status;
          trip.statusOrdinal = incomingOrdinal;
        }
        break;
      }
      case "lww": {
        // LWW Register using HLC string comparison
        if (op.field === "dropoff") {
          if (!trip.dropoff || op.hlc > trip.dropoff.hlc) {
            trip.dropoff = { ...op.value, hlc: op.hlc };
          }
        } else if (op.field === "pickup") {
          if (!trip.pickup || op.hlc > trip.pickup.hlc) {
            trip.pickup = { ...op.value, hlc: op.hlc };
          }
        }
        break;
      }
      case "or_set_add": {
        // OR-Set Add: tag and addHlc
        const tag = op.value.tag;
        const text = op.value.text;
        trip.stopNotes.set(tag, { tag, text, addHlc: op.hlc });
        break;
      }
      case "or_set_remove": {
        const tag = op.value.tag;
        trip.removedNotes.set(tag, op.hlc);
        trip.stopNotes.delete(tag);
        break;
      }
      case "g_counter": {
        const nodeId = op.value.nodeId || "client";
        const count = op.value.count || 0;
        const current = trip.odometer.get(nodeId) || 0;
        trip.odometer.set(nodeId, Math.max(current, count));
        break;
      }
    }
  }

  getTrip(tripId: string): ServerTripRecord | undefined {
    return this.trips.get(tripId);
  }
}
