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
if (import.meta.url === `file://${process.argv[1]}`) {
  const { app } = createGatewayApp();
  const PORT = Number(process.env.PORT) || 8080;
  app.listen({ port: PORT, host: '0.0.0.0' }, (err, address) => {
    if (err) {
      console.error(err);
      process.exit(1);
    }
    console.log(`🚀 Uber Telemetry Gateway running on ${address}`);
  });
}
