import { z } from 'zod';

/**
 * Section 7.1: WebSocket Envelope
 */
export const EnvelopeSchema = z.object({
  v: z.number().int().default(1),
  type: z.string(),
  seq: z.number().int(),
  ackSeq: z.number().int().default(0),
  ts: z.number().int(),
  traceId: z.string(),
  payload: z.record(z.any())
});

export type Envelope<T = Record<string, any>> = {
  v: number;
  type: string;
  seq: number;
  ackSeq: number;
  ts: number;
  traceId: string;
  payload: T;
};

/**
 * Section 7.2: Location Telemetry Batch (client -> server)
 */
export const LocationPointSchema = z.object({
  t: z.number().int(),
  lat: z.number(),
  lng: z.number(),
  accM: z.number(),
  spdMps: z.number(),
  brg: z.number(),
  alt: z.number().optional(),
  src: z.string().default("fused"),
  flt: z.string().optional()
});

export const LocationBatchPayloadSchema = z.object({
  driverId: z.string(),
  tripId: z.string().optional(),
  deviceState: z.object({
    batteryPct: z.number(),
    charging: z.boolean(),
    thermal: z.string(),
    network: z.string(),
    appState: z.string()
  }),
  samplingMode: z.enum(["HIGH_ACCURACY", "BALANCED", "LOW_POWER", "PASSIVE"]),
  points: z.array(LocationPointSchema),
  edge: z.object({
    etaCorrectionSec: z.number(),
    modelVersion: z.string(),
    confidence: z.number()
  }).optional()
});

export type LocationBatchPayload = z.infer<typeof LocationBatchPayloadSchema>;

/**
 * Section 7.3: Surge Pricing Update (server -> client)
 */
export const SurgeChangeItemSchema = z.object({
  h3: z.string(),
  mult: z.number(),
  demandIdx: z.number()
});

export const SurgePricingPayloadSchema = z.object({
  cityId: z.string(),
  h3Res: z.number().int().default(8),
  layerVersion: z.number().int(),
  baseVersion: z.number().int(),
  validUntil: z.number().int(),
  changed: z.array(SurgeChangeItemSchema),
  removed: z.array(z.string()).default([])
});

export type SurgePricingPayload = z.infer<typeof SurgePricingPayloadSchema>;

/**
 * Section 7.4: Route Update (server -> client)
 */
export const RouteManeuverSchema = z.object({
  idx: z.number().int(),
  type: z.string(),
  street: z.string(),
  distM: z.number()
});

export const RouteUpdatePayloadSchema = z.object({
  tripId: z.string(),
  routeVersion: z.number().int(),
  reason: z.string().optional(),
  polyline: z.string(),
  precision: z.number().int().default(5),
  distanceM: z.number(),
  etaSec: z.number().int(),
  maneuvers: z.array(RouteManeuverSchema).default([])
});

export type RouteUpdatePayload = z.infer<typeof RouteUpdatePayloadSchema>;

/**
 * Section 7.5: Offline Sync Push & Acknowledgment
 */
export const SyncOpSchema = z.object({
  opId: z.string(),
  entity: z.string(),
  entityId: z.string(),
  field: z.string(),
  crdt: z.enum(["max_ordinal", "lww", "or_set_add", "or_set_remove", "g_counter"]),
  value: z.any(),
  hlc: z.string(),
  vclock: z.record(z.number())
});

export const SyncRequestSchema = z.object({
  driverId: z.string(),
  deviceId: z.string(),
  sinceServerVersion: z.number().int(),
  clientClock: z.record(z.number()),
  ops: z.array(SyncOpSchema)
});

export type SyncRequest = z.infer<typeof SyncRequestSchema>;
export type SyncOp = z.infer<typeof SyncOpSchema>;

export interface SyncResponse {
  accepted: string[];
  rejected: string[];
  serverVersion: number;
  serverClock: Record<string, number>;
  changes: Array<{
    entity: string;
    entityId: string;
    field: string;
    crdt: string;
    value: any;
    hlc: string;
  }>;
  mergedState: Record<string, any>;
}

/**
 * Session Resume (client -> server)
 */
export const SessionResumePayloadSchema = z.object({
  driverId: z.string(),
  deviceId: z.string(),
  lastServerSeq: z.number().int(),
  lastClientSeqAcked: z.number().int()
});

export type SessionResumePayload = z.infer<typeof SessionResumePayloadSchema>;
