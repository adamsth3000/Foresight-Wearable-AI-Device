package com.foresight.gateway.transport

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.util.Log
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.sources.OrientationForced
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.video.Camera2Source
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.library.rtsp.RtspStream
import kotlin.math.abs
import kotlin.math.sqrt
import java.util.Locale

/**
 * Phone-local RTSP publisher. RootEncoder is confined here so higher layers only
 * depend on lifecycle callbacks and an RTSP endpoint.
 */
class RtspPublisher(
    context: Context,
    private val listener: Listener,
) : ConnectChecker {
    interface Listener {
        fun onLifecycleChanged(lifecycle: StreamLifecycle, detail: String? = null)
        fun onBitrateChanged(bitsPerSecond: Long)
    }

    private val applicationContext = context.applicationContext
    private val cameraSource = Camera2Source(applicationContext)
    private val stream = RtspStream(applicationContext, this, cameraSource, MicrophoneSource())

    fun start(endpoint: String): Boolean {
        if (stream.isStreaming) {
            Log.i(TAG, "RTSP stream is already active.")
            return true
        }

        listener.onLifecycleChanged(StreamLifecycle.PREPARING)
        val cameraSize = selectCameraSize()
        cameraSource.setRequiredResolution(cameraSize)
        logCameraGeometry(cameraSize)
        Log.i(
            TAG,
            "Preparing H.264 encoder ${VIDEO_WIDTH}x${VIDEO_HEIGHT} at ${VIDEO_FPS} fps, " +
                "${VIDEO_BITRATE_BITS_PER_SECOND} bps, keyframe interval " +
                "${VIDEO_KEYFRAME_INTERVAL_SECONDS}s, rotation $VIDEO_ROTATION_DEGREES.",
        )
        val videoReady = stream.prepareVideo(
            width = VIDEO_WIDTH,
            height = VIDEO_HEIGHT,
            bitrate = VIDEO_BITRATE_BITS_PER_SECOND,
            fps = VIDEO_FPS,
            iFrameInterval = VIDEO_KEYFRAME_INTERVAL_SECONDS,
            rotation = VIDEO_ROTATION_DEGREES,
        )
        Log.i(TAG, "Video preparation result: $videoReady")
        if (videoReady) {
            // Keep the output viewport landscape, then apply the inverse of the Camera2
            // SurfaceTexture's clockwise axis exchange to the camera-quad MVP transform.
            stream.getGlInterface().forceOrientation(OrientationForced.LANDSCAPE)
            stream.setOrientation(CAMERA_TEXTURE_COMPENSATION_DEGREES)
            Log.i(
                TAG,
                "GL geometry: forcedLandscape=true, cameraMvpCompensation=" +
                    "$CAMERA_TEXTURE_COMPENSATION_DEGREES, " +
                    "encoderViewport=${VIDEO_WIDTH}x${VIDEO_HEIGHT}, " +
                    "previewAspectMode=Adjust (preview-only), " +
                    "surfaceTextureTransform=applied by RootEncoder CameraRender per frame.",
            )
        }
        val audioReady = stream.prepareAudio(
            sampleRate = AUDIO_SAMPLE_RATE_HZ,
            isStereo = true,
            bitrate = AUDIO_BITRATE_BITS_PER_SECOND,
            echoCanceler = false,
            noiseSuppressor = false,
        )
        Log.i(TAG, "Audio preparation result: $audioReady")
        if (!videoReady || !audioReady) {
            listener.onLifecycleChanged(StreamLifecycle.ERROR, "Unable to prepare rear camera or microphone.")
            return false
        }

        listener.onLifecycleChanged(StreamLifecycle.CONNECTING)
        Log.i(TAG, "Starting RTSP stream: $endpoint")
        stream.startStream(endpoint)
        Log.i(TAG, "RootEncoder start requested for camera ${cameraSource.getCurrentCameraId()}.")
        scheduleTextureDiagnostics(cameraSize)
        return true
    }

    fun stop() {
        if (!stream.isStreaming) {
            return
        }
        listener.onLifecycleChanged(StreamLifecycle.STOPPING)
        stream.stopStream()
    }

    override fun onConnectionStarted(url: String) {
        Log.i(TAG, "RTSP connection started: $url")
    }

    override fun onConnectionSuccess() {
        Log.i(TAG, "RTSP connection established.")
        listener.onLifecycleChanged(StreamLifecycle.STREAMING)
    }

    override fun onConnectionFailed(reason: String) {
        Log.e(TAG, "RTSP connection failed: $reason")
        listener.onLifecycleChanged(StreamLifecycle.ERROR, reason)
        stream.stopStream()
    }

    override fun onNewBitrate(bitrate: Long) {
        listener.onBitrateChanged(bitrate)
    }

    override fun onDisconnect() {
        Log.i(TAG, "RTSP connection disconnected.")
        listener.onLifecycleChanged(StreamLifecycle.IDLE)
    }

    override fun onAuthError() {
        listener.onLifecycleChanged(StreamLifecycle.ERROR, "RTSP authentication failed.")
    }

    override fun onAuthSuccess() = Unit

    /**
     * RootEncoder otherwise selects a camera surface independently from the encoder size. Its GL
     * route does not aspect-fit a mismatched camera surface, so require an actual 16:9 Camera2 size.
     */
    private fun selectCameraSize(): Size {
        val supported = cameraSource.getCameraResolutions(CameraHelper.Facing.BACK)
        val target = Size(VIDEO_WIDTH, VIDEO_HEIGHT)
        val matchingAspect = supported.filter { size ->
            size.width.toLong() * target.height == size.height.toLong() * target.width
        }
        require(matchingAspect.isNotEmpty()) {
            "The rear camera exposes no 16:9 output size; refusing to stretch it into " +
                "${VIDEO_WIDTH}x${VIDEO_HEIGHT}. Supported: ${supported.joinToString()}"
        }
        return matchingAspect.minWith(
            compareBy<Size> { abs(it.width - target.width) + abs(it.height - target.height) }
                .thenBy { abs((it.width.toLong() * it.height) - (target.width.toLong() * target.height)) },
        )
    }

    private fun logCameraGeometry(cameraSize: Size) {
        val cameraId = cameraSource.getCurrentCameraId()
        val cameraManager = applicationContext.getSystemService(CameraManager::class.java)
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)
        Log.i(
            TAG,
            "Camera geometry: id=$cameraId, sensorOrientation=$sensorOrientation, " +
                "selectedCameraSize=${cameraSize.width}x${cameraSize.height}, " +
                "encoder=${VIDEO_WIDTH}x${VIDEO_HEIGHT}, rotation=$VIDEO_ROTATION_DEGREES, " +
                "requestedCameraToEncoder=16:9-to-16:9.",
        )
    }

    private fun scheduleTextureDiagnostics(cameraSize: Size) {
        mainHandler.postDelayed({
            if (!stream.isStreaming) return@postDelayed
            try {
                val texture = stream.getGlInterface().getSurfaceTexture()
                val matrix = FloatArray(16)
                texture.getTransformMatrix(matrix)
                // Android matrices are column-major: (m0, m1) maps source X and (m4, m5) source Y.
                val sourceXScale = sqrt(matrix[0] * matrix[0] + matrix[1] * matrix[1])
                val sourceYScale = sqrt(matrix[4] * matrix[4] + matrix[5] * matrix[5])
                val axesExchanged = isAxisExchange(matrix)
                val transformedSourceAspect = if (axesExchanged) {
                    cameraSize.height.toFloat() / cameraSize.width
                } else {
                    cameraSize.width.toFloat() / cameraSize.height
                }
                Log.i(
                    TAG,
                    "SurfaceTexture geometry: defaultBuffer=requested ${cameraSize.width}x${cameraSize.height} " +
                        "(Android SurfaceTexture does not expose its negotiated buffer dimensions), " +
                        "camera=${cameraSize.width}x${cameraSize.height}, " +
                        "encoderViewport=${VIDEO_WIDTH}x${VIDEO_HEIGHT}, " +
                        "nominalSourceAspect=${formatAspect(cameraSize)}, " +
                        "stAxesExchanged=$axesExchanged, stSourceAspect=${formatFloat(transformedSourceAspect)}, " +
                        "mvpCompensation=$CAMERA_TEXTURE_COMPENSATION_DEGREES, " +
                        "composedSourceAspect=${formatAspect(cameraSize)}, " +
                        "destinationAspect=${formatAspect(VIDEO_WIDTH, VIDEO_HEIGHT)}, " +
                        "textureScaleX=${formatFloat(sourceXScale)}, textureScaleY=${formatFloat(sourceYScale)}, " +
                        "matrix=${matrix.joinToString(prefix = "[", postfix = "]") { formatFloat(it) }}",
                )
            } catch (error: RuntimeException) {
                Log.w(TAG, "Unable to read RootEncoder SurfaceTexture diagnostics.", error)
            }
        }, TEXTURE_DIAGNOSTIC_DELAY_MILLIS)
    }

    private fun formatAspect(size: Size): String = formatAspect(size.width, size.height)

    private fun formatAspect(width: Int, height: Int): String = formatFloat(width.toFloat() / height)

    private fun formatFloat(value: Float): String = String.format(Locale.US, "%.5f", value)

    private fun isAxisExchange(matrix: FloatArray): Boolean =
        abs(matrix[0]) < MATRIX_EPSILON &&
            abs(matrix[5]) < MATRIX_EPSILON &&
            abs(matrix[1]) > MATRIX_EPSILON &&
            abs(matrix[4]) > MATRIX_EPSILON

    private companion object {
        const val TAG = "RtspPublisher"
        const val VIDEO_WIDTH = 1280
        const val VIDEO_HEIGHT = 720
        const val VIDEO_FPS = 30
        const val VIDEO_BITRATE_BITS_PER_SECOND = 2_000_000
        const val VIDEO_KEYFRAME_INTERVAL_SECONDS = 2
        const val VIDEO_ROTATION_DEGREES = 0
        const val CAMERA_TEXTURE_COMPENSATION_DEGREES = 270
        const val AUDIO_SAMPLE_RATE_HZ = 44_100
        const val AUDIO_BITRATE_BITS_PER_SECOND = 128_000
        const val TEXTURE_DIAGNOSTIC_DELAY_MILLIS = 1_000L
        const val MATRIX_EPSILON = 0.0001f
        val mainHandler = Handler(Looper.getMainLooper())
    }
}
