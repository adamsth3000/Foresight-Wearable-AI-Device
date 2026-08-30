package com.foresight.gateway.capture

import java.time.Instant
import org.junit.Assert.assertTrue
import org.junit.Test

class EventMediaExtractionPolicyTest {
    @Test
    fun `valid finalized recording and ready event produce extraction plan`() {
        val decision = EventMediaExtractionPolicy.decide(recording(), event(), null)

        assertTrue(decision is EventMediaExtractionDecision.Extract)
    }

    @Test
    fun `unfinished or interrupted recording is rejected`() {
        assertRejected(EventMediaExtractionPolicy.decide(recording(finalized = false), event(), null), "not finalized")
        assertRejected(EventMediaExtractionPolicy.decide(recording(interrupted = true), event(), null), "interrupted")
    }

    @Test
    fun `missing source hash nonready event and invalid interval are rejected`() {
        assertRejected(EventMediaExtractionPolicy.decide(recording(sha256 = null), event(), null), "SHA-256")
        assertRejected(EventMediaExtractionPolicy.decide(recording(), event(state = LocalEventMappingState.STARTED), null), "not READY")
        assertRejected(EventMediaExtractionPolicy.decide(recording(), event(start = 4_000L, end = 4_000L), null), "not positive")
    }

    @Test
    fun `matching ready metadata is idempotent`() {
        val ready = EventMediaMetadata(
            eventId = "event-1",
            recordingId = "recording-1",
            sourceRecordingSha256 = "a".repeat(64),
            outputFileName = "event-event-1.mp4",
            extractionMethod = "android_mediaextractor_mediummuxer_remux",
            requestedStartOffsetMillis = 1_000L,
            requestedEndOffsetMillis = 5_000L,
            extractionState = EventMediaExtractionState.READY,
        )

        val decision = EventMediaExtractionPolicy.decide(recording(), event(), ready)

        assertTrue(decision is EventMediaExtractionDecision.ExistingReady)
    }

    @Test
    fun `ready output whose source hash differs is a conflict`() {
        val ready = EventMediaMetadata(
            eventId = "event-1",
            recordingId = "recording-1",
            sourceRecordingSha256 = "b".repeat(64),
            outputFileName = "event-event-1.mp4",
            extractionMethod = "android_mediaextractor_mediummuxer_remux",
            requestedStartOffsetMillis = 1_000L,
            requestedEndOffsetMillis = 5_000L,
            extractionState = EventMediaExtractionState.READY,
        )

        assertRejected(EventMediaExtractionPolicy.decide(recording(), event(), ready), "conflicts")
    }

    @Test
    fun `active extraction is not scheduled a second time`() {
        val extracting = EventMediaMetadata(
            eventId = "event-1",
            recordingId = "recording-1",
            sourceRecordingSha256 = "a".repeat(64),
            outputFileName = "event-event-1.mp4",
            extractionMethod = "android_mediaextractor_mediummuxer_remux",
            requestedStartOffsetMillis = 1_000L,
            requestedEndOffsetMillis = 5_000L,
            extractionState = EventMediaExtractionState.EXTRACTING,
        )

        assertRejected(EventMediaExtractionPolicy.decide(recording(), event(), extracting), "already active")
    }

    private fun assertRejected(decision: EventMediaExtractionDecision, expectedReason: String) {
        assertTrue(decision is EventMediaExtractionDecision.Rejected)
        assertTrue((decision as EventMediaExtractionDecision.Rejected).reason.contains(expectedReason))
    }

    private fun recording(
        finalized: Boolean = true,
        interrupted: Boolean = false,
        sha256: String? = "a".repeat(64),
    ) = LocalRecordingMetadata(
        recordingId = "recording-1",
        sourceSessionId = "source-session-1",
        captureGeneration = 1,
        recordingStartUtc = Instant.EPOCH,
        recordingStartMonotonicMillis = 1L,
        localMediaFileName = "capture-recording-1-g1.mp4",
        finalized = finalized,
        interrupted = interrupted,
        sha256 = sha256,
    )

    private fun event(
        start: Long = 1_000L,
        end: Long? = 5_000L,
        state: LocalEventMappingState = LocalEventMappingState.READY,
    ) = LocalEventMapping(
        eventId = "event-1",
        recordingId = "recording-1",
        observedStartUtc = Instant.EPOCH,
        observedStartMonotonicMillis = 1_000L,
        startOffsetMillis = start,
        observedEndUtc = Instant.EPOCH,
        observedEndMonotonicMillis = end,
        endOffsetMillis = end,
        durationMillis = end?.minus(start),
        state = state,
    )
}
