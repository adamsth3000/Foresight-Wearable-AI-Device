package com.foresight.gateway.capture

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalRecordingEventMapperTest {
    private val recording = LocalRecordingContext(
        recordingId = "recording-1",
        sourceSessionId = "phone-session-1",
        captureGeneration = 7,
        localMediaFileName = "capture-recording-1-g7.mp4",
        startedUtc = Instant.parse("2026-08-30T12:00:00Z"),
        startedMonotonicMillis = 10_000L,
        isRecording = true,
    )

    @Test
    fun `maps authoritative start and end to recording-relative monotonic offsets`() {
        val mapper = LocalRecordingEventMapper()

        val start = mapper.start("event-1", recording, Instant.parse("2026-08-30T12:00:03Z"), 13_250L)
        val (_, end) = mapper.end("event-1", recording, Instant.parse("2026-08-30T12:00:08Z"), 18_900L)

        assertEquals("recording-1", start.recordingId)
        assertEquals("phone-session-1", start.sourceSessionId)
        assertEquals(7, start.captureGeneration)
        assertEquals(3_250L, start.recordingOffsetMillis)
        assertEquals(8_900L, end.recordingOffsetMillis)
    }

    @Test
    fun `allows multiple authoritative events in one recording`() {
        val mapper = LocalRecordingEventMapper()
        mapper.start("event-1", recording, recording.startedUtc, 10_000L)
        mapper.start("event-2", recording, recording.startedUtc, 11_000L)

        val (secondStart, secondEnd) = mapper.end("event-2", recording, recording.startedUtc, 12_000L)
        val (_, firstEnd) = mapper.end("event-1", recording, recording.startedUtc, 13_000L)

        assertEquals(1_000L, secondStart.recordingOffsetMillis)
        assertEquals(2_000L, secondEnd.recordingOffsetMillis)
        assertEquals(3_000L, firstEnd.recordingOffsetMillis)
    }

    @Test
    fun `rejects events when no active recording exists`() {
        val inactive = recording.copy(isRecording = false)
        val mapper = LocalRecordingEventMapper()

        assertThrows(IllegalArgumentException::class.java) {
            mapper.start("event-1", inactive, recording.startedUtc, 10_000L)
        }
    }

    @Test
    fun `does not accept duplicate or unmatched authoritative event IDs`() {
        val mapper = LocalRecordingEventMapper()
        mapper.start("event-1", recording, recording.startedUtc, 10_000L)

        assertThrows(IllegalArgumentException::class.java) {
            mapper.start("event-1", recording, recording.startedUtc, 10_100L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            mapper.end("unknown-event", recording, recording.startedUtc, 10_100L)
        }
    }

    @Test
    fun `mismatched recording does not consume the pending event boundary`() {
        val mapper = LocalRecordingEventMapper()
        mapper.start("event-1", recording, recording.startedUtc, 10_000L)
        val replacementRecording = recording.copy(recordingId = "recording-2")

        assertThrows(IllegalArgumentException::class.java) {
            mapper.end("event-1", replacementRecording, recording.startedUtc, 12_000L)
        }

        val (_, end) = mapper.end("event-1", recording, recording.startedUtc, 12_000L)
        assertEquals("recording-1", end.recordingId)
    }

    @Test
    fun `clamps a receipt before recording clock anchor to zero`() {
        val mapper = LocalRecordingEventMapper()

        val boundary = mapper.start("event-1", recording, recording.startedUtc, 9_000L)

        assertEquals(0L, boundary.recordingOffsetMillis)
    }
}
