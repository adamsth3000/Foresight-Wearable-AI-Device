package com.foresight.gateway.capture

import java.io.File
import java.nio.file.Files
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalRecordingMetadataRepositoryTest {
    @Test
    fun `recording metadata is persisted immediately without final media hash`() {
        val repository = repository()

        repository.createRecording(context())

        val record = requireNotNull(repository.snapshot().recordings["recording-1"])
        assertFalse(record.finalized)
        assertFalse(record.interrupted)
        assertEquals(null, record.byteSize)
        assertEquals(null, record.sha256)
        assertEquals("capture-recording-1-g7.mp4", record.localMediaFileName)
    }

    @Test
    fun `authoritative start and end persist a ready duration across reload`() {
        val root = temporaryRoot()
        val repository = repository(root)
        repository.createRecording(context())
        val start = boundary("event-1", 13_000L)
        val end = boundary("event-1", 18_500L)

        repository.recordAuthoritativeStart(start)
        repository.recordAuthoritativeEnd(start, end)
        val media = File(File(root, "recordings"), "capture-recording-1-g7.mp4")
        requireNotNull(media.parentFile).mkdirs()
        media.writeText("finalized event media")
        repository.finalizeRecording(context(), Instant.parse("2026-08-30T12:00:10Z"))

        val reloaded = repository(root).snapshot().events.getValue("event-1")
        assertEquals(LocalEventMappingState.READY, reloaded.state)
        assertEquals(3_000L, reloaded.startOffsetMillis)
        assertEquals(8_500L, reloaded.endOffsetMillis)
        assertEquals(5_500L, reloaded.durationMillis)
    }

    @Test
    fun `multiple events retain independent mappings for one recording`() {
        val repository = repository()
        repository.createRecording(context())
        val firstStart = boundary("event-1", 11_000L)
        val firstEnd = boundary("event-1", 12_000L)
        val secondStart = boundary("event-2", 14_000L)
        val secondEnd = boundary("event-2", 19_000L)

        repository.recordAuthoritativeStart(firstStart)
        repository.recordAuthoritativeEnd(firstStart, firstEnd)
        repository.recordAuthoritativeStart(secondStart)
        repository.recordAuthoritativeEnd(secondStart, secondEnd)

        assertEquals(2, repository.snapshot().events.size)
        assertEquals("recording-1", repository.snapshot().events.getValue("event-1").recordingId)
        assertEquals("recording-1", repository.snapshot().events.getValue("event-2").recordingId)
    }

    @Test
    fun `finalization writes size and streaming hash only after media exists`() {
        val root = temporaryRoot()
        val repository = repository(root)
        repository.createRecording(context())
        val media = File(File(root, "recordings"), "capture-recording-1-g7.mp4")
        requireNotNull(media.parentFile).mkdirs()
        media.writeBytes("foresight-local-media".toByteArray())

        val finalized = repository.finalizeRecording(context(), Instant.parse("2026-08-30T12:00:10Z"))

        assertTrue(finalized.finalized)
        assertFalse(finalized.interrupted)
        assertEquals(media.length(), finalized.byteSize)
        assertNotNull(finalized.sha256)
        assertTrue(finalized.sha256.orEmpty().matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `restart marks unfinished recording and its event as interrupted`() {
        val root = temporaryRoot()
        val original = repository(root)
        original.createRecording(context())
        original.recordAuthoritativeStart(boundary("event-1", 12_000L))

        val recovered = repository(root).snapshot()

        assertTrue(recovered.recordings.getValue("recording-1").interrupted)
        assertFalse(recovered.recordings.getValue("recording-1").finalized)
        assertEquals(LocalEventMappingState.INTERRUPTED, recovered.events.getValue("event-1").state)
    }

    @Test
    fun `restart preserves finalized recording as valid`() {
        val root = temporaryRoot()
        val original = repository(root)
        original.createRecording(context())
        val media = File(File(root, "recordings"), "capture-recording-1-g7.mp4")
        requireNotNull(media.parentFile).mkdirs()
        media.writeText("finalized")
        original.finalizeRecording(context(), Instant.parse("2026-08-30T12:00:10Z"))

        val reloaded = repository(root).snapshot().recordings.getValue("recording-1")

        assertTrue(reloaded.finalized)
        assertFalse(reloaded.interrupted)
    }

    @Test
    fun `mismatched event end remains rejected and does not corrupt persisted start`() {
        val repository = repository()
        repository.createRecording(context())
        val start = boundary("event-1", 12_000L)
        repository.recordAuthoritativeStart(start)
        val wrongRecordingEnd = boundary("event-1", 14_000L, recordingId = "recording-2")

        assertThrows(IllegalArgumentException::class.java) {
            repository.recordAuthoritativeEnd(start, wrongRecordingEnd)
        }

        assertEquals(LocalEventMappingState.STARTED, repository.snapshot().events.getValue("event-1").state)
    }

    @Test
    fun `malformed ledger is preserved and does not crash repository startup`() {
        val root = temporaryRoot()
        val metadata = File(root, "recording_metadata")
        metadata.mkdirs()
        File(metadata, "local-recording-ledger.json").writeText("not json")
        val warnings = mutableListOf<String>()

        val repository = repository(root, LocalRecordingRepositoryLogger { message, _ -> warnings += message })

        assertTrue(repository.snapshot().recordings.isEmpty())
        assertTrue(warnings.any { it.contains("malformed") })
        assertTrue(metadata.listFiles().orEmpty().any { it.name.startsWith("local-recording-ledger.json.corrupt-") })
    }

    @Test
    fun `atomic update remains reloadable after every persisted change`() {
        val root = temporaryRoot()
        val repository = repository(root)
        repository.createRecording(context())
        repository(root).snapshot().recordings.getValue("recording-1")
        repository.recordAuthoritativeStart(boundary("event-1", 12_000L))

        val reloaded = repository(root).snapshot()

        assertEquals(1, reloaded.recordings.size)
        assertEquals(1, reloaded.events.size)
    }

    @Test
    fun `duplicate recording id cannot create a second logical record`() {
        val repository = repository()
        repository.createRecording(context())

        val repeated = repository.createRecording(context())
        assertEquals(1, repository.snapshot().recordings.size)
        assertEquals("recording-1", repeated.recordingId)

        assertThrows(IllegalArgumentException::class.java) {
            repository.createRecording(context().copy(localMediaFileName = "other.mp4"))
        }
    }

    @Test
    fun `event-media completion persists requested and actual boundary bookkeeping`() {
        val root = temporaryRoot()
        val repository = readyRepository(root)
        val decision = repository.beginEventMediaExtraction("event-1")
        assertTrue(decision is EventMediaExtractionDecision.Extract)
        val plan = (decision as EventMediaExtractionDecision.Extract).plan

        val ready = repository.completeEventMediaExtraction(
            plan = plan,
            actualStartOffsetMillis = 1_800L,
            actualEndOffsetMillis = 5_900L,
            outputByteSize = 123L,
            outputSha256 = "b".repeat(64),
            videoPresent = true,
            audioPresent = true,
        )

        assertEquals(EventMediaExtractionState.READY, ready.extractionState)
        assertEquals(EventMediaSyncState.LOCAL_ONLY, ready.syncState)
        assertEquals(800L, ready.boundaryAdjustmentStartMillis)
        assertEquals(900L, ready.boundaryAdjustmentEndMillis)
        assertEquals(4_100L, ready.outputDurationMillis)
        assertTrue(repository.beginEventMediaExtraction("event-1") is EventMediaExtractionDecision.ExistingReady)
        assertEquals("event-1", repository(root).latestSyncableEventId())
        assertEquals(EventMediaSyncState.LOCAL_ONLY, repository(root).eventMediaSyncState("event-1"))
    }

    @Test
    fun `several ready events can begin extraction from one finalized recording`() {
        val root = temporaryRoot()
        val repository = readyRepository(root)
        addReadyEvent(repository, "event-2", 16_000L, 20_000L)

        assertTrue(repository.beginEventMediaExtraction("event-1") is EventMediaExtractionDecision.Extract)
        assertTrue(repository.beginEventMediaExtraction("event-2") is EventMediaExtractionDecision.Extract)
        assertEquals(2, repository.snapshot().eventMedia.size)
    }

    @Test
    fun `stale extracting metadata becomes failed after restart`() {
        val root = temporaryRoot()
        val repository = readyRepository(root)
        repository.beginEventMediaExtraction("event-1")

        val recovered = repository(root).snapshot().eventMedia.getValue("event-1")

        assertEquals(EventMediaExtractionState.FAILED, recovered.extractionState)
        assertTrue(recovered.failureDetail.orEmpty().contains("process ended"))
    }

    @Test
    fun `failed event-media extraction is retryable`() {
        val repository = readyRepository(temporaryRoot())
        repository.beginEventMediaExtraction("event-1")
        repository.failEventMediaExtraction("event-1", "synthetic failure")

        assertTrue(repository.beginEventMediaExtraction("event-1") is EventMediaExtractionDecision.Extract)
    }

    @Test
    fun `metadata preparation never mutates finalized source media`() {
        val root = temporaryRoot()
        val repository = readyRepository(root)
        val source = File(File(root, "recordings"), "capture-recording-1-g7.mp4")
        val before = source.readBytes()

        repository.beginEventMediaExtraction("event-1")

        assertTrue(before.contentEquals(source.readBytes()))
    }

    @Test
    fun `rejected extraction precondition persists an exact failed status`() {
        val root = temporaryRoot()
        val repository = repository(root)
        repository.createRecording(context())
        val start = boundary("event-1", 11_000L)
        val end = boundary("event-1", 15_000L)
        repository.recordAuthoritativeStart(start)
        repository.recordAuthoritativeEnd(start, end)

        val decision = repository.beginEventMediaExtraction("event-1")
        val failed = repository.snapshot().eventMedia.getValue("event-1")

        assertTrue(decision is EventMediaExtractionDecision.Rejected)
        assertEquals(EventMediaExtractionState.FAILED, failed.extractionState)
        assertTrue(failed.failureDetail.orEmpty().contains("not finalized"))
    }

    @Test
    fun `missing finalized source file is rejected before extraction begins`() {
        val root = temporaryRoot()
        val repository = readyRepository(root)
        val source = File(File(root, "recordings"), "capture-recording-1-g7.mp4")
        assertTrue(source.delete())

        val decision = repository.beginEventMediaExtraction("event-1")

        assertTrue(decision is EventMediaExtractionDecision.Rejected)
        assertEquals(EventMediaExtractionState.FAILED, repository.snapshot().eventMedia.getValue("event-1").extractionState)
        assertTrue(repository.snapshot().eventMedia.getValue("event-1").failureDetail.orEmpty().contains("file is missing"))
    }

    @Test
    fun `only READY event media can transition through persisted sync states`() {
        val root = temporaryRoot()
        val repository = readyRepository(root)
        assertThrows(IllegalArgumentException::class.java) { repository.beginEventMediaSync("event-1") }

        val plan = repository.beginEventMediaExtraction("event-1") as EventMediaExtractionDecision.Extract
        val output = File(File(root, "event_media"), plan.plan.outputFileName)
        requireNotNull(output.parentFile).mkdirs()
        output.writeText("private extracted event media")
        val sha256 = java.security.MessageDigest.getInstance("SHA-256")
            .digest(output.readBytes()).joinToString("") { "%02x".format(it) }
        repository.completeEventMediaExtraction(
            plan.plan, 1_000L, 5_000L, output.length(), sha256, videoPresent = true, audioPresent = true,
        )

        val sync = repository.beginEventMediaSync("event-1")
        assertEquals(EventMediaSyncState.UPLOADING, repository.snapshot().eventMedia.getValue("event-1").syncState)
        assertTrue(sync.privateFile.exists())
        repository.failEventMediaSync("event-1", "offline")
        assertEquals(EventMediaSyncState.FAILED, repository.snapshot().eventMedia.getValue("event-1").syncState)
        repository.beginEventMediaSync("event-1")
        repository.completeEventMediaSync("event-1")
        assertEquals(EventMediaSyncState.SYNCED, repository(root).snapshot().eventMedia.getValue("event-1").syncState)
        assertTrue(output.exists())
    }

    private fun repository(
        root: File = temporaryRoot(),
        logger: LocalRecordingRepositoryLogger = LocalRecordingRepositoryLogger { _, _ -> },
    ): LocalRecordingMetadataRepository = LocalRecordingMetadataRepository(
        metadataDirectory = File(root, "recording_metadata"),
        recordingsDirectory = File(root, "recordings"),
        logger = logger,
    )

    private fun temporaryRoot(): File = Files.createTempDirectory("foresight-recording-ledger-").toFile()

    private fun readyRepository(root: File): LocalRecordingMetadataRepository {
        val repository = repository(root)
        repository.createRecording(context())
        val source = File(File(root, "recordings"), "capture-recording-1-g7.mp4")
        requireNotNull(source.parentFile).mkdirs()
        source.writeText("immutable finalized local MP4 test placeholder")
        repository.finalizeRecording(context(), Instant.parse("2026-08-30T12:00:30Z"))
        addReadyEvent(repository, "event-1", 11_000L, 15_000L)
        return repository
    }

    private fun addReadyEvent(
        repository: LocalRecordingMetadataRepository,
        eventId: String,
        startMonotonicMillis: Long,
        endMonotonicMillis: Long,
    ) {
        val start = boundary(eventId, startMonotonicMillis)
        val end = boundary(eventId, endMonotonicMillis)
        repository.recordAuthoritativeStart(start)
        repository.recordAuthoritativeEnd(start, end)
    }

    private fun context(): LocalRecordingContext = LocalRecordingContext(
        recordingId = "recording-1",
        sourceSessionId = "source-session-1",
        captureGeneration = 7,
        localMediaFileName = "capture-recording-1-g7.mp4",
        startedUtc = Instant.parse("2026-08-30T12:00:00Z"),
        startedMonotonicMillis = 10_000L,
        isRecording = true,
    )

    private fun boundary(eventId: String, monotonicMillis: Long, recordingId: String = "recording-1"): LocalEventBoundary =
        LocalEventBoundary(
            eventId = eventId,
            recordingId = recordingId,
            sourceSessionId = "source-session-1",
            captureGeneration = 7,
            receiptUtc = Instant.parse("2026-08-30T12:00:00Z").plusMillis(monotonicMillis - 10_000L),
            receiptMonotonicMillis = monotonicMillis,
            recordingOffsetMillis = monotonicMillis - 10_000L,
        )
}
