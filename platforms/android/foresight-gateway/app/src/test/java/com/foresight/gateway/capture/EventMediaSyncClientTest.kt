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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventMediaSyncClientTest {
    @Test
    fun `READY media streams private metadata then persists SYNCED without deleting file`() {
        val root = Files.createTempDirectory("foresight-event-sync-").toFile()
        val repository = repositoryWithReadyMedia(root)
        var connection: FakeConnection? = null
        val client = EventMediaSyncClient(repository) { url ->
            successConnection(url, root).also {
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
        assertEquals("laptop_control", sent.headers["X-Foresight-Event-Origin"])
        assertEquals("LAPTOP", sent.headers["X-Foresight-Event-Authority"])
        assertTrue(sent.body.size() > 0)
        assertEquals(EventMediaSyncState.SYNCED, states.last().state)
        assertEquals(EventMediaSyncState.SYNCED, repository.snapshot().eventMedia.getValue("event-1").syncState)
        val history = repository.syncHistory().single()
        assertEquals(EventMediaSyncAttemptResult.SYNCED, history.result)
        assertTrue(history.laptopValidated)
        assertEquals(sha256(File(root, "event_media/event-event-1.mp4")), history.authoritativeMediaSha256)
        assertEquals("http://192.168.1.171:8766", history.destinationIdentity)
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
        assertEquals(EventMediaSyncAttemptResult.FAILED, repository.syncHistory().single().result)
        assertTrue(File(root, "event_media/event-event-1.mp4").isFile)
    }

    @Test
    fun `sync success requires an explicit laptop validation acknowledgement`() {
        val root = Files.createTempDirectory("foresight-event-sync-").toFile()
        val repository = repositoryWithReadyMedia(root)
        val sha256 = sha256(File(root, "event_media/event-event-1.mp4"))
        val client = EventMediaSyncClient(repository) {
            FakeConnection(it, 200, "{\"state\":\"synced\",\"event_id\":\"event-1\",\"sha256\":\"$sha256\"}")
        }
        val completed = CountDownLatch(1)

        client.sync("event-1", "http://192.168.1.171:8766") {
            if (it.state == EventMediaSyncState.FAILED) completed.countDown()
        }

        assertTrue(completed.await(3, TimeUnit.SECONDS))
        val attempt = repository.syncHistory().single()
        assertEquals(EventMediaSyncAttemptResult.FAILED, attempt.result)
        assertFalse(attempt.laptopValidated)
    }

    @Test
    fun `field event sync explicitly identifies phone authoritative provenance`() {
        val root = Files.createTempDirectory("foresight-field-sync-").toFile()
        val repository = repositoryWithReadyMedia(root, phoneField = true)
        var connection: FakeConnection? = null
        val client = EventMediaSyncClient(repository) { url ->
            successConnection(url, root).also { connection = it }
        }
        val completed = CountDownLatch(1)

        client.sync("event-1", "http://192.168.1.171:8766") {
            if (it.state == EventMediaSyncState.SYNCED) completed.countDown()
        }

        assertTrue(completed.await(3, TimeUnit.SECONDS))
        assertEquals("phone_field", requireNotNull(connection).headers["X-Foresight-Event-Origin"])
        assertEquals("PHONE_FIELD", connection?.headers?.get("X-Foresight-Event-Authority"))
        assertEquals("USER_END", connection?.headers?.get("X-Foresight-Event-Termination-Reason"))
        assertEquals("phone_field", repository.syncHistory().single().eventOrigin)
    }

    @Test
    fun `sync all serializes uploads and continues after an individual failure`() {
        val root = Files.createTempDirectory("foresight-sync-all-").toFile()
        val repository = repositoryWithTwoReadyMedia(root)
        var connectionCount = 0
        val client = EventMediaSyncClient(repository) { url ->
            connectionCount += 1
            if (connectionCount == 1) FakeConnection(url, 409, "offline") else successConnection(url, root, "event-2")
        }
        val completed = CountDownLatch(1)

        client.syncAll(listOf("event-1", "event-2"), "http://192.168.1.171:8766") { _, state, done, total ->
            if (done == total && state.state != EventMediaSyncState.UPLOADING) completed.countDown()
        }

        assertTrue(completed.await(3, TimeUnit.SECONDS))
        assertEquals(2, connectionCount)
        assertEquals(EventMediaSyncState.FAILED, repository.eventMediaSyncState("event-1"))
        assertEquals(EventMediaSyncState.SYNCED, repository.eventMediaSyncState("event-2"))
        assertEquals(2, repository.syncHistory().size)
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

    private fun repositoryWithReadyMedia(
        root: File,
        phoneField: Boolean = false,
    ): LocalRecordingMetadataRepository {
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
        if (phoneField) {
            repository.recordFieldStart(start)
            repository.completeFieldEvent("event-1", end, LocalEventTerminationReason.USER_END)
        } else {
            repository.recordAuthoritativeStart(start)
            repository.recordAuthoritativeEnd(start, end)
        }
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

    private fun successConnection(url: URL, root: File, eventId: String = "event-1"): FakeConnection {
        val sha256 = sha256(File(root, "event_media/event-$eventId.mp4"))
        return FakeConnection(
            url,
            200,
            "{\"state\":\"synced\",\"event_id\":\"$eventId\",\"sha256\":\"$sha256\",\"authoritative_media_sha256\":\"$sha256\",\"validated\":true}",
        )
    }

    private fun repositoryWithTwoReadyMedia(root: File): LocalRecordingMetadataRepository {
        val repository = repositoryWithReadyMedia(root)
        val start = LocalEventBoundary("event-2", "recording-1", "source-session-1", 1, Instant.parse("2026-08-30T12:00:05Z"), 6_000L, 5_000L)
        val end = LocalEventBoundary("event-2", "recording-1", "source-session-1", 1, Instant.parse("2026-08-30T12:00:07Z"), 8_000L, 7_000L)
        repository.recordAuthoritativeStart(start)
        repository.recordAuthoritativeEnd(start, end)
        val extraction = repository.beginEventMediaExtraction("event-2") as EventMediaExtractionDecision.Extract
        val output = File(root, "event_media/${extraction.plan.outputFileName}")
        requireNotNull(output.parentFile).mkdirs()
        output.writeText("private event media two")
        repository.completeEventMediaExtraction(
            extraction.plan,
            5_000L,
            7_000L,
            output.length(),
            sha256(output),
            true,
            true,
        )
        return repository
    }

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
