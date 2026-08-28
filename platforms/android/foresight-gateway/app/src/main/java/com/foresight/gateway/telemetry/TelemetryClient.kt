package com.foresight.gateway.telemetry

import android.util.Log
import com.foresight.gateway.metadata.CaptureSessionMetadata
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Bounded, best-effort LAN telemetry sender that never controls RTSP capture. */
class TelemetryClient(
    private val listener: Listener,
    private val maxQueuedRecords: Int = 2_048,
) {
    interface Listener {
        fun onTelemetryBound(captureSessionId: String)
        fun onTelemetryStatus(detail: String)
    }

    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val queue = ArrayDeque<JSONObject>()
    private var metadata: CaptureSessionMetadata? = null
    private var endpoint: String? = null
    private var captureSessionId: String? = null
    private var active = false
    private var workScheduled = false
    private var retryRequired = false
    private var droppedRecordCount = 0

    fun start(session: CaptureSessionMetadata, telemetryEndpoint: String) {
        synchronized(this) {
            metadata = session
            endpoint = telemetryEndpoint.trimEnd('/')
            captureSessionId = null
            active = telemetryEndpoint.isNotBlank()
            if (!active) {
                listener.onTelemetryStatus("Telemetry disabled; RTSP capture remains active.")
                return
            }
            Log.i(TAG, "Telemetry worker starting baseUrl=$endpoint sourceSession=${session.sourceSessionId}")
            scheduleWorkLocked()
        }
    }

    fun enqueue(record: JSONObject) {
        synchronized(this) {
            if (!active) return
            if (queue.size == maxQueuedRecords) {
                queue.removeFirst()
                droppedRecordCount += 1
                if (droppedRecordCount == 1 || droppedRecordCount % QUEUE_WARNING_INTERVAL == 0) {
                    Log.w(
                        TAG,
                        "Telemetry queue full; dropped oldest observation totalDropped=$droppedRecordCount",
                    )
                }
            }
            queue.addLast(record)
            scheduleWorkLocked()
        }
    }

    fun stop() {
        synchronized(this) {
            active = false
            queue.clear()
        }
        executor.shutdownNow()
    }

    private fun scheduleWorkLocked(delayMillis: Long = 0L) {
        if (workScheduled) return
        workScheduled = true
        if (delayMillis > 0L) {
            Log.i(TAG, "Telemetry reconnect scheduled in ${delayMillis}ms")
        }
        executor.schedule({
            try {
                bindIfNeeded()
                flush()
            } finally {
                synchronized(this) {
                    workScheduled = false
                    if (active && queue.isNotEmpty()) {
                        val delay = if (retryRequired) RETRY_DELAY_MILLIS else 0L
                        retryRequired = false
                        scheduleWorkLocked(delay)
                    }
                }
            }
        }, delayMillis, TimeUnit.MILLISECONDS)
    }

    private fun bindIfNeeded() {
        val session = synchronized(this) { metadata } ?: return
        if (synchronized(this) { !active || captureSessionId != null }) return
        try {
            val body = JSONObject().apply {
                put("source_id", session.source.sourceId)
                put("source_session_id", session.sourceSessionId)
                put("source_metadata", JSONObject().apply {
                    put("source_device", session.source.sourceDevice)
                    put("camera_source", session.source.cameraSource)
                    put("microphone_source", session.source.microphoneSource)
                    put("location_source", session.source.locationSource)
                    put("imu_source", session.source.imuSource)
                    put("transport", session.source.transport)
                })
                put("timing_anchor", JSONObject().apply {
                    put("utc", session.sessionStartUtc.toString())
                    put("elapsed_realtime_nanos", session.elapsedRealtimeNanos)
                })
            }
            val bindUrl = buildRequestUrl(requireNotNull(endpoint), "/v1/bind")
            Log.i(TAG, "Telemetry bind attempt url=$bindUrl")
            val response = post(bindUrl, body, "bind")
            val boundId = response.getString("capture_session_id")
            synchronized(this) { captureSessionId = boundId }
            Log.i(TAG, "Telemetry bound canonicalCaptureSessionId=$boundId")
            listener.onTelemetryBound(boundId)
            listener.onTelemetryStatus("Telemetry bound to capture session $boundId")
        } catch (error: Exception) {
            synchronized(this) { retryRequired = true }
            Log.w(TAG, "Telemetry bind failed baseUrl=$endpoint", error)
            listener.onTelemetryStatus("Telemetry waiting for laptop: ${error.message}")
        }
    }

    private fun flush() {
        val session = synchronized(this) { metadata } ?: return
        val records = synchronized(this) {
            if (!active || captureSessionId == null || queue.isEmpty()) return
            ArrayList<JSONObject>(minOf(queue.size, MAX_BATCH_RECORDS)).also { batch ->
                repeat(minOf(queue.size, MAX_BATCH_RECORDS)) { batch.add(queue.removeFirst()) }
            }
        }
        try {
            val body = JSONObject().apply {
                put("source_id", session.source.sourceId)
                put("source_session_id", session.sourceSessionId)
                put("records", JSONArray(records))
            }
            val recordsUrl = buildRequestUrl(requireNotNull(endpoint), "/v1/records")
            post(recordsUrl, body, "records")
        } catch (error: Exception) {
            synchronized(this) {
                retryRequired = true
                // A receiver restart has no binding state. Clear the prior canonical ID so the
                // next attempt performs an explicit bind instead of assigning data silently.
                captureSessionId = null
                records.asReversed().forEach { record ->
                    if (queue.size < maxQueuedRecords) queue.addFirst(record)
                }
            }
            Log.w(TAG, "Telemetry record upload failed recordCount=${records.size}", error)
            listener.onTelemetryStatus("Telemetry disconnected; buffering observations: ${error.message}")
        }
    }

    private fun post(url: String, body: JSONObject, operation: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            Log.i(TAG, "Telemetry $operation HTTP response status=$status url=$url")
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream.bufferedReader().use { it.readText() }
            if (status !in 200..299) error("HTTP $status: $response")
            JSONObject(response)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TAG = "TelemetryClient"
        const val MAX_BATCH_RECORDS = 64
        const val CONNECT_TIMEOUT_MILLIS = 1_500
        const val READ_TIMEOUT_MILLIS = 2_000
        const val RETRY_DELAY_MILLIS = 1_000L
        const val QUEUE_WARNING_INTERVAL = 100

        internal fun buildRequestUrl(baseUrl: String, path: String): String {
            val normalizedBase = baseUrl.trimEnd('/')
            val parsed = URL(normalizedBase)
            require(parsed.protocol == "http") { "Telemetry endpoint must use http:// for LAN transport" }
            require(parsed.path.isEmpty() || parsed.path == "/") {
                "Telemetry endpoint must be a base URL without a path"
            }
            return normalizedBase + path
        }
    }
}
