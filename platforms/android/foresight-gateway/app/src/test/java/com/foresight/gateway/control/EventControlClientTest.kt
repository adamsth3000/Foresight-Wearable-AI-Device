package com.foresight.gateway.control

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventControlClientTest {
    @Test
    fun `start request uses exact LAN endpoint and nonempty JSON POST body`() {
        assertRequest("start")
    }

    @Test
    fun `end request uses exact LAN endpoint and nonempty JSON POST body`() {
        assertRequest("end")
    }

    @Test
    fun `quick request uses exact LAN endpoint and nonempty JSON POST body`() {
        assertRequest("quick")
    }

    @Test
    fun `successful response becomes the authoritative UI event state`() {
        val updated = EventControlUiState().apply(
            Result.success(EventControlState("recording_bounded_event", "event-1")),
        )

        assertEquals("recording_bounded_event", updated.event.state)
        assertEquals("event-1", updated.event.eventId)
        assertTrue(updated.event.canEndBounded)
    }

    @Test
    fun `failed response keeps the prior authoritative event state and surfaces server detail`() {
        val initial = EventControlUiState(EventControlState("recording_bounded_event", "event-1"))
        val updated = initial.apply(Result.failure(EventControlHttpException(400, "request body is required")))

        assertEquals(initial.event, updated.event)
        assertTrue(updated.detail.orEmpty().contains("HTTP 400: request body is required"))
        assertFalse(updated.event.canStartBounded)
    }

    @Test
    fun `server error body is exposed by the actual HTTP request`() {
        var connection: FakeConnection? = null
        val client = EventControlClient { url ->
            FakeConnection(url, 400, "request body must be between 1 and 1000000 bytes").also {
                connection = it
            }
        }

        val error = runCatching { client.request("http://192.168.1.171:8766", "start") }.exceptionOrNull()

        assertTrue(error is EventControlHttpException)
        assertTrue(error?.message.orEmpty().contains("request body must be between 1 and 1000000 bytes"))
        assertEquals("{}", connection?.body?.toString(Charsets.UTF_8))
    }

    @Test
    fun `blank control base fails locally without opening a connection`() {
        var connectionOpened = false
        val client = EventControlClient {
            connectionOpened = true
            FakeConnection(it, 200, "{\"state\":\"idle\"}")
        }

        val error = runCatching { client.request("", "start") }.exceptionOrNull()

        assertTrue(error?.message.orEmpty().contains("Control endpoint not configured"))
        assertFalse(connectionOpened)
    }

    @Test
    fun `status request uses the exact laptop status endpoint`() {
        var connection: FakeConnection? = null
        val client = EventControlClient { url ->
            FakeConnection(url, 200, "{\"state\":\"finalizing\",\"event_id\":\"event-1\"}").also {
                connection = it
            }
        }

        val state = client.requestStatus("http://192.168.1.171:8766/")

        assertEquals("http://192.168.1.171:8766/events/status", connection?.url.toString())
        assertEquals("GET", connection?.requestMethod)
        assertEquals("test_response", state.state)
    }

    @Test
    fun `failed status refresh preserves the prior authoritative event state`() {
        val initial = EventControlUiState(EventControlState("finalizing", "event-1"))

        val updated = initial.applyStatus(Result.failure(EventControlHttpException(503, "receiver unavailable")))

        assertEquals(initial.event, updated.event)
        assertTrue(updated.detail.orEmpty().contains("Event status unavailable"))
    }

    @Test
    fun `later successful status refresh restores idle presentation state`() {
        val initial = EventControlUiState(EventControlState("finalizing", "event-1"))

        val updated = initial.applyStatus(Result.success(EventControlState("idle", null)))

        assertEquals(EventControlState("idle", null), updated.event)
        assertEquals(null, updated.detail)
    }

    private fun assertRequest(action: String) {
        var connection: FakeConnection? = null
        val requestClient = EventControlClient { url ->
            FakeConnection(url, 200, "{\"state\":\"recording_bounded_event\",\"event_id\":\"event-1\"}").also {
                connection = it
            }
        }
        requestClient.request("http://192.168.1.171:8766/", action)
        val captured = requireNotNull(connection)

        assertEquals("http://192.168.1.171:8766/events/$action", captured.url.toString())
        assertEquals("POST", captured.requestMethod)
        assertEquals("application/json", captured.requestHeaders["Content-Type"])
        assertEquals(2, captured.fixedLength)
        assertEquals("{}", captured.body.toString(Charsets.UTF_8))
    }

    private class FakeConnection(
        url: URL,
        private val status: Int,
        private val response: String,
    ) : HttpURLConnection(url) {
        val body = ByteArrayOutputStream()
        val requestHeaders = mutableMapOf<String, String>()
        var fixedLength = -1

        override fun disconnect() = Unit

        override fun usingProxy(): Boolean = false

        override fun connect() = Unit

        override fun getOutputStream(): OutputStream = body

        override fun getResponseCode(): Int = status

        override fun getInputStream(): InputStream = ByteArrayInputStream(response.toByteArray())

        override fun getErrorStream(): InputStream? =
            if (status in 200..299) null else ByteArrayInputStream(response.toByteArray())

        override fun setRequestProperty(key: String, value: String) {
            requestHeaders[key] = value
        }

        override fun setFixedLengthStreamingMode(contentLength: Int) {
            fixedLength = contentLength
        }
    }
}
