package com.foresight.gateway.control

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

data class EventControlState(val state: String = "idle", val eventId: String? = null) {
    val canStartBounded: Boolean get() = state == "idle" || state == "quick_event_pending"
    val canEndBounded: Boolean get() = state == "recording_bounded_event"
}

internal data class EventControlRequest(
    val url: URL,
    val method: String,
    val contentType: String,
    val body: ByteArray,
)

/** UI-facing state remains unchanged until a laptop-authoritative response succeeds. */
internal data class EventControlUiState(
    val event: EventControlState = EventControlState(),
    val detail: String? = null,
) {
    fun pending(action: String): EventControlUiState = copy(detail = "$action event command pending...")

    fun apply(result: Result<EventControlState>): EventControlUiState = result.fold(
        onSuccess = { response -> copy(event = response, detail = "Event control updated: ${response.state}") },
        onFailure = { error -> copy(detail = "Event command failed: ${error.message ?: "network error"}") },
    )

    fun applyStatus(result: Result<EventControlState>): EventControlUiState = result.fold(
        onSuccess = { response -> copy(event = response, detail = null) },
        onFailure = { error ->
            copy(detail = "Event status unavailable: ${error.message ?: "network error"}")
        },
    )
}

internal class EventControlHttpException(
    val statusCode: Int,
    val responseBody: String,
) : IllegalStateException("HTTP $statusCode: $responseBody")

internal interface EventControlLogger {
    fun info(message: String)
    fun debug(message: String)
    fun warn(message: String, error: Throwable? = null)
}

internal fun interface EventControlResponseParser {
    fun parse(body: String): EventControlState
}

private object AndroidEventControlLogger : EventControlLogger {
    override fun info(message: String) {
        Log.i(TAG, message)
    }

    override fun debug(message: String) {
        Log.d(TAG, message)
    }

    override fun warn(message: String, error: Throwable?) {
        if (error == null) Log.w(TAG, message) else Log.w(TAG, message, error)
    }

    private const val TAG = "EventControlClient"
}

private object NoOpEventControlLogger : EventControlLogger {
    override fun info(message: String) = Unit
    override fun debug(message: String) = Unit
    override fun warn(message: String, error: Throwable?) = Unit
}

private object JsonEventControlResponseParser : EventControlResponseParser {
    override fun parse(body: String): EventControlState {
        val response = JSONObject(body)
        return EventControlState(
            response.getString("state"),
            response.optString("event_id").ifBlank { null },
        )
    }
}

/** LAN control client; capture state changes only after laptop-authoritative JSON succeeds. */
class EventControlClient private constructor(
    private val connectionOpener: (URL) -> HttpURLConnection,
    private val logger: EventControlLogger,
    private val responseParser: EventControlResponseParser,
) {
    constructor() : this(
        { url -> url.openConnection() as HttpURLConnection },
        AndroidEventControlLogger,
        JsonEventControlResponseParser,
    )

    internal constructor(connectionOpener: (URL) -> HttpURLConnection) :
        this(connectionOpener, NoOpEventControlLogger, EventControlResponseParser {
            EventControlState("test_response")
        })

    private val executor = Executors.newSingleThreadExecutor()

    fun post(baseUrl: String, action: String, callback: (Result<EventControlState>) -> Unit) {
        val validation = runCatching { buildRequest(baseUrl, action) }
        if (validation.isFailure) {
            val error = requireNotNull(validation.exceptionOrNull())
            logger.warn("Local control endpoint validation failed: ${error.message}", error)
            callback(Result.failure(error))
            return
        }
        logger.info("Visible event action tapped: action=$action base=${baseUrl.trim()}")
        executor.execute {
            callback(runCatching { request(baseUrl, action) })
        }
    }

    /** Refresh the laptop-authoritative bounded-event state without changing it locally. */
    fun status(baseUrl: String, callback: (Result<EventControlState>) -> Unit) {
        val validation = runCatching { buildStatusUrl(baseUrl) }
        if (validation.isFailure) {
            callback(Result.failure(requireNotNull(validation.exceptionOrNull())))
            return
        }
        executor.execute { callback(runCatching { requestStatus(baseUrl) }) }
    }

    internal fun request(baseUrl: String, action: String): EventControlState {
        val request = buildRequest(baseUrl, action)
        logger.info("POST /events/$action base=${baseUrl.trim()}")
        val connection = connectionOpener(request.url)
        return try {
            connection.requestMethod = request.method
            connection.connectTimeout = 1_500
            connection.readTimeout = 2_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", request.contentType)
            connection.setRequestProperty("Accept", "application/json")
            // The Python receiver rejects zero-byte POSTs. Set the length explicitly rather than
            // relying on HttpURLConnection's deferred/chunked output behavior.
            connection.setFixedLengthStreamingMode(request.body.size)
            connection.outputStream.use {
                it.write(request.body)
                it.flush()
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(StandardCharsets.UTF_8).use { it?.readText().orEmpty() }
            if (status !in 200..299) {
                logger.warn("POST /events/$action failed: HTTP $status body=$body")
                throw EventControlHttpException(status, body)
            }
            responseParser.parse(body).also {
                logger.info("POST /events/$action succeeded: HTTP $status state=${it.state} eventId=${it.eventId}")
            }
        } catch (error: Exception) {
            if (error !is EventControlHttpException) {
                logger.warn("POST /events/$action failed before a valid response.", error)
            }
            throw error
        } finally {
            connection.disconnect()
        }
    }

    internal fun requestStatus(baseUrl: String): EventControlState {
        val url = buildStatusUrl(baseUrl)
        logger.info("GET /events/status base=${baseUrl.trim()}")
        val connection = connectionOpener(url)
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 1_500
            connection.readTimeout = 2_000
            connection.setRequestProperty("Accept", "application/json")
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(StandardCharsets.UTF_8).use { it?.readText().orEmpty() }
            if (status !in 200..299) throw EventControlHttpException(status, body)
            responseParser.parse(body).also {
                logger.debug("GET /events/status succeeded: HTTP $status state=${it.state} eventId=${it.eventId}")
            }
        } catch (error: Exception) {
            logger.warn("GET /events/status failed; preserving the last authoritative event state.", error)
            throw error
        } finally {
            connection.disconnect()
        }
    }

    internal fun buildRequest(baseUrl: String, action: String): EventControlRequest {
        require(action in VALID_ACTIONS) { "Unsupported event action" }
        val normalized = normalizedBaseUrl(baseUrl)
        return EventControlRequest(
            url = URL("$normalized/events/$action"),
            method = "POST",
            contentType = "application/json",
            body = EMPTY_JSON_BODY,
        )
    }

    private fun buildStatusUrl(baseUrl: String): URL = URL("${normalizedBaseUrl(baseUrl)}/events/status")

    private fun normalizedBaseUrl(baseUrl: String): String {
        val normalized = baseUrl.trim().trimEnd('/')
        require(normalized.isNotBlank()) { "Control endpoint not configured" }
        val base = URL(normalized)
        require(base.protocol == "http") { "Control endpoint must use http:// on the local LAN" }
        require(base.path.isEmpty() || base.path == "/") {
            "Control endpoint must be the receiver base URL, without a path suffix"
        }
        return normalized
    }

    private companion object {
        val VALID_ACTIONS = setOf("start", "end", "quick")
        val EMPTY_JSON_BODY = "{}".toByteArray(StandardCharsets.UTF_8)
    }
}
