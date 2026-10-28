package com.uber.edge.domain

import com.uber.edge.domain.filter.ConstantVelocityKalmanFilter
import com.uber.edge.domain.model.LocationFix
import com.uber.edge.domain.model.SamplingProfile
import com.uber.edge.domain.model.TripStatus
import com.uber.edge.domain.policy.AdaptiveSamplingEngine
import com.uber.edge.domain.policy.MotionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KalmanAndPolicyTests {

    @Test
    fun testKalmanRejectsInaccurateFixes() {
        val filter = ConstantVelocityKalmanFilter()
        val fixBadAcc = LocationFix(
            t = 1000L,
            lat = 12.971598,
            lng = 77.594566,
            accM = 65.0, // > 50m: must be dropped
            spdMps = 10.0,
            brg = 90.0
        )
        assertNull(filter.filter(fixBadAcc))
    }

    @Test
    fun testKalmanRejectsSpeedTeleport() {
        val filter = ConstantVelocityKalmanFilter()
        val fix1 = LocationFix(1000L, 12.971598, 77.594566, 5.0, 10.0, 90.0)
        assertNotNull(filter.filter(fix1))

        // Jump 5 km in 1 second (5000 m/s > 55 m/s)
        val fixTeleport = LocationFix(2000L, 13.010000, 77.594566, 5.0, 10.0, 90.0)
        assertNull(filter.filter(fixTeleport))
    }

    @Test
    fun testKalmanResetsAfter30sGap() {
        val filter = ConstantVelocityKalmanFilter()
        val fix1 = LocationFix(1000L, 12.971598, 77.594566, 5.0, 10.0, 90.0)
        filter.filter(fix1)

        // 35s later
        val fixAfterGap = LocationFix(36000L, 12.971600, 77.594570, 5.0, 10.0, 90.0)
        val result = filter.filter(fixAfterGap)
        assertNotNull(result)
        assertEquals("kalman_reset", result.flt)
    }

    @Test
    fun testSamplingProfileSelectionAndHysteresis() {
        val engine = AdaptiveSamplingEngine(hysteresisHoldTimeMs = 20_000L)

        // Offline -> PASSIVE
        assertEquals(
            SamplingProfile.PASSIVE,
            engine.evaluate(TripStatus.OFFLINE, MotionState.STILL, 80, "nominal", false, 0, 1000L)
        )

        // Online Idle -> BALANCED
        assertEquals(
            SamplingProfile.BALANCED,
            engine.evaluate(TripStatus.ONLINE_IDLE, MotionState.IN_VEHICLE, 80, "nominal", false, 0, 2000L)
        )

        // On Trip -> Immediate upgrade to HIGH_ACCURACY
        assertEquals(
            SamplingProfile.HIGH_ACCURACY,
            engine.evaluate(TripStatus.ON_TRIP, MotionState.IN_VEHICLE, 80, "nominal", false, 0, 3000L)
        )

        // Traffic stop (stationary for 10s) -> Should stay HIGH_ACCURACY due to 20s hysteresis
        assertEquals(
            SamplingProfile.HIGH_ACCURACY,
            engine.evaluate(TripStatus.ON_TRIP, MotionState.STILL, 80, "nominal", false, 10, 13000L)
        )

        // Battery critical (< 15%) -> Immediate LOW_POWER downgrade
        assertEquals(
            SamplingProfile.LOW_POWER,
            engine.evaluate(TripStatus.ON_TRIP, MotionState.IN_VEHICLE, 12, "nominal", false, 0, 14000L)
        )
    }
}
