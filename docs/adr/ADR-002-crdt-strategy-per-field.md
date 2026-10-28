# ADR-002: Field-Specific Conflict-Free Replicated Data Types (CRDTs)

## Status
Accepted

## Context
Driver mobile clients operate in network-hostile conditions (basements, tunnels, highway dead zones). A generic "last-write-wins" rule across an entire record is unacceptable because concurrent offline edits (e.g. driver marking "Arrived" while dispatch reroutes drop-off and adds a gate code) would clobber critical revenue and safety data.

## Decision
We implemented field-specific CRDT semantics matching the exact business lifecycle of each field (PRD Section 10.4):

| Field | CRDT Type | Merge Semantic | Invariant Guaranteed |
| :--- | :--- | :--- | :--- |
| `trip.status` | **Max-Ordinal Lattice** | `max(local.ord, remote.ord)` with `CANCELLED = 99` domination | Monotonic state progression; trip states only move forward; cancellation always wins |
| `trip.dropoff`, `trip.pickup` | **LWW-Register (HLC)** | Higher Hybrid Logical Clock (`<ms>:<c>:<node>`) wins | Single logical owner per address edit; immune to wall-clock drift |
| `trip.stopNotes` | **OR-Set (Add-Wins)** | $\text{Adds} = A_{\text{adds}} \cup B_{\text{adds}}$, $\text{Removes} = A_{\text{rem}} \cup B_{\text{rem}}$ | Driver and dispatch notes added concurrently offline both survive without loss |
| `trip.odometerMeters` | **G-Counter** | $\forall k, \max(\text{local}[k], \text{remote}[k])$ | Odometer distance accumulates monotonically without double-counting |
| `location_points` | **Append-Only G-Set** | Keyed by `(driverId, t)` | Immutable spatial facts; deduplicated on ingest |

### Mathematical Properties Verified
All CRDT merge operations satisfy:
1. **Commutativity**: $A \star B = B \star A$
2. **Associativity**: $(A \star B) \star C = A \star (B \star C)$
3. **Idempotency**: $A \star A = A$

## Consequences
- Requires persistent HLC and vector clock state in local SQLite (`sync_meta`).
- Increases wire format slightly to carry causal tags (`tag`, `addHlc`), but guarantees zero lost operational mutations.
