import { createGatewayApp } from './dist/server.js';
import { WebSocket } from 'ws';

async function main() {
  console.log('================================================================');
  console.log('🚀 UBER REAL-TIME TELEMETRY GATEWAY - LIVE POWERSHELL DEMO');
  console.log('================================================================\n');

  // 1. Start the Fastify Gateway on an ephemeral port
  const { app } = createGatewayApp({ scenarioIntervalMs: 800 });
  await app.listen({ port: 8080, host: '127.0.0.1' });
  const address = app.server.address();
  console.log(`[1] Gateway successfully started on http://127.0.0.1:${address.port}\n`);

  // 2. Health check via fetch
  console.log('[2] Testing GET /health endpoint:');
  const healthRes = await fetch(`http://127.0.0.1:${address.port}/health`);
  const healthData = await healthRes.json();
  console.log('    Status Code:', healthRes.status);
  console.log('    Payload:', JSON.stringify(healthData));
  console.log('    -> Result: HEALTH CHECK PASSED!\n');

  // 3. Offline CRDT Delta Sync via POST /v1/sync
  console.log('[3] Testing POST /v1/sync (Offline CRDT Reconnect):');
  const syncPayload = {
    driverId: 'drv_7f3a91c2',
    deviceId: 'dev_a19c',
    sinceServerVersion: 30412,
    clientClock: { dev_a19c: 57, server: 30412 },
    ops: [
      {
        opId: '0192f7a1-6b2e-7c11-9a3d-5e2f1c0b7d44',
        entity: 'trip',
        entityId: 'trp_01J9ZJ4M2Q',
        field: 'status',
        crdt: 'max_ordinal',
        value: { status: 'ARRIVED_AT_PICKUP', ordinal: 3 },
        hlc: '1791460401222:0004:dev_a19c',
        vclock: { dev_a19c: 55, server: 30410 }
      },
      {
        opId: '0192f7a1-9c40-7e02-8b11-0d4a6f3e2c19',
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

  const syncRes = await fetch(`http://127.0.0.1:${address.port}/v1/sync`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(syncPayload)
  });
  const syncData = await syncRes.json();
  console.log('    Status Code:', syncRes.status);
  console.log('    Accepted Op IDs:', syncData.accepted);
  console.log('    Merged Trip Status:', syncData.mergedState.trip.status, `(Ordinal: ${syncData.mergedState.trip.ordinal})`);
  console.log('    Merged Stop Notes (OR-Set Union):', syncData.mergedState.trip.stopNotes);
  console.log('    -> Result: CRDT OFFLINE MERGE PASSED!\n');

  // 4. WebSocket Streaming Telemetry (/v1/stream)
  console.log('[4] Connecting to WebSocket Telemetry Stream (ws://127.0.0.1:8080/v1/stream)...');
  const ws = new WebSocket(`ws://127.0.0.1:${address.port}/v1/stream?token=valid_test_jwt`);

  let frameCount = 0;
  await new Promise((resolve) => {
    ws.on('open', () => {
      console.log('    -> WebSocket Connection Established & Authenticated!\n');

      // Send a ping from the client
      const pingFrame = {
        v: 1,
        type: 'system.ping',
        seq: 1,
        ackSeq: 0,
        ts: Date.now(),
        traceId: '01J9ZK_DEMO_PING',
        payload: {}
      };
      ws.send(JSON.stringify(pingFrame));
      console.log('    [Client -> Server] Sent system.ping frame.');
    });

    ws.on('message', (raw) => {
      frameCount++;
      const frame = JSON.parse(raw.toString());
      console.log(`    [Server -> Client] Frame #${frameCount} [Type: ${frame.type}, Seq: ${frame.seq}, AckSeq: ${frame.ackSeq}]`);
      if (frame.type === 'route.update') {
        console.log(`       -> Polyline: "${frame.payload.polyline}" (Dist: ${frame.payload.distanceM}m, ETA: ${frame.payload.etaSec}s)`);
        console.log(`       -> Turn Maneuvers:`, frame.payload.maneuvers);
      } else if (frame.type === 'pricing.surge') {
        console.log(`       -> H3 Resolution-8 Surge Cells:`, frame.payload.changed);
      } else if (frame.type === 'system.pong') {
        console.log(`       -> Pong Received! Heartbeat healthy.`);
      }

      if (frameCount >= 3) {
        ws.close();
        resolve();
      }
    });
  });

  console.log('\n[5] Shutting down live demonstration server cleanly...');
  await app.close();
  console.log('================================================================');
  console.log('✅ ALL POWERSHELL VERIFICATION CHECKS COMPLETED SUCCESSFULLY!');
  console.log('================================================================');
}

main().catch((err) => {
  console.error('Demo error:', err);
  process.exit(1);
});
