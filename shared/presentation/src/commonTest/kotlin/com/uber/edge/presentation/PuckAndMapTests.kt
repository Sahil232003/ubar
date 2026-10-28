package com.uber.edge.presentation

import com.uber.edge.presentation.map.GeoJsonBuilders
import com.uber.edge.presentation.map.PuckInterpolator
import com.uber.edge.presentation.map.SurgeThrottler
import com.uber.edge.presentation.state.PuckUi
import com.uber.edge.presentation.state.RouteUi
import com.uber.edge.presentation.state.SurgeLayerUi
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PuckAndMapTests {

    @Test
    fun testPuckLinearCoordinateInterpolation() {
        val interpolator = PuckInterpolator(defaultStepDurationMs = 1000L)

        val p1 = PuckUi(lat = 10.0, lng = 20.0, bearing = 0.0, accuracyM = 5.0)
        val p2 = PuckUi(lat = 12.0, lng = 24.0, bearing = 0.0, accuracyM = 5.0)

        interpolator.updateTarget(p1, 1000L)
        interpolator.updateTarget(p2, 1000L) // target is p2, duration is 1000ms

        // Halfway at 1500L (500ms elapsed -> 50% progress)
        val halfway = interpolator.evaluate(1500L)
        assertEquals(11.0, halfway.lat, 0.001)
        assertEquals(22.0, halfway.lng, 0.001)

        // At 2000L (100% progress)
        val end = interpolator.evaluate(2000L)
        assertEquals(12.0, end.lat, 0.001)
        assertEquals(24.0, end.lng, 0.001)
    }

    @Test
    fun testPuckShortestArcBearingRotation() {
        // Test 1: From 5 deg to 355 deg
        // Shortest arc is -10 deg (across 0/360), NOT +350 deg clockwise!
        val halfway1 = PuckInterpolator.interpolateShortestArc(5.0, 355.0, 0.5)
        assertEquals(0.0, halfway1, 0.001)

        // Test 2: From 350 deg to 10 deg
        // Shortest arc is +20 deg (across 0/360)
        val halfway2 = PuckInterpolator.interpolateShortestArc(350.0, 10.0, 0.5)
        assertEquals(0.0, halfway2, 0.001)

        // Test 3: Normal 90 deg to 180 deg
        val halfway3 = PuckInterpolator.interpolateShortestArc(90.0, 180.0, 0.5)
        assertEquals(135.0, halfway3, 0.001)
    }

    @Test
    fun testGeoJsonBuildersOutput() {
        val puck = PuckUi(12.971598, 77.594566, 88.5, 4.2)
        val puckJson = GeoJsonBuilders.buildPuckGeoJson(puck)
        assertTrue(puckJson.contains("Point"))
        assertTrue(puckJson.contains("77.594566"))
        assertTrue(puckJson.contains("88.5"))

        val route = RouteUi(version = 1, encodedPolyline = "m~ykAmzhxMcAkBy@}AsAmCgAyBk@iA", remainingMeters = 4820)
        val routeJson = GeoJsonBuilders.buildRouteGeoJson(route)
        assertTrue(routeJson.contains("LineString"))
        assertTrue(routeJson.contains("4820"))

        val surge = SurgeLayerUi(version = 1, cells = persistentMapOf("8861892593fffff" to 2.1))
        val surgeJson = GeoJsonBuilders.buildSurgeGeoJson(surge)
        assertTrue(surgeJson.contains("Polygon"))
        assertTrue(surgeJson.contains("8861892593fffff"))
        assertTrue(surgeJson.contains("#EF4444")) // High surge color
    }

    @Test
    fun testSurgeThrottlerRateLimiting() = runTest {
        val throttler = SurgeThrottler(minIntervalMs = 500L, scope = this)
        val surge1 = SurgeLayerUi(1, persistentMapOf("cell1" to 1.5))

        throttler.onSurgeUpdated(surge1, 1000L)
        val emitted = throttler.throttledSurgeGeoJson.first()
        assertTrue(emitted.contains("cell1"))
    }
}
