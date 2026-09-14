package com.foresight.gateway.sensors

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.Handler
import android.util.Log
import com.foresight.gateway.vision.SceneLocation
import com.foresight.gateway.vision.SceneLocationQualityPolicy

/** Foreground-only phone location source for scene context; it never participates in capture ownership. */
class ForegroundLocationProvider(
    context: Context,
    private val elapsedRealtimeMillis: () -> Long,
    private val onLocation: (SceneLocation) -> Unit,
    private val onPermissionDenied: () -> Unit,
) : LocationListener {
    private val appContext = context.applicationContext
    private val locationManager = context.getSystemService(LocationManager::class.java)
    private var running = false
    private var permissionDeniedReported = false
    private var latestLocation: SceneLocation? = null
    private val handler = Handler(Looper.getMainLooper())
    private val pendingRefreshes = mutableListOf<PendingRefresh>()

    fun start() {
        if (!hasLocationPermission()) {
            if (running) stop()
            reportPermissionDenied()
            return
        }
        if (running) return
        permissionDeniedReported = false
        running = true
        Log.i(TAG, "FORESIGHT_LOCATION_PROVIDER_START")
        requestUpdates()
    }

    fun stop() {
        if (!running) return
        running = false
        locationManager.removeUpdates(this)
        completeRefreshes(null)
        Log.i(TAG, "FORESIGHT_LOCATION_PROVIDER_STOP")
    }

    /** Awaits the next acceptable foreground fix without blocking the caller or UI thread. */
    fun requestFreshFix(
        maxAgeMillis: Long,
        timeoutMillis: Long = REFRESH_TIMEOUT_MILLIS,
        onComplete: (SceneLocation?) -> Unit,
    ) {
        require(maxAgeMillis >= 0 && timeoutMillis >= 0)
        latestLocation?.takeIf { it.ageMillis(elapsedRealtimeMillis()) <= maxAgeMillis }?.let {
            onComplete(it)
            return
        }
        if (!running || !hasLocationPermission()) {
            onComplete(null)
            return
        }
        Log.i(TAG, "FORESIGHT_LOCATION_REFRESH_REQUESTED")
        val pending = PendingRefresh(maxAgeMillis, onComplete)
        pendingRefreshes += pending
        pending.timeout = Runnable {
            if (pendingRefreshes.remove(pending)) {
                Log.i(TAG, "FORESIGHT_LOCATION_REFRESH_TIMEOUT")
                onComplete(null)
            }
        }.also { handler.postDelayed(it, timeoutMillis) }
        requestSingleUpdates()
    }

    private fun hasLocationPermission(): Boolean =
        ForegroundLocationPermissionPolicy.resolve(
            fineGranted = appContext.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED,
            coarseGranted = appContext.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED,
        ) != ForegroundLocationPermission.DENIED

    @SuppressLint("MissingPermission")
    private fun requestUpdates() {
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { provider ->
            runCatching {
                if (locationManager.isProviderEnabled(provider)) {
                    locationManager.getLastKnownLocation(provider)?.let(::accept)
                    locationManager.requestLocationUpdates(provider, UPDATE_INTERVAL_MILLIS, MIN_DISTANCE_METERS, this, Looper.getMainLooper())
                }
            }.onFailure { error ->
                if (error is SecurityException) {
                    reportPermissionDenied()
                    stop()
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun requestSingleUpdates() {
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { provider ->
            runCatching {
                if (locationManager.isProviderEnabled(provider)) {
                    locationManager.requestSingleUpdate(provider, this, Looper.getMainLooper())
                }
            }
        }
    }

    override fun onLocationChanged(location: Location) = accept(location)

    private fun accept(location: Location) {
        if (!running) return
        val capturedAt = (location.elapsedRealtimeNanos / 1_000_000L).takeIf { it > 0L } ?: elapsedRealtimeMillis()
        val value = SceneLocation(
            latitude = location.latitude,
            longitude = location.longitude,
            horizontalAccuracyMeters = location.accuracy.takeIf { location.hasAccuracy() },
            altitudeMeters = location.altitude.takeIf { location.hasAltitude() },
            capturedAtElapsedMillis = capturedAt,
            provider = location.provider,
        )
        latestLocation = value
        onLocation(value)
        Log.i(TAG, "FORESIGHT_LOCATION_UPDATE accuracyMeters=${value.horizontalAccuracyMeters ?: "unknown"} ageMs=${value.ageMillis(elapsedRealtimeMillis())}")
        Log.i(TAG, "FORESIGHT_LOCATION_QUALITY quality=${SceneLocationQualityPolicy.classify(value.horizontalAccuracyMeters)} accuracyMeters=${value.horizontalAccuracyMeters ?: "unknown"}")
        completeRefreshes(value)
    }

    override fun onProviderDisabled(provider: String) = Unit
    override fun onProviderEnabled(provider: String) = Unit

    private fun reportPermissionDenied() {
        if (permissionDeniedReported) return
        permissionDeniedReported = true
        Log.i(TAG, "FORESIGHT_LOCATION_PERMISSION_DENIED")
        onPermissionDenied()
    }

    private fun completeRefreshes(location: SceneLocation?) {
        val pending = pendingRefreshes.toList()
        pending.forEach { refresh ->
            if (location != null && location.ageMillis(elapsedRealtimeMillis()) > refresh.maxAgeMillis) return@forEach
            pendingRefreshes.remove(refresh)
            refresh.timeout?.let(handler::removeCallbacks)
            if (location != null) {
                Log.i(TAG, "FORESIGHT_LOCATION_REFRESH_SUCCESS ageMs=${location.ageMillis(elapsedRealtimeMillis())} accuracyMeters=${location.horizontalAccuracyMeters ?: "unknown"}")
            }
            refresh.onComplete(location)
        }
    }

    private class PendingRefresh(
        val maxAgeMillis: Long,
        val onComplete: (SceneLocation?) -> Unit,
    ) {
        var timeout: Runnable? = null
    }

    private companion object {
        const val TAG = "ForesightLocation"
        const val UPDATE_INTERVAL_MILLIS = 3_000L
        const val MIN_DISTANCE_METERS = 2f
        const val REFRESH_TIMEOUT_MILLIS = 4_000L
    }
}
