import Fastify from 'fastify';
import fastifyWebsocket from '@fastify/websocket';
import fastifyCors from '@fastify/cors';
import { WebSocket } from 'ws';
import {
  Envelope,
  EnvelopeSchema,
  LocationBatchPayloadSchema,
  SessionResumePayloadSchema,
  SyncRequestSchema
} from './types/contracts.js';
import { InMemoryResumeBuffer } from './buffer/ResumeBuffer.js';
import { ScenarioEngine } from './scenario/ScenarioEngine.js';
import { SyncController } from './sync/SyncController.js';

export function createGatewayApp(options: { scenarioIntervalMs?: number } = {}) {
  const app = Fastify({ logger: true });
  app.register(fastifyCors, { origin: true });
  app.register(fastifyWebsocket);

  const syncController = new SyncController();
  const resumeBuffer = new InMemoryResumeBuffer(500);
  const scenarioEngine = new ScenarioEngine();

  let serverSeq = 48200;
  let lastClientSeqAcked = 0;

  // REST Health check
  app.get('/health', async () => ({ status: 'ok', uptime: process.uptime() }));

  // Interactive Web Testing Dashboard
  app.get('/', async (request, reply) => {
    reply.type('text/html').send(`<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <title>Uber Real-Time Telemetry & Dispatch Gateway</title>
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <style>
    * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
    body { background: #0f172a; color: #f8fafc; padding: 24px; }
    .header { display: flex; justify-content: space-between; align-items: center; border-bottom: 1px solid #334155; padding-bottom: 16px; margin-bottom: 24px; }
    .title { font-size: 20px; font-weight: 700; color: #38bdf8; display: flex; align-items: center; gap: 10px; }
    .badge { padding: 4px 10px; border-radius: 9999px; font-size: 12px; font-weight: 700; }
    .badge-live { background: #10b981; color: #fff; }
    .badge-off { background: #ef4444; color: #fff; }
    .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(340px, 1fr)); gap: 20px; }
    .card { background: #1e293b; border: 1px solid #334155; border-radius: 12px; padding: 20px; }
    .card-title { font-size: 14px; font-weight: 700; color: #94a3b8; text-transform: uppercase; margin-bottom: 12px; display: flex; justify-content: space-between; }
    .metric { font-size: 28px; font-weight: 800; color: #f8fafc; margin: 4px 0 12px; }
    .btn { background: #0284c7; color: white; border: none; padding: 10px 16px; border-radius: 8px; font-weight: 600; cursor: pointer; transition: 0.2s; font-size: 13px; }
    .btn:hover { background: #0369a1; }
    .btn-emerald { background: #059669; }
    .btn-emerald:hover { background: #047857; }
    .btn-amber { background: #d97706; }
    .btn-amber:hover { background: #b45309; }
    .btn-group { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 12px; }
    .feed { max-height: 240px; overflow-y: auto; background: #090d16; border-radius: 8px; padding: 12px; font-family: monospace; font-size: 12px; line-height: 1.5; color: #cbd5e1; }
    .feed-item { margin-bottom: 6px; border-bottom: 1px solid #1e293b; padding-bottom: 4px; }
    .surge-tag { display: inline-block; background: #b45309; color: #fef3c7; border-radius: 4px; padding: 2px 6px; font-size: 11px; margin: 2px; }
  </style>
</head>
<body>
  <div class="header">
    <div class="title">
      <span>🚗</span> Uber Real-Time Telemetry & Scenario Gateway
    </div>
    <div>
      <span id="conn-badge" class="badge badge-off">CONNECTING...</span>
    </div>
  </div>

  <div class="grid">
    <!-- Active Route Card -->
    <div class="card">
      <div class="card-title">Active Route & Dispatch</div>
      <div id="route-dest" class="metric">MG Road → Koramangala</div>
      <p style="color: #94a3b8; font-size: 13px;" id="route-stats">Distance: 4,820m | Server ETA: 742s</p>
      <div style="margin-top: 12px;">
        <span style="font-size: 12px; color: #64748b;">Turn Maneuvers:</span>
        <div id="maneuvers-list" style="font-size: 13px; margin-top: 4px; line-height: 1.6;">
          • MG Road (500m)<br>• Turn Right: 100 Feet Rd (2,200m)<br>• Arrive: Koramangala 5th Block (2,120m)
        </div>
      </div>
    </div>

    <!-- Surge Pricing Card -->
    <div class="card">
      <div class="card-title">
        <span>H3 Surge Multipliers (Res 8)</span>
        <span id="surge-version" style="font-size: 11px; color: #38bdf8;">Layer v9021</span>
      </div>
      <div id="surge-rate" class="metric">1.8x Peak</div>
      <div id="surge-cells">
        <span class="surge-tag">8861892593fffff : 1.8x</span>
        <span class="surge-tag">88618925b7fffff : 1.4x</span>
      </div>
      <p style="color: #64748b; font-size: 12px; margin-top: 12px;">Updates dynamically from the Scenario Engine every 3 seconds.</p>
    </div>

    <!-- Interactive Actions & Offline Sync -->
    <div class="card">
      <div class="card-title">Client Simulator & CRDT Sync</div>
      <p style="font-size: 13px; color: #94a3b8;">Simulate driver telemetry actions and offline synchronization:</p>
      <div class="btn-group">
        <button class="btn btn-emerald" onclick="sendOfflineSync()">Trigger Offline Sync (/v1/sync)</button>
        <button class="btn btn-amber" onclick="sendPing()">Send Heartbeat (Ping)</button>
        <button class="btn" onclick="sendLocationBatch()">Upload GPS Batch (10 pts)</button>
      </div>
      <div id="action-result" style="margin-top: 14px; font-size: 12px; color: #38bdf8; min-height: 20px;"></div>
    </div>

    <!-- Live Stream Log -->
    <div class="card" style="grid-column: 1 / -1;">
      <div class="card-title">Live WebSocket Frame Inspector (/v1/stream)</div>
      <div class="feed" id="feed"></div>
    </div>
  </div>

  <script>
    const feed = document.getElementById('feed');
    const badge = document.getElementById('conn-badge');
    const actionResult = document.getElementById('action-result');

    function log(msg, color='#38bdf8') {
      const el = document.createElement('div');
      el.className = 'feed-item';
      el.style.color = color;
      el.textContent = '[' + new Date().toLocaleTimeString() + '] ' + msg;
      feed.prepend(el);
    }

    const ws = new WebSocket((location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/v1/stream?token=browser_jwt');

    ws.onopen = () => {
      badge.textContent = 'LIVE STREAMING';
      badge.className = 'badge badge-live';
      log('WebSocket connected & authenticated to Telemetry Gateway!', '#10b981');
    };

    ws.onclose = () => {
      badge.textContent = 'DISCONNECTED';
      badge.className = 'badge badge-off';
      log('WebSocket disconnected.', '#ef4444');
    };

    ws.onmessage = (e) => {
      const frame = JSON.parse(e.data);
      log('INBOUND [' + frame.type + '] Seq: ' + frame.seq + ' Ack: ' + frame.ackSeq, '#e2e8f0');

      if (frame.type === 'pricing.surge') {
        document.getElementById('surge-version').textContent = 'Layer v' + frame.payload.layerVersion;
        const highest = Math.max(...frame.payload.changed.map(c => c.mult), 1.0);
        document.getElementById('surge-rate').textContent = highest.toFixed(1) + 'x Peak';
        const cellsDiv = document.getElementById('surge-cells');
        cellsDiv.innerHTML = frame.payload.changed.map(c => '<span class="surge-tag">' + c.h3 + ' : ' + c.mult.toFixed(1) + 'x</span>').join('');
      } else if (frame.type === 'route.update') {
        document.getElementById('route-stats').textContent = 'Distance: ' + frame.payload.distanceM + 'm | ETA: ' + frame.payload.etaSec + 's';
      }
    };

    window.sendPing = function() {
      ws.send(JSON.stringify({ v: 1, type: 'system.ping', seq: 100, ackSeq: 0, ts: Date.now(), traceId: 'UI_PING', payload: {} }));
      actionResult.textContent = 'Sent system.ping -> waiting for system.pong';
    };

    window.sendLocationBatch = function() {
      ws.send(JSON.stringify({
        v: 1, type: 'telemetry.location.batch', seq: 101, ackSeq: 0, ts: Date.now(), traceId: 'UI_LOC',
        payload: {
          driverId: 'drv_7f3a91c2',
          deviceState: { batteryPct: 78, charging: false, thermal: 'nominal', network: 'cellular_5g', appState: 'foreground' },
          samplingMode: 'HIGH_ACCURACY',
          points: [
            { t: Date.now() - 1000, lat: 12.971598, lng: 77.594566, accM: 4.8, spdMps: 11.2, brg: 88.5, src: 'fused', flt: 'kalman_v2' }
          ]
        }
      }));
      actionResult.textContent = 'Uploaded batched GPS location fix with Kalman metadata!';
    };

    window.sendOfflineSync = async function() {
      actionResult.textContent = 'Submitting offline operations to /v1/sync...';
      const res = await fetch('/v1/sync', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          driverId: 'drv_7f3a91c2',
          deviceId: 'dev_a19c',
          sinceServerVersion: 30412,
          clientClock: { dev_a19c: 57, server: 30412 },
          ops: [
            {
              opId: 'browser-op-' + Date.now(),
              entity: 'trip',
              entityId: 'trp_01J9ZJ4M2Q',
              field: 'status',
              crdt: 'max_ordinal',
              value: { status: 'ARRIVED_AT_PICKUP', ordinal: 3 },
              hlc: Date.now() + ':0001:dev_a19c',
              vclock: { dev_a19c: 58, server: 30412 }
            }
          ]
        })
      });
      const data = await res.json();
      actionResult.textContent = 'Sync Success! Accepted: ' + data.accepted.join(', ') + ' | Merged Status: ' + data.mergedState.trip.status;
    };
  </script>
</body>
</html>`);
  });

  // REST /v1/sync endpoint (Section 7.5)
  app.post('/v1/sync', async (request, reply) => {
    const parseResult = SyncRequestSchema.safeParse(request.body);
    if (!parseResult.success) {
      return reply.status(400).send({ error: 'Invalid sync payload', details: parseResult.error.format() });
    }
    const response = syncController.handleSync(parseResult.data);
    return reply.status(200).send(response);
  });

  // WebSocket /v1/stream endpoint (Section 7.1 - 7.6)
  app.register(async (fastify) => {
    fastify.get('/v1/stream', { websocket: true }, (socket: WebSocket, req) => {
      // Basic JWT / Auth check (allow test tokens)
      const token = req.headers.authorization || (req.query as any)?.token;
      if (!token) {
        socket.close(4001, 'Unauthorized: Missing JWT');
        return;
      }

      function sendEnvelope(type: string, payload: Record<string, any>) {
        if (socket.readyState !== WebSocket.OPEN) return;
        serverSeq += 1;
        const envelope: Envelope = {
          v: 1,
          type,
          seq: serverSeq,
          ackSeq: lastClientSeqAcked,
          ts: Date.now(),
          traceId: `01J9ZK${serverSeq.toString(36).toUpperCase()}`,
          payload
        };
        resumeBuffer.push(envelope);
        socket.send(JSON.stringify(envelope));
      }

      // Initial route update on connect
      sendEnvelope('route.update', scenarioEngine.generateRouteUpdate('trp_01J9ZJ4M2Q'));

      // Periodic Surge Pricing Stream from Scenario Engine
      const surgeInterval = setInterval(() => {
        sendEnvelope('pricing.surge', scenarioEngine.generateSurgeCycle());
      }, options.scenarioIntervalMs ?? 3000);

      socket.on('message', (data: Buffer | string) => {
        try {
          const raw = JSON.parse(data.toString());
          const env = EnvelopeSchema.parse(raw);

          // Update acked sequence
          if (env.seq > lastClientSeqAcked) {
            lastClientSeqAcked = env.seq;
          }

          // Handle message types
          switch (env.type) {
            case 'telemetry.location.batch': {
              const batch = LocationBatchPayloadSchema.parse(env.payload);
              // Send sync ack
              sendEnvelope('sync.ack', {
                batchCount: batch.points.length,
                lastPointTime: batch.points[batch.points.length - 1]?.t
              });
              break;
            }

            case 'session.resume': {
              const resume = SessionResumePayloadSchema.parse(env.payload);
              const { frames, gapDetected } = resumeBuffer.replay(resume.lastServerSeq);
              if (gapDetected) {
                sendEnvelope('resume.gap', { lastServerSeq: serverSeq });
              } else {
                for (const frame of frames) {
                  socket.send(JSON.stringify(frame));
                }
              }
              break;
            }

            case 'system.ping': {
              sendEnvelope('system.pong', { clientTs: env.ts });
              break;
            }

            case 'pricing.snapshot.request': {
              sendEnvelope('pricing.surge', scenarioEngine.generateSurgeCycle());
              break;
            }
          }
        } catch (err) {
          app.log.warn({ err }, 'Invalid message envelope received');
        }
      });

      socket.on('close', () => {
        clearInterval(surgeInterval);
      });
    });
  });

  return { app, syncController, resumeBuffer, scenarioEngine };
}

// Start standalone server when executed directly
export async function startServer(port: number = 8080) {
  const { app } = createGatewayApp();
  const PORT = Number(process.env.PORT) || port;
  await app.listen({ port: PORT, host: '0.0.0.0' });
  console.log(`🚀 Uber Telemetry Gateway running on http://localhost:${PORT}`);
  return app;
}

// Check if running as main script
const currentFile = import.meta.url.replace(/\\/g, '/');
const entryArg = (process.argv[1] || '').replace(/\\/g, '/');
if (currentFile.includes(entryArg) || entryArg.includes('server')) {
  startServer().catch((err) => {
    console.error('Fatal startup error:', err);
    process.exit(1);
  });
}
