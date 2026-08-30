package com.foresight.gateway.capture

import java.time.Instant

data class LocalRecordingContext(
    val recordingId: String,
    val sourceSessionId: String,
    val captureGeneration: Int,
    val localMediaFileName: String,
    val startedUtc: Instant,
    val startedMonotonicMillis: Long,
    val isRecording: Boolean,
)

internal data class LocalEventBoundary(
    val eventId: String,
    val recordingId: String,
    val sourceSessionId: String,
    val captureGeneration: Int,
    val receiptUtc: Instant,
    val receiptMonotonicMillis: Long,
    val recordingOffsetMillis: Long,
)

/** Service-owned, in-memory bridge from laptop-authoritative event responses to local media. */
internal class LocalRecordingEventMapper {
    private val starts = mutableMapOf<String, LocalEventBoundary>()

    fun start(eventId: String, context: LocalRecordingContext, utc: Instant, monotonicMillis: Long): LocalEventBoundary {
        require(context.isRecording) { "no active local recording" }
        require(eventId !in starts) { "event already has an active local boundary" }
        return LocalEventBoundary(eventId, context.recordingId, context.sourceSessionId, context.captureGeneration, utc, monotonicMillis,
            (monotonicMillis - context.startedMonotonicMillis).coerceAtLeast(0L)).also { starts[eventId] = it }
    }

    fun end(eventId: String, context: LocalRecordingContext, utc: Instant, monotonicMillis: Long): Pair<LocalEventBoundary, LocalEventBoundary> {
        require(context.isRecording) { "no active local recording" }
        val start = requireNotNull(starts[eventId]) { "no matching authoritative event start" }
        require(start.recordingId == context.recordingId) { "event belongs to a different local recording" }
        starts.remove(eventId)
        val end = LocalEventBoundary(eventId, context.recordingId, context.sourceSessionId, context.captureGeneration, utc, monotonicMillis,
            (monotonicMillis - context.startedMonotonicMillis).coerceAtLeast(0L))
        return start to end
    }
}
