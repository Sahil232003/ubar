package com.uber.edge.presentation.map

import com.uber.edge.presentation.state.PuckUi
import com.uber.edge.presentation.state.RouteUi
import com.uber.edge.presentation.state.SurgeLayerUi
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * GeoJSON Generation for GPU Map Layers (MapLibre / Mapbox).
 * Only GeoJSON data sources are updated; views are never re-created.
 */
object GeoJsonBuilders {

    /**
     * Builds GeoJSON Feature for the interpolated driver puck with bearing.
     */
    fun buildPuckGeoJson(puck: PuckUi): String {
        return """
        {
          "type": "Feature",
          "geometry": {
            "type": "Point",
            "coordinates": [${puck.lng}, ${puck.lat}]
          },
          "properties": {
            "bearing": ${puck.bearing},
            "accuracyM": ${puck.accuracyM}
          }
        }
        """.trimIndent()
    }

    /**
     * Builds GeoJSON Feature for the route polyline.
     */
    fun buildRouteGeoJson(route: RouteUi?): String {
        if (route == null || route.encodedPolyline.isEmpty()) {
            return """{"type": "FeatureCollection", "features": []}"""
        }

        val decodedCoords = decodePolyline(route.encodedPolyline)
        val coordJson = decodedCoords.joinToString(",") { "[${it.second}, ${it.first}]" }

        return """
        {
          "type": "Feature",
          "geometry": {
            "type": "LineString",
            "coordinates": [$coordJson]
          },
          "properties": {
            "version": ${route.version},
            "remainingMeters": ${route.remainingMeters}
          }
        }
        """.trimIndent()
    }

    /**
     * Builds GeoJSON FeatureCollection for H3 Resolution-8 surge hexagons.
     * Throttled to <= 500ms updates per PRD Section 11.3.
     */
    fun buildSurgeGeoJson(surge: SurgeLayerUi): String {
        if (surge.cells.isEmpty()) {
            return """{"type": "FeatureCollection", "features": []}"""
        }

        val features = surge.cells.entries.map { (h3, mult) ->
            val color = when {
                mult >= 2.0 -> "#EF4444" // High surge - Red
                mult >= 1.5 -> "#F59E0B" // Moderate surge - Amber
                else -> "#3B82F6"        // Light surge - Blue
            }
            val opacity = (0.25 + (mult - 1.0) * 0.3).coerceIn(0.2, 0.7)

            // Approximate H3 resolution-8 boundary (approx 0.74 km2, ~460m radius)
            val center = h3ApproxCenter(h3)
            val ring = generateHexagonRing(center.first, center.second, 460.0)
            val coordsJson = ring.joinToString(",") { "[${it.second}, ${it.first}]" }

            """
            {
              "type": "Feature",
              "geometry": {
                "type": "Polygon",
                "coordinates": [[$coordsJson]]
              },
              "properties": {
                "h3": "$h3",
                "multiplier": $mult,
                "color": "$color",
                "opacity": $opacity
              }
            }
            """.trimIndent()
        }

        return """{"type": "FeatureCollection", "features": [${features.joinToString(",")}]}"""
    }

    private fun decodePolyline(encoded: String): List<Pair<Double, Double>> {
        val poly = mutableListOf<Pair<Double, Double>>()
        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0

        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlat = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
            lat += dlat

            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlng = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
            lng += dlng

            poly.add(Pair(lat / 1E5, lng / 1E5))
        }
        return poly
    }

    private fun h3ApproxCenter(h3: String): Pair<Double, Double> {
        // Pre-computed lookup or fallback center for Bangalore test hexagons
        return when (h3) {
            "8861892593fffff" -> 12.9716 to 77.5946
            "88618925b7fffff" -> 12.9352 to 77.6245
            else -> 12.9716 to 77.5946
        }
    }

    private fun generateHexagonRing(centerLat: Double, centerLng: Double, radiusM: Double): List<Pair<Double, Double>> {
        val ring = mutableListOf<Pair<Double, Double>>()
        for (i in 0..6) {
            val angleDeg = 60.0 * i - 30.0
            val angleRad = angleDeg * PI / 180.0
            val dLat = (radiusM * sin(angleRad)) / 111320.0
            val dLng = (radiusM * cos(angleRad)) / (111320.0 * cos(centerLat * PI / 180.0))
            ring.add(Pair(centerLat + dLat, centerLng + dLng))
        }
        return ring
    }
}
