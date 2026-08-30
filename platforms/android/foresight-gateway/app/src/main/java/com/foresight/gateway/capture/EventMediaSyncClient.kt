package com.foresight.gateway.capture

import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

data class EventMediaSyncUiState(
    val state: EventMediaSyncState = EventMediaSyncState.LOCAL_ONLY,
    val detail: String? = null,
)

/** Streams a verified private event MP4 to the laptop's private control listener. */
internal class EventMediaSyncClient(
    private val repository: LocalRecordingMetadataRepository,
    private val connectionOpener: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    private val executor = Executors.newSingleThreadExecutor()

    fun sync(eventId: String, baseUrl: String, callback: (EventMediaSyncUiState) -> Unit) {
        executor.execute {
            val plan = runCatching { repository.beginEventMediaSync(eventId) }.getOrElse { error ->
                callback(EventMediaSyncUiState(EventMediaSyncState.FAILED, error.message))
                return@execute
            }
            callback(EventMediaSyncUiState(EventMediaSyncState.UPLOADING, null))
            val result = runCatching { upload(plan, baseUrl) }
            val state = result.fold(
                onSuccess = { detail ->
                    repository.completeEventMediaSync(eventId, detail)
                    EventMediaSyncUiState(EventMediaSyncState.SYNCED, detail)
                },
                onFailure = { error ->
                    val detail = error.message ?: error.javaClass.simpleName
                    repository.failEventMediaSync(eventId, detail)
                    logWarning("Event media sync failed: eventId=$eventId; $detail", error)
                    EventMediaSyncUiState(EventMediaSyncState.FAILED, detail)
                },
            )
            callback(state)
        }
    }

    private fun upload(plan: EventMediaSyncPlan, baseUrl: String): String {
        val endpoint = endpoint(baseUrl, plan.media.eventId)
        val file = plan.privateFile
        require(file.isFile && file.length() == plan.media.outputByteSize) { "local event media is unavailable" }
        logInfo(
            "Uploading READY event media: eventId=${plan.media.eventId} bytes=${file.length()} " +
                "endpoint=$endpoint",
        )
        val connection = connectionOpener(endpoint)
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-Foresight-Source-Session-Id", plan.recording.sourceSessionId)
            connection.setRequestProperty("X-Foresight-Recording-Id", plan.recording.recordingId)
            connection.setRequestProperty("X-Foresight-Media-Length", plan.media.outputByteSize.toString())
            connection.setRequestProperty("X-Foresight-Media-Sha256", requireNotNull(plan.media.outputSha256))
            connection.setRequestProperty("X-Foresight-Observed-Start-Utc", plan.event.observedStartUtc.toString())
            connection.setRequestProperty("X-Foresight-Observed-End-Utc", requireNotNull(plan.event.observedEndUtc).toString())
            connection.setRequestProperty("X-Foresight-Start-Offset-Ms", plan.media.actualStartOffsetMillis.toString())
            connection.setRequestProperty("X-Foresight-End-Offset-Ms", plan.media.actualEndOffsetMillis.toString())
            connection.setRequestProperty("X-Foresight-Output-Duration-Ms", requireNotNull(plan.media.outputDurationMillis).toString())
            connection.setRequestProperty("X-Foresight-Audio-Present", plan.media.audioPresent.toString())
            connection.setFixedLengthStreamingMode(plan.media.outputByteSize)
            BufferedInputStream(file.inputStream()).use { input ->
                connection.outputStream.use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(StandardCharsets.UTF_8).use { it?.readText().orEmpty() }
            if (status !in 200..299) error("laptop rejected upload: HTTP $status $body")
            val response = JSONObject(body)
            require(response.optString("state") == "synced") { "laptop returned an invalid sync response" }
            require(response.optString("event_id") == plan.media.eventId) { "laptop returned another event ID" }
            require(response.optString("sha256") == plan.media.outputSha256) { "laptop returned another SHA-256" }
            return if (response.optBoolean("idempotent")) "already verified by laptop" else "verified by laptop"
        } finally {
            connection.disconnect()
        }
    }

    internal fun endpoint(baseUrl: String, eventId: String): URL {
        val normalized = baseUrl.trim().trimEnd('/')
        require(normalized.isNotBlank()) { "Sync endpoint is not configured" }
        val base = URL(normalized)
        require(base.protocol in setOf("http", "https")) { "Sync endpoint must use http:// or https://" }
        require(base.path.isEmpty() || base.path == "/") { "Sync endpoint must be a base URL without a path" }
        require(isPrivateHost(base.host)) { "Sync endpoint must use a private or Tailscale host" }
        return URL("$normalized/events/$eventId/phone-media")
    }

    internal fun isPrivateHost(host: String): Boolean {
        val normalized = host.lowercase().trimEnd('.')
        if (normalized.endsWith(".ts.net")) return true
        return runCatching {
            val address = InetAddress.getByName(host)
            address.isSiteLocalAddress || address.isLoopbackAddress ||
            address.hostAddress.orEmpty().lowercase().startsWith("100.") ||
                address.hostAddress.orEmpty().lowercase().startsWith("fd")
        }.getOrDefault(false)
    }

    private fun logWarning(message: String, error: Throwable) {
        // JVM unit tests do not provide Android's Log implementation; logging cannot prevent a
        // FAILED result from reaching durable metadata or the UI callback.
        runCatching { Log.w(TAG, message, error) }
    }

    private fun logInfo(message: String) {
        runCatching { Log.i(TAG, message) }
    }

    private companion object {
        const val TAG = "EventMediaSyncClient"
        const val BUFFER_BYTES = 64 * 1024
        const val CONNECT_TIMEOUT_MILLIS = 5_000
        const val READ_TIMEOUT_MILLIS = 30_000
    }
}
