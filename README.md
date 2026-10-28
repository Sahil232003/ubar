# Real-Time Edge-AI Logistics & Dynamic Routing Platform
**MPE-INT-PRD-2026-14** | Mobile Platform Engineering, Uber

Production-grade driver-side client architecture and high-frequency telemetry gateway designed for network-hostile environments, sub-second dispatch synchronization, on-device edge AI, and battery preservation.

---

## 1. System Topology & Architecture

- **Client**: Kotlin Multiplatform (KMP) + Compose Multiplatform
  - `:shared:domain`: 100% pure Kotlin, zero platform imports. Contains entities, CRDT merge rules, Hybrid Logical Clock (HLC), Vector Clocks, Adaptive Sampling Engine, and Constant-Velocity Kalman Filter (ENU).
  - `:shared:data`: Remote WebSocket client (Ktor), serialization DTOs, SQLDelight SQLite database (WAL mode), and outbox dispatcher.
  - `:shared:presentation`: ViewModels (`MapViewModel`), unidirectional `MapUiState`, conflated hot path ($\le 60\text{ Hz}$), Compose UI cards and diagnostic overlays.
- **Telemetry Gateway**: Fastify + `@fastify/websocket` (Node.js & TypeScript)
  - `/v1/stream`: Persistent WebSocket stream with monotonic sequence tracking, piggybacked `ackSeq`, ping/pong heartbeat (15s/10s), and Redis-backed resumable buffer.
  - `/v1/sync`: REST delta sync endpoint with UUIDv7 idempotency deduplication table and CRDT merge engine.
  - **Scenario Engine**: High-fidelity Bangalore route track (MG Road $\rightarrow$ Koramangala 5th Block) replaying GPX telemetry and dynamic H3 resolution-8 surge price cycling (up to 20 msgs/s).

---

## 2. Quickstart & Verification

### Running the Telemetry Gateway
```bash
cd gateway
npm install
npm test       # Runs end-to-end integration and AC-03/04/06 acceptance tests
npm run dev    # Starts WebSocket and REST gateway on port 8080
```

### Running with Docker Compose (PostGIS + Redis)
```bash
cd gateway
docker compose up --build
```

---

## 3. Verified Acceptance Criteria

| Criteria ID | Requirement | Status |
|:---|:---|:---|
| **AC-03** | Device `ARRIVED` offline merges over server `EN_ROUTE` via ordinal lattice | **VERIFIED** |
| **AC-04** | Concurrent stop notes added on device and server survive via OR-Set union | **VERIFIED** |
| **AC-06** | Outbox op retry storms (same op sent 3x) applied idempotently exactly once | **VERIFIED** |
| **FR-03 / Sec 8.1** | Constant-Velocity Kalman filter rejects accuracy $> 50\text{ m}$ & speed $> 55\text{ m/s}$ | **VERIFIED** |
| **NFR-05 / Sec 11.1** | Adaptive sampling engine switches to `LOW_POWER` under battery/thermal triggers with 20s hysteresis | **VERIFIED** |
