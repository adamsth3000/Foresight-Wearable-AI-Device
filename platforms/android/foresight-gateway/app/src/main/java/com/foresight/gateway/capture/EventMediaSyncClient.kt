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
            callback(EventMediaSyncUiState(EventMediaSyncState.UPLOADING, null))
            callback(syncOne(eventId, baseUrl))
        }
    }

    /** Executes on the same single-threaded upload executor as individual sync requests. */
    fun syncAll(
        eventIds: List<String>,
        baseUrl: String,
        callback: (eventId: String, state: EventMediaSyncUiState, completed: Int, total: Int) -> Unit,
    ) {
        executor.execute {
            val uniqueEventIds = eventIds.distinct()
            if (uniqueEventIds.isEmpty()) {
                callback("", EventMediaSyncUiState(EventMediaSyncState.LOCAL_ONLY, "No pending event media"), 0, 0)
                return@execute
            }
            uniqueEventIds.forEachIndexed { index, eventId ->
                callback(eventId, EventMediaSyncUiState(EventMediaSyncState.UPLOADING), index, uniqueEventIds.size)
                callback(eventId, syncOne(eventId, baseUrl), index + 1, uniqueEventIds.size)
            }
        }
    }

    private fun syncOne(eventId: String, baseUrl: String): EventMediaSyncUiState {
        val attemptPlan = runCatching { repository.beginEventMediaSync(eventId, baseUrl.trim()) }.getOrElse { error ->
            return EventMediaSyncUiState(EventMediaSyncState.FAILED, error.message)
        }
        return runCatching { upload(attemptPlan.syncPlan, baseUrl) }.fold(
            onSuccess = { acknowledgement ->
                repository.completeEventMediaSync(
                    eventId,
                    attemptPlan.attempt.attemptId,
                    acknowledgement.authoritativeMediaSha256,
                    acknowledgement.detail,
                )
                EventMediaSyncUiState(EventMediaSyncState.SYNCED, acknowledgement.detail)
            },
            onFailure = { error ->
                val detail = error.message ?: error.javaClass.simpleName
                repository.failEventMediaSync(eventId, attemptPlan.attempt.attemptId, detail)
                logWarning("Event media sync failed: eventId=$eventId; $detail", error)
                EventMediaSyncUiState(EventMediaSyncState.FAILED, detail)
            },
        )
    }

    private fun upload(plan: EventMediaSyncPlan, baseUrl: String): SyncUploadAcknowledgement {
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
            connection.setRequestProperty("X-Foresight-Capture-Generation", plan.recording.captureGeneration.toString())
            connection.setRequestProperty("X-Foresight-Source-Recording-Sha256", plan.recording.sha256.orEmpty())
            connection.setRequestProperty("X-Foresight-Event-Authority", plan.event.authority.name)
            connection.setRequestProperty(
                "X-Foresight-Event-Origin",
                if (plan.event.authority == LocalEventAuthority.PHONE_FIELD) "phone_field" else "laptop_control",
            )
            plan.event.terminationReason?.let {
                connection.setRequestProperty("X-Foresight-Event-Termination-Reason", it.name)
            }
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
            require(response.optBoolean("validated")) { "laptop did not confirm media validation" }
            val authoritativeSha = response.optString("authoritative_media_sha256")
            require(authoritativeSha == plan.media.outputSha256) { "laptop returned another authoritative SHA-256" }
            return SyncUploadAcknowledgement(
                authoritativeMediaSha256 = authoritativeSha,
                detail = if (response.optBoolean("idempotent")) "already verified by laptop" else "verified by laptop",
            )
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

private data class SyncUploadAcknowledgement(
    val authoritativeMediaSha256: String,
    val detail: String,
)
