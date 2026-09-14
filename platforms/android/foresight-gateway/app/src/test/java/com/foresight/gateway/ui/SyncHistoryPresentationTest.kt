package com.foresight.gateway.ui

import com.foresight.gateway.capture.EventMediaSyncAttemptResult
import com.foresight.gateway.capture.EventMediaSyncHistoryEntry
import com.foresight.gateway.capture.LocalEventAuthority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class SyncHistoryPresentationTest {
    @Test
    fun `successful history heading uses persisted completion time in device zone`() {
        val entry = entry(EventMediaSyncAttemptResult.SYNCED, Instant.parse("2026-09-02T14:34:18Z"))

        val heading = SyncHistoryPresentation.heading(entry, Locale.US, ZoneId.of("America/New_York"))

        assertEquals("09/02/2026 10:34:18 AM - SYNCED", heading)
    }

    @Test
    fun `failed history heading uses persisted terminal time and retains seconds`() {
        val entry = entry(EventMediaSyncAttemptResult.FAILED, Instant.parse("2026-09-02T14:36:02Z"))

        val heading = SyncHistoryPresentation.heading(entry, Locale.US, ZoneId.of("America/New_York"))

        assertEquals("09/02/2026 10:36:02 AM - FAILED", heading)
        assertTrue(heading.contains(":02"))
    }

    @Test
    fun `incomplete attempt falls back to persisted attempt timestamp rather than current time`() {
        val entry = entry(null, null)

        val heading = SyncHistoryPresentation.heading(entry, Locale.US, ZoneId.of("UTC"))

        assertEquals("09/02/2026 02:30:00 PM - FAILED", heading)
    }

    private fun entry(result: EventMediaSyncAttemptResult?, completed: Instant?) = EventMediaSyncHistoryEntry(
        attemptId = "attempt-1",
        eventId = "event-1",
        eventOrigin = "phone_field",
        authority = LocalEventAuthority.PHONE_FIELD,
        startedUtc = Instant.parse("2026-09-02T14:30:00Z"),
        destinationIdentity = "http://laptop:8766",
        localMediaSha256 = "abc",
        byteSize = 100,
        completedUtc = completed,
        result = result,
        failureReason = if (result == EventMediaSyncAttemptResult.FAILED) "connection aborted" else null,
    )
}
