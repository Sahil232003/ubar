# ADR-005: Adaptive Location Sampling & Battery Budgets

## Status
Accepted

## Context
Constant high-accuracy GPS sampling drains 12–18% of mobile phone battery per hour, causing drivers to unplug or end shifts prematurely. PRD targets require:
- Active trip, screen on: $\le 6\%$ / hour
- Active trip, screen off: $\le 3\%$ / hour

## Decision
We implemented a pure domain policy engine (`AdaptiveSamplingEngine`) that dynamically switches between 4 sampling profiles:

| Profile | Conditions | Interval | Min Distance | Upload Cadence | Radio Wake Strategy |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **`HIGH_ACCURACY`** | Active trip (`EN_ROUTE`, `ARRIVED`, `ON_TRIP`) & in motion | 1 s | 5 m | Every 2 s | Continuous radio stream |
| **`BALANCED`** | `ONLINE_IDLE` & in motion | 5 s | 20 m | Every 15 s | Chip-level batching (`maxUpdateDelay = 15s`) |
| **`LOW_POWER`** | Stationary $> 60\text{ s}$, battery $< 15\%$, or thermal $\ge \text{serious}$ | 30 s | 100 m | Every 60 s | Deep sleep radio wakes |
| **`PASSIVE`** | Shift offline | 0 s | 0 m | None | Geofence / significant change only |

### Hysteresis Rule
To eliminate rapid oscillation between profiles when a driver stops at a red light, accuracy upgrades occur immediately, but accuracy downgrades require a **20-second continuous hold time** before the lower profile is activated.

## Consequences
- Reduces GPS radio on-time by over 60% during traffic halts and idling.
- Protects device thermals under heavy dashboard sunlight exposure.
