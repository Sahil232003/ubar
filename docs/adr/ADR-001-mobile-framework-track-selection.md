# ADR-001: Mobile Framework Track Selection — Kotlin Multiplatform (KMP)

## Status
Accepted

## Context
The Uber driver mobile client is a high-frequency real-time application requiring persistent background GPS tracking, local CRDT synchronization, and on-device Edge-AI filtering under strict memory and battery budgets. Three tracks were evaluated:
- Track A: Kotlin Multiplatform + Compose Multiplatform
- Track B: Flutter (Melos, Drift, Isolates, Pigeon)
- Track C: React Native New Architecture (TurboModules, JSI, Fabric, Reanimated)

## Decision
We selected **Option A (Kotlin Multiplatform + Compose Multiplatform)**.

### Rationale
1. **Direct Android Foreground Service (FGS) Execution**:
   Android battery managers (Doze, OEM killers on Xiaomi/Samsung) aggressively kill non-native background runtimes. In KMP, the pure Kotlin domain layer and tracking pipeline run directly inside the native Android `TrackingService` (`foregroundServiceType="location"`) without cross-bridge marshalling or the risk of background VM pausing (which affects Flutter and React Native JS threads).
2. **Pure Clean Architecture Domain**:
   Kotlin Multiplatform allows the entire `:shared:domain` module to compile to native code with **zero platform imports**. Business rules, CRDT merges, Kalman filter equations, and clock algorithms are completely platform-agnostic and unit-tested in JVM/Native environments.
3. **Coroutines & Structural Concurrency**:
   Kotlin Flows with `.conflate()`, `Dispatchers.Default.limitedParallelism(1)` for serial outbox writers, and channels with `BufferOverflow.DROP_OLDEST` map directly onto our multi-threaded telemetry pipeline.

## Consequences
- Requires JDK 17/21 and Android SDK for local mobile builds.
- iOS platform map view is embedded via UIKitView interop.
