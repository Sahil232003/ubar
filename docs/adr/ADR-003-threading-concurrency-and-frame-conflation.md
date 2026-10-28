# ADR-003: High-Frequency Concurrency, Conflation & Frame Budgets

## Status
Accepted

## Context
At peak surge and high-density dispatch, inbound WebSocket traffic reaches up to 20 msgs/s. Performing JSON parsing, state tree manipulation, and map redraws on the UI/Main thread causes frame drops ($< 58\text{ fps}$), UI jank, and unresponsive touch handling (e.g. driver missing the "Arrived" button while driving).

## Decision
We adopted a multi-stage off-main-thread ingestion pipeline with strict per-stage time budgets:

```
[Socket I/O Thread]       [Worker Dispatcher]        [Domain / State Worker]          [UI Main Thread]
  WS frame bytes    ──▶   Envelope decode      ──▶   Entity mapping & Kalman   ──▶   Frame Conflation
  (Budget < 1 ms)         (Budget < 4 ms)            (Budget < 2 ms)                 (Budget < 4 ms)
```

### Key Technical Mechanisms
1. **Bounded Ring Buffers & Backpressure**:
   Inbound WebSocket channel capacity is capped at 256 frames. For surge pricing, channel uses `BufferOverflow.DROP_OLDEST` (conflation), while critical events (`route.update`, `trip.state`, `sync.ack`) use reliable buffering.
2. **State Conflation**:
   The `MapViewModel` combines data streams and applies `.conflate()`. Only the most recent combined `MapUiState` per display refresh cycle reaches the UI. Intermediate states are dropped, never queued.
3. **Serial Outbox Concurrency**:
   Outbox writes and HLC increments run on `Dispatchers.IO.limitedParallelism(1)` to eliminate race conditions between concurrent user intents and background telemetry uploads.

## Consequences
- Guaranteed sub-8ms UI thread workload per frame.
- Stale surge and position updates never accumulate in memory.
