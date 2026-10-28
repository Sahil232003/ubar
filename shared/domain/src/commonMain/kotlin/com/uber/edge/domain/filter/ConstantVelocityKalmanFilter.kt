package com.uber.edge.domain.filter

import com.uber.edge.domain.model.LocationFix
import com.uber.edge.domain.model.Route
import com.uber.edge.domain.port.LocationFilter
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Constant-Velocity 4D Kalman Filter in local East-North-Up (ENU) coordinates.
 * State vector: [x (m), y (m), vx (m/s), vy (m/s)]
 */
class ConstantVelocityKalmanFilter : LocationFilter {

    private var anchorLat: Double? = null
    private var anchorLng: Double? = null

    // State [x, y, vx, vy]
    private var x = 0.0
    private var y = 0.0
    private var vx = 0.0
    private var vy = 0.0

    // Covariance matrix 4x4
    private var p00 = 100.0; private var p01 = 0.0;   private var p02 = 0.0;   private var p03 = 0.0
    private var p10 = 0.0;   private var p11 = 100.0; private var p12 = 0.0;   private var p13 = 0.0
    private var p20 = 0.0;   private var p21 = 0.0;   private var p22 = 25.0;  private var p23 = 0.0
    private var p30 = 0.0;   private var p31 = 0.0;   private var p32 = 0.0;   private var p33 = 25.0

    private var lastTimestampMs: Long = 0L
    private var isInitialized = false

    override fun reset() {
        anchorLat = null
        anchorLng = null
        isInitialized = false
        lastTimestampMs = 0L
        p00 = 100.0; p01 = 0.0; p02 = 0.0; p03 = 0.0
        p10 = 0.0; p11 = 100.0; p12 = 0.0; p13 = 0.0
        p20 = 0.0; p21 = 0.0; p22 = 25.0; p23 = 0.0
        p30 = 0.0; p31 = 0.0; p32 = 0.0; p33 = 25.0
    }

    override fun filter(fix: LocationFix, activeRoute: Route?): LocationFix? {
        // Rule 1: Reject fix if horizontal accuracy > 50 m (cell tower or Wi-Fi multipath)
        if (fix.accM > 50.0) return null

        if (!isInitialized) {
            initialize(fix)
            return fix.copy(flt = "kalman_init")
        }

        val dt = (fix.t - lastTimestampMs) / 1000.0

        // Rule 4: Reset filter if gap > 30s (stale after tunnel/basement)
        if (dt > 30.0 || dt <= 0.0) {
            reset()
            initialize(fix)
            return fix.copy(flt = "kalman_reset")
        }

        // Project measurement to local ENU
        val zx = latLngToEastM(fix.lat, fix.lng, anchorLat!!, anchorLng!!)
        val zy = latLngToNorthM(fix.lat, anchorLat!!)

        // Rule 2: Reject fix if implied speed > 55 m/s (198 km/h teleport)
        val measuredDistance = sqrt((zx - x) * (zx - x) + (zy - y) * (zy - y))
        val impliedSpeed = measuredDistance / dt
        if (impliedSpeed > 55.0) {
            return null // discard multipath spike
        }

        // Predict Step
        // State extrapolation
        x += vx * dt
        y += vy * dt

        // Process noise Q based on current speed
        val currentSpeed = sqrt(vx * vx + vy * vy)
        val sigmaA = if (currentSpeed > 15.0) 3.0 else 1.5 // Rule 3: Process noise scaling
        val qAcc = sigmaA * sigmaA
        val dt2 = dt * dt
        val dt3 = dt2 * dt / 2.0
        val dt4 = dt2 * dt2 / 4.0

        // P = F * P * F^T + Q
        // (Analytical update of 4x4 matrix)
        val newP00 = p00 + dt * (p20 + p02) + dt2 * p22 + dt4 * qAcc
        val newP02 = p02 + dt * p22 + dt3 * qAcc
        val newP11 = p11 + dt * (p31 + p13) + dt2 * p33 + dt4 * qAcc
        val newP13 = p13 + dt * p33 + dt3 * qAcc
        val newP22 = p22 + dt2 * qAcc
        val newP33 = p33 + dt2 * qAcc

        p00 = newP00; p02 = newP02; p20 = newP02
        p11 = newP11; p13 = newP13; p31 = newP13
        p22 = newP22; p33 = newP33

        // Measurement Update Step (z = [zx, zy])
        val r = maxOf(fix.accM * fix.accM, 4.0) // measurement noise R

        // Innovation
        val yx = zx - x
        val yy = zy - y

        // Innovation covariance S = H * P * H^T + R
        val s0 = p00 + r
        val s1 = p11 + r

        // Kalman gain K = P * H^T * S^-1
        val k00 = p00 / s0
        val k20 = p20 / s0
        val k11 = p11 / s1
        val k31 = p31 / s1

        // State update
        x += k00 * yx
        vx += k20 * yx
        y += k11 * yy
        vy += k31 * yy

        // Covariance update: P = (I - K*H) * P
        p00 *= (1.0 - k00)
        p20 *= (1.0 - k00)
        p02 = p20
        p22 -= k20 * p02

        p11 *= (1.0 - k11)
        p31 *= (1.0 - k11)
        p13 = p31
        p33 -= k31 * p13

        lastTimestampMs = fix.t

        // Convert back to Lat/Lng
        var filteredLat = northMToLat(y, anchorLat!!)
        var filteredLng = eastMToLng(x, anchorLat!!, anchorLng!!)
        val filteredSpeed = sqrt(vx * vx + vy * vy)
        var filteredBearing = (atan2(vx, vy) * 180.0 / PI + 360.0) % 360.0

        // Optional Snap-to-Route (Rule 5: snap if distance < 20m and bearing delta < 35 deg)
        if (activeRoute != null && activeRoute.polyline.isNotEmpty()) {
            // cheap snap placeholder logic for route polyline
        }

        return fix.copy(
            lat = filteredLat,
            lng = filteredLng,
            accM = sqrt(minOf(p00, p11)),
            spdMps = filteredSpeed,
            brg = filteredBearing,
            flt = "kalman_v2"
        )
    }

    private fun initialize(fix: LocationFix) {
        anchorLat = fix.lat
        anchorLng = fix.lng
        x = 0.0
        y = 0.0
        val brgRad = fix.brg * PI / 180.0
        vx = fix.spdMps * sin(brgRad)
        vy = fix.spdMps * cos(brgRad)
        lastTimestampMs = fix.t
        isInitialized = true
    }

    companion object {
        private const val EARTH_RADIUS_M = 6378137.0

        fun latLngToEastM(lat: Double, lng: Double, anchorLat: Double, anchorLng: Double): Double {
            val dLng = (lng - anchorLng) * PI / 180.0
            val latRad = anchorLat * PI / 180.0
            return dLng * EARTH_RADIUS_M * cos(latRad)
        }

        fun latLngToNorthM(lat: Double, anchorLat: Double): Double {
            val dLat = (lat - anchorLat) * PI / 180.0
            return dLat * EARTH_RADIUS_M
        }

        fun northMToLat(northM: Double, anchorLat: Double): Double {
            return anchorLat + (northM / EARTH_RADIUS_M) * (180.0 / PI)
        }

        fun eastMToLng(eastM: Double, anchorLat: Double, anchorLng: Double): Double {
            val latRad = anchorLat * PI / 180.0
            return anchorLng + (eastM / (EARTH_RADIUS_M * cos(latRad))) * (180.0 / PI)
        }
    }
}
