package com.uber.edge.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.uber.edge.domain.model.LocationFix
import com.uber.edge.domain.model.SamplingProfile

/**
 * Android Foreground Service (FGS) for uninterrupted background tracking.
 * Required by PRD Section 9.1 (P1, FR-02).
 */
class TrackingService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var isTracking = false

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (location in result.locations) {
                    val fix = LocationFix(
                        t = location.time,
                        lat = location.latitude,
                        lng = location.longitude,
                        accM = location.accuracy.toDouble(),
                        spdMps = location.speed.toDouble(),
                        brg = location.bearing.toDouble(),
                        alt = if (location.hasAltitude()) location.altitude else null,
                        src = "fused",
                        flt = null
                    )
                    // Broadcast or hand off to TrackingSessionManager / SQLDelight
                    updateNotification("Lat: ${"%.4f".format(fix.lat)}, Lng: ${"%.4f".format(fix.lng)}")
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_START -> startTracking()
            ACTION_STOP -> stopTracking()
        }
        // Recover cleanly if OS terminates process
        return START_STICKY
    }

    private fun startTracking() {
        if (isTracking) return
        isTracking = true

        val notification = buildNotification("Tracking Active — Dispatch Stream Online")
        startForeground(NOTIFICATION_ID, notification)

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateDistanceMeters(5.0f)
            .setMaxUpdateDelayMillis(0L)
            .build()

        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            stopSelf()
        }
    }

    private fun stopTracking() {
        if (!isTracking) return
        isTracking = false
        fusedLocationClient.removeLocationUpdates(locationCallback)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isTracking) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Active Trip Tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows persistent telemetry and GPS tracking notification during active shift."
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Uber Driver — Shift Active")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    companion object {
        const val ACTION_START = "com.uber.edge.action.START_TRACKING"
        const val ACTION_STOP = "com.uber.edge.action.STOP_TRACKING"
        private const val NOTIFICATION_ID = 4821
        private const val CHANNEL_ID = "uber_tracking_channel"
    }
}
