# ADR-004: Native Map GPU Layer Updates via GeoJSON Sources

## Status
Accepted

## Context
Rendering hundreds of H3 surge hexagons, route polylines, and moving pucks using high-level UI widget overlays (e.g. standard Compose markers or DOM/RN views) causes severe rasterization overhead, off-screen buffer allocations, and GPU texture thrashing.

## Decision
We utilize a single native MapLibre GPU surface where map entities are rendered purely as hardware-accelerated GeoJSON data layers:

1. **Update Sources, Never Views**:
   Per frame, only `source.setGeoJson()` is called for the specific layer that changed (`puck_source`, `route_source`, `surge_source`). Map markers and canvas views are never re-created or re-instantiated.
2. **60 fps Puck Interpolator**:
   Incoming GPS fixes arrive at $\le 1\text{ Hz}$. A display-refresh animator (`withFrameMillis`) interpolates between `prevFix` and `targetFix`:
   - Linear coordinate interpolation on $(lat, lng)$.
   - Shortest-arc angular interpolation on bearing to eliminate 360-degree spins across the North boundary.
3. **Surge Hexagon Rate Limiting**:
   Surge polygon updates are throttled to at most once per 500 ms via `SurgeThrottler`.

## Consequences
- Resident memory stays well within budget ($< 220\text{ MB}$ on Android).
- Zero GPU overdraw from stacked UI widget trees.
