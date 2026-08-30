package com.foresight.gateway.capture

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventMediaSyncClientTest {
    @Test
    fun `READY media streams private metadata then persists SYNCED without deleting file`() {
        val root = Files.createTempDirectory("foresight-event-sync-").toFile()
        val repository = repositoryWithReadyMedia(root)
        var connection: FakeConnection? = null
        val client = EventMediaSyncClient(repository) { url ->
            FakeConnection(url, 200, "{\"state\":\"synced\",\"event_id\":\"event-1\",\"sha256\":\"${sha256(File(root, "event_media/event-event-1.mp4"))}\"}").also {
                connection = it
            }
        }
        val states = mutableListOf<EventMediaSyncUiState>()
        val completed = CountDownLatch(1)

        client.sync("event-1", "http://192.168.1.171:8766") {
            states += it
            if (it.state != EventMediaSyncState.UPLOADING) completed.countDown()
        }

        assertTrue(completed.await(3, TimeUnit.SECONDS))
        val sent = requireNotNull(connection)
        assertEquals("http://192.168.1.171:8766/events/event-1/phone-media", sent.url.toString())
        assertEquals("application/octet-stream", sent.headers["Content-Type"])
        assertEquals("recording-1", sent.headers["X-Foresight-Recording-Id"])
        assertEquals("source-session-1", sent.headers["X-Foresight-Source-Session-Id"])
        assertTrue(sent.body.size() > 0)
        assertEquals(EventMediaSyncState.SYNCED, states.last().state)
        assertEquals(EventMediaSyncState.SYNCED, repository.snapshot().eventMedia.getValue("event-1").syncState)
        assertTrue(File(root, "event_media/event-event-1.mp4").isFile)
    }

    @Test
    fun `failed upload remains retryable and retains local media`() {
        val root = Files.createTempDirectory("foresight-event-sync-").toFile()
        val repository = repositoryWithReadyMedia(root)
        val client = EventMediaSyncClient(repository) { FakeConnection(it, 409, "conflict") }
        val completed = CountDownLatch(1)

        client.sync("event-1", "http://192.168.1.171:8766") {
            if (it.state == EventMediaSyncState.FAILED) completed.countDown()
        }

        assertTrue(completed.await(3, TimeUnit.SECONDS))
        assertEquals(EventMediaSyncState.FAILED, repository.snapshot().eventMedia.getValue("event-1").syncState)
        assertTrue(File(root, "event_media/event-event-1.mp4").isFile)
    }

    @Test
    fun `sync endpoint rejects public hosts and preserves explicit Tailscale hostnames`() {
        val repository = LocalRecordingMetadataRepository(File("build/test-metadata"), File("build/test-recordings"))
        val client = EventMediaSyncClient(repository)

        assertTrue(runCatching { client.endpoint("https://example.com", "event-1") }.isFailure)
        assertEquals(
            "https://phone-laptop.tailnet.ts.net/events/event-1/phone-media",
            client.endpoint("https://phone-laptop.tailnet.ts.net", "event-1").toString(),
        )
    }

    private fun repositoryWithReadyMedia(root: File): LocalRecordingMetadataRepository {
        val repository = LocalRecordingMetadataRepository(File(root, "recording_metadata"), File(root, "recordings"))
        val context = LocalRecordingContext(
            "recording-1", "source-session-1", 1, "capture-recording-1.mp4",
            Instant.parse("2026-08-30T12:00:00Z"), 1_000L, true,
        )
        repository.createRecording(context)
        val recording = File(root, "recordings/capture-recording-1.mp4")
        requireNotNull(recording.parentFile).mkdirs()
        recording.writeText("source recording")
        repository.finalizeRecording(context, Instant.parse("2026-08-30T12:00:20Z"))
        val start = LocalEventBoundary("event-1", "recording-1", "source-session-1", 1, Instant.parse("2026-08-30T12:00:01Z"), 2_000L, 1_000L)
        val end = LocalEventBoundary("event-1", "recording-1", "source-session-1", 1, Instant.parse("2026-08-30T12:00:03Z"), 4_000L, 3_000L)
        repository.recordAuthoritativeStart(start)
        repository.recordAuthoritativeEnd(start, end)
        val extraction = repository.beginEventMediaExtraction("event-1") as EventMediaExtractionDecision.Extract
        val output = File(root, "event_media/${extraction.plan.outputFileName}")
        requireNotNull(output.parentFile).mkdirs()
        output.writeText("private event media")
        repository.completeEventMediaExtraction(
            extraction.plan, 1_000L, 3_000L, output.length(), sha256(output), true, true,
        )
        return repository
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private class FakeConnection(url: URL, private val status: Int, private val response: String) : HttpURLConnection(url) {
        val body = ByteArrayOutputStream()
        val headers = mutableMapOf<String, String>()
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun connect() = Unit
        override fun getOutputStream(): OutputStream = body
        override fun getResponseCode(): Int = status
        override fun getInputStream(): InputStream = ByteArrayInputStream(response.toByteArray())
        override fun getErrorStream(): InputStream? = if (status in 200..299) null else ByteArrayInputStream(response.toByteArray())
        override fun setRequestProperty(key: String, value: String) { headers[key] = value }
    }
}
