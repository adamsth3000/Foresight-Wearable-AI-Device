package com.foresight.gateway.sensors

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import com.foresight.gateway.telemetry.TelemetryClient
import org.json.JSONObject

/** Collects phone IMU and optional location observations without affecting media capture. */
class PhoneSensorCapture(
    context: Context,
    private val telemetry: TelemetryClient,
    private val status: (String) -> Unit,
) : SensorEventListener, LocationListener {
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val locationManager = context.getSystemService(LocationManager::class.java)
    private var running = false

    fun start() {
        if (running) return
        running = true
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyroscope?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        status(
            "IMU: accelerometer=${accelerometer != null}, gyroscope=${gyroscope != null}; " +
                "location requested separately.",
        )
        requestLocation()
    }

    fun stop() {
        running = false
        sensorManager.unregisterListener(this)
        locationManager.removeUpdates(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!running || event.values.size < 3) return
        val recordType = when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> "accelerometer"
            Sensor.TYPE_GYROSCOPE -> "gyroscope"
            else -> return
        }
        telemetry.enqueue(JSONObject().apply {
            put("record_type", recordType)
            put("timestamp_elapsed_realtime_nanos", event.timestamp)
            put("x", event.values[0].toDouble())
            put("y", event.values[1].toDouble())
            put("z", event.values[2].toDouble())
            put("accuracy", event.accuracy)
            put("units", if (recordType == "accelerometer") "m_s2" else "rad_s")
            put("coordinate_frame", "android_device_x_right_y_up_z_out_of_screen")
        })
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    @SuppressLint("MissingPermission")
    private fun requestLocation() {
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, this)
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1_000L, 0f, this)
            }
        } catch (error: SecurityException) {
            status("Location unavailable: permission denied.")
        } catch (error: IllegalArgumentException) {
            status("Location unavailable: ${error.message}")
        }
    }

    override fun onLocationChanged(location: Location) {
        if (!running) return
        telemetry.enqueue(JSONObject().apply {
            put("record_type", "location")
            put("timestamp_elapsed_realtime_nanos", location.elapsedRealtimeNanos)
            put("provider", location.provider)
            put("latitude", location.latitude)
            put("longitude", location.longitude)
            put("accuracy_m", location.accuracy.toDouble())
            if (location.hasAltitude()) put("altitude_m", location.altitude)
            if (location.hasSpeed()) put("speed_m_s", location.speed.toDouble())
            if (location.hasBearing()) put("bearing_degrees", location.bearing.toDouble())
        })
    }

    override fun onProviderDisabled(provider: String) {
        Log.i(TAG, "Location provider disabled: $provider")
    }

    override fun onProviderEnabled(provider: String) = Unit

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    private companion object {
        const val TAG = "PhoneSensorCapture"
    }
}
