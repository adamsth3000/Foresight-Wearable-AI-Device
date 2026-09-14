package com.foresight.gateway.vision

import android.graphics.Bitmap
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal object LocalVisionEndpoint {
    fun detectionUrl(value: String): URL? = runCatching {
        val endpoint = value.trim().removeSuffix("/")
        if (endpoint.isBlank()) return null
        URL(if (endpoint.endsWith("/v1/detect")) endpoint else "$endpoint/v1/detect").also {
            require(it.protocol == "http" || it.protocol == "https")
        }
    }.getOrNull()
}

/** Isolated best-effort HTTP client. It has no dependency on capture or media state. */
internal class LocalVisionClient(
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ForesightVisionClient").apply { isDaemon = true }
    },
) : AutoCloseable {
    private val lock = Any()
    private var requestInFlight = false

    fun isReadyForFrame(endpoint: String): Boolean = synchronized(lock) {
        LocalVisionEndpoint.detectionUrl(endpoint) != null && !requestInFlight
    }

    fun submit(
        endpoint: String,
        frame: SampledPreviewFrame,
        onSnapshot: (DetectionSnapshot) -> Unit,
    ): Boolean {
        val url = LocalVisionEndpoint.detectionUrl(endpoint) ?: run {
            frame.bitmap.recycle()
            return false
        }
        synchronized(lock) {
            if (requestInFlight) {
                frame.bitmap.recycle()
                return false
            }
            requestInFlight = true
        }
        executor.execute {
            var connection: HttpURLConnection? = null
            try {
                val jpeg = frame.bitmap.toJpeg()
                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = CONNECT_TIMEOUT_MILLIS
                    readTimeout = READ_TIMEOUT_MILLIS
                    doOutput = true
                    setRequestProperty("Content-Type", "image/jpeg")
                    setRequestProperty("X-Foresight-Vision-Schema-Version", SCHEMA_VERSION.toString())
                    setRequestProperty("X-Foresight-Vision-Runtime-Generation", frame.runtimeGeneration.toString())
                    setRequestProperty("X-Foresight-Vision-Frame-Id", frame.frameId.toString())
                    setRequestProperty(
                        "X-Foresight-Vision-Capture-Elapsed-Realtime-Nanos",
                        frame.captureElapsedRealtimeNanos.toString(),
                    )
                    setRequestProperty("X-Foresight-Vision-Source-Width", frame.bitmap.width.toString())
                    setRequestProperty("X-Foresight-Vision-Source-Height", frame.bitmap.height.toString())
                    setFixedLengthStreamingMode(jpeg.size)
                }
                connection.outputStream.use { it.write(jpeg) }
                if (connection.responseCode in 200..299) {
                    connection.inputStream.bufferedReader().use { reader ->
                        LiveVisionResponseParser.parse(reader.readText())?.let(onSnapshot)
                    }
                } else {
                    Log.w(TAG, "Live Vision request failed with HTTP ${connection.responseCode}.")
                }
            } catch (error: Exception) {
                Log.w(TAG, "Live Vision request unavailable: ${error.javaClass.simpleName}: ${error.message}")
            } finally {
                frame.bitmap.recycle()
                connection?.disconnect()
                synchronized(lock) { requestInFlight = false }
            }
        }
        return true
    }

    override fun close() {
        executor.shutdownNow()
    }

    private fun Bitmap.toJpeg(): ByteArray = ByteArrayOutputStream().use { output ->
        check(compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output))
        output.toByteArray()
    }

    private companion object {
        const val TAG = "LocalVisionClient"
        const val SCHEMA_VERSION = 1
        const val JPEG_QUALITY = 85
        const val CONNECT_TIMEOUT_MILLIS = 3_000
        const val READ_TIMEOUT_MILLIS = 15_000
    }
}

internal object LiveVisionResponseParser {
    fun parse(payload: String): DetectionSnapshot? = runCatching {
        val root = JSONObject(payload)
        require(root.getInt("schema_version") == 1)
        val source = root.getJSONObject("source")
        val detector = root.optJSONObject("detector")
        val detections = root.getJSONArray("detections")
        DetectionSnapshot(
            runtimeGeneration = root.getLong("runtime_generation"),
            frameId = root.getLong("frame_id"),
            captureElapsedRealtimeNanos = root.getLong("capture_elapsed_realtime_nanos"),
            sourceWidth = source.getInt("width"),
            sourceHeight = source.getInt("height"),
            detections = List(detections.length()) { index ->
                val detection = detections.getJSONObject(index)
                val box = detection.getJSONObject("bounding_box")
                LiveDetectionPresentation(
                    label = detection.getString("label"),
                    confidence = detection.getDouble("confidence").toFloat(),
                    boundingBox = LiveNormalizedBoundingBox(
                        box.getDouble("x_min").toFloat(),
                        box.getDouble("y_min").toFloat(),
                        box.getDouble("x_max").toFloat(),
                        box.getDouble("y_max").toFloat(),
                    ),
                    prompt = detection.optString("prompt").takeIf { !detection.isNull("prompt") },
                )
            },
            detectorBackend = detector?.optString("backend")?.takeIf { it.isNotBlank() },
            detectorModel = detector?.optString("model")?.takeIf { it.isNotBlank() },
        )
    }.getOrNull()
}
