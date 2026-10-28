import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { createGatewayApp } from '../src/server.js';
import { WebSocket } from 'ws';

describe('Telemetry Gateway End-to-End Tests', () => {
  let app: any;
  let serverPort: number;

  beforeAll(async () => {
    const gateway = createGatewayApp({ scenarioIntervalMs: 500 });
    app = gateway.app;
    await app.listen({ port: 0, host: '127.0.0.1' });
    serverPort = app.server.address().port;
  });

  afterAll(async () => {
    await app.close();
  });

  it('GET /health returns status ok', async () => {
    const res = await app.inject({ method: 'GET', url: '/health' });
    expect(res.statusCode).toBe(200);
    expect(JSON.parse(res.payload)).toEqual(expect.objectContaining({ status: 'ok' }));
  });

  it('POST /v1/sync handles offline sync and AC-03 / AC-04 / AC-06', async () => {
    const testOpId1 = '0192f7a1-6b2e-7c11-9a3d-5e2f1c0b7d44';
    const testOpId2 = '0192f7a1-9c40-7e02-8b11-0d4a6f3e2c19';

    const syncPayload = {
      driverId: 'drv_7f3a91c2',
      deviceId: 'dev_a19c',
      sinceServerVersion: 30412,
      clientClock: { dev_a19c: 57, server: 30412 },
      ops: [
        {
          opId: testOpId1,
          entity: 'trip',
          entityId: 'trp_01J9ZJ4M2Q',
          field: 'status',
          crdt: 'max_ordinal',
          value: { status: 'ARRIVED_AT_PICKUP', ordinal: 3 },
          hlc: '1791460401222:0004:dev_a19c',
          vclock: { dev_a19c: 55, server: 30410 }
        },
        {
          opId: testOpId2,
          entity: 'trip',
          entityId: 'trp_01J9ZJ4M2Q',
          field: 'stopNotes',
          crdt: 'or_set_add',
          value: { tag: 'dev_a19c:56', text: 'Gate code 4471, use side entrance' },
          hlc: '1791460455870:0000:dev_a19c',
          vclock: { dev_a19c: 56, server: 30410 }
        }
      ]
    };

    // First call: both ops accepted
    const res1 = await app.inject({
      method: 'POST',
      url: '/v1/sync',
      payload: syncPayload
    });
    expect(res1.statusCode).toBe(200);
    const body1 = JSON.parse(res1.payload);
    expect(body1.accepted).toContain(testOpId1);
    expect(body1.accepted).toContain(testOpId2);
    // AC-03: Merged status is ARRIVED_AT_PICKUP
    expect(body1.mergedState.trip.status).toBe('ARRIVED_AT_PICKUP');
    expect(body1.mergedState.trip.ordinal).toBe(3);
    // AC-04: Both notes exist (the server's 'Call on arrival' and the client's 'Gate code 4471')
    expect(body1.mergedState.trip.stopNotes.length).toBeGreaterThanOrEqual(2);

    // AC-06: Send same ops again (simulating 3 retries) -> still accepted idempotently
    const res2 = await app.inject({
      method: 'POST',
      url: '/v1/sync',
      payload: syncPayload
    });
    expect(res2.statusCode).toBe(200);
    const body2 = JSON.parse(res2.payload);
    expect(body2.accepted).toContain(testOpId1);
  });

  it('WebSocket /v1/stream connects, authenticates, receives route and ping/pong', async () => {
    const ws = new WebSocket(`ws://127.0.0.1:${serverPort}/v1/stream?token=valid_test_jwt`);

    const receivedMessages: any[] = [];

    await new Promise<void>((resolve, reject) => {
      ws.on('open', () => {
        // Send a ping
        ws.send(JSON.stringify({
          v: 1,
          type: 'system.ping',
          seq: 1,
          ackSeq: 0,
          ts: Date.now(),
          traceId: '01J9ZK_TEST_PING',
          payload: {}
        }));
      });

      ws.on('message', (data) => {
        const msg = JSON.parse(data.toString());
        receivedMessages.push(msg);

        // Check if we got both route.update and system.pong
        const hasRoute = receivedMessages.some(m => m.type === 'route.update');
        const hasPong = receivedMessages.some(m => m.type === 'system.pong');

        if (hasRoute && hasPong) {
          ws.close();
          resolve();
        }
      });

      ws.on('error', reject);
    });

    const routeMsg = receivedMessages.find(m => m.type === 'route.update');
    expect(routeMsg).toBeDefined();
    expect(routeMsg.payload.polyline).toBe('m~ykAmzhxMcAkBy@}AsAmCgAyBk@iA');

    const pongMsg = receivedMessages.find(m => m.type === 'system.pong');
    expect(pongMsg).toBeDefined();
  });
});
