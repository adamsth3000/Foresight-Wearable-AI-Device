package com.foresight.gateway.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface

/**
 * Single live PHONE-heading source for presentation consumers.
 *
 * The provider deliberately uses the platform rotation-vector fusion rather than duplicating the
 * raw accelerometer/gyroscope telemetry pipeline. It owns no capture or telemetry state.
 */
class PhoneHeadingProvider(
    context: Context,
    private val displayRotation: () -> Int,
    private val onHeadingChanged: (HeadingState) -> Unit,
) : SensorEventListener {
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var sensorThread: HandlerThread? = null
    private var running = false

    fun start() {
        if (running) return
        running = true

        val rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotationVector == null) {
            dispatch(HeadingState.unavailable())
            return
        }

        val thread = HandlerThread("ForesightPhoneHeading").also { it.start() }
        sensorThread = thread
        sensorManager.registerListener(
            this,
            rotationVector,
            SensorManager.SENSOR_DELAY_UI,
            Handler(thread.looper),
        )
    }

    fun stop() {
        if (!running) return
        running = false
        sensorManager.unregisterListener(this)
        sensorThread?.quitSafely()
        sensorThread = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!running || event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return

        val rotation = FloatArray(9)
        val remappedRotation = FloatArray(9)
        val orientation = FloatArray(3)
        SensorManager.getRotationMatrixFromVector(rotation, event.values)
        val (axisX, axisY) = displayAxes(displayRotation())
        SensorManager.remapCoordinateSystem(rotation, axisX, axisY, remappedRotation)
        SensorManager.getOrientation(remappedRotation, orientation)

        val heading = normalizeDegrees(Math.toDegrees(orientation[0].toDouble()).toFloat())
        dispatch(
            HeadingState(
                headingDegrees = heading,
                sensorAccuracy = event.accuracy,
                timestampElapsedRealtimeNanos = event.timestamp,
            ),
        )
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    private fun dispatch(state: HeadingState) {
        mainHandler.post {
            if (running) onHeadingChanged(state)
        }
    }

    private fun displayAxes(rotation: Int): Pair<Int, Int> = when (rotation) {
        Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
        Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
        Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
        else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
    }

    private fun normalizeDegrees(value: Float): Float = ((value % 360f) + 360f) % 360f
}
