import { RouteUpdatePayload, SurgePricingPayload } from '../types/contracts.js';

export interface ScenarioPoint {
  lat: number;
  lng: number;
  spdMps: number;
  brg: number;
  accM: number;
}

/**
 * High-fidelity Bangalore route track (MG Road to Koramangala 5th Block via 100 Feet Rd).
 */
export const BANGALORE_TEST_TRACK: ScenarioPoint[] = [
  { lat: 12.971598, lng: 77.594566, spdMps: 0.0, brg: 87.5, accM: 5.2 },
  { lat: 12.971603, lng: 77.594671, spdMps: 5.4, brg: 88.0, accM: 4.8 },
  { lat: 12.971620, lng: 77.595010, spdMps: 11.2, brg: 88.2, accM: 5.0 },
  { lat: 12.971650, lng: 77.596100, spdMps: 12.5, brg: 89.0, accM: 6.1 },
  { lat: 12.971700, lng: 77.598200, spdMps: 13.8, brg: 88.5, accM: 5.5 },
  { lat: 12.971800, lng: 77.602500, spdMps: 14.2, brg: 90.0, accM: 4.9 },
  { lat: 12.971900, lng: 77.607800, spdMps: 12.0, brg: 91.0, accM: 5.3 },
  { lat: 12.969500, lng: 77.612000, spdMps: 10.5, brg: 135.0, accM: 6.0 },
  { lat: 12.965000, lng: 77.618000, spdMps: 11.8, brg: 140.0, accM: 5.1 },
  { lat: 12.955000, lng: 77.621000, spdMps: 13.0, brg: 165.0, accM: 4.7 },
  { lat: 12.945000, lng: 77.623000, spdMps: 12.4, brg: 175.0, accM: 5.8 },
  { lat: 12.935192, lng: 77.624480, spdMps: 0.0, brg: 180.0, accM: 4.2 }
];

export class ScenarioEngine {
  private currentTrackIdx = 0;
  private surgeLayerVersion = 9020;
  private routeVersion = 1;

  getNextTrackPoint(): ScenarioPoint {
    const point = BANGALORE_TEST_TRACK[this.currentTrackIdx];
    this.currentTrackIdx = (this.currentTrackIdx + 1) % BANGALORE_TEST_TRACK.length;
    return point;
  }

  generateSurgeCycle(): SurgePricingPayload {
    const base = this.surgeLayerVersion;
    this.surgeLayerVersion++;
    const multipliers = [1.3, 1.5, 1.8, 2.1, 2.4];
    const mult1 = multipliers[Math.floor(Math.random() * multipliers.length)];
    const mult2 = multipliers[Math.floor(Math.random() * multipliers.length)];

    return {
      cityId: "blr",
      h3Res: 8,
      layerVersion: this.surgeLayerVersion,
      baseVersion: base,
      validUntil: Date.now() + 120_000,
      changed: [
        { h3: "8861892593fffff", mult: mult1, demandIdx: Math.min(1.0, mult1 / 2.5) },
        { h3: "88618925b7fffff", mult: mult2, demandIdx: Math.min(1.0, mult2 / 2.5) }
      ],
      removed: base % 5 === 0 ? ["886189259bfffff"] : []
    };
  }

  generateRouteUpdate(tripId: string, reason: string = "normal"): RouteUpdatePayload {
    this.routeVersion++;
    return {
      tripId,
      routeVersion: this.routeVersion,
      reason,
      polyline: "m~ykAmzhxMcAkBy@}AsAmCgAyBk@iA", // Encoded polyline from PRD
      precision: 5,
      distanceM: 4820,
      etaSec: 742,
      maneuvers: [
        { idx: 1, type: "depart", street: "MG Road", distM: 500 },
        { idx: 2, type: "turn_right", street: "100 Feet Rd", distM: 2200 },
        { idx: 3, type: "arrive", street: "Koramangala 5th Block", distM: 2120 }
      ]
    };
  }
}
