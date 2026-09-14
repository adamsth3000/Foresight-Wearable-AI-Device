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
    val mediaSource: LocalMediaSourceId = LocalMediaSourceId.PHONE_CAMERA,
    val mediaLocation: LocalMediaLocation = LocalMediaLocation.phoneCamera(localMediaFileName),
    val sourceGenerationId: String = captureGeneration.toString(),
    val availability: LocalMediaAvailability = LocalMediaAvailability.AVAILABLE,
    val recordingArmMonotonicNanos: Long? = null,
    val timelineAnchor: MediaTimelineAnchor? = null,
    val streamPath: String? = null,
    val container: String = "mp4",
    val width: Int = 1280,
    val height: Int = 720,
    val videoCodec: String = "h264",
    val configuredVideoBitrate: Int = 2_000_000,
    val videoFps: Int = 30,
    val audioCodec: String = "aac",
    val audioSampleRate: Int = 44_100,
    val audioChannels: Int = 2,
    val reportedAudioSampleRate: Int? = null,
    val terminationReason: String? = null,
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
        return boundary(eventId, context, utc, monotonicMillis).also { starts[eventId] = it }
    }

    fun end(eventId: String, context: LocalRecordingContext, utc: Instant, monotonicMillis: Long): Pair<LocalEventBoundary, LocalEventBoundary> {
        require(context.isRecording) { "no active local recording" }
        val start = requireNotNull(starts[eventId]) { "no matching authoritative event start" }
        require(start.recordingId == context.recordingId) { "event belongs to a different local recording" }
        starts.remove(eventId)
        val end = boundary(eventId, context, utc, monotonicMillis)
        return start to end
    }

    fun boundary(
        eventId: String,
        context: LocalRecordingContext,
        utc: Instant,
        monotonicMillis: Long,
    ): LocalEventBoundary {
        require(context.isRecording) { "no active local recording" }
        return LocalEventBoundary(
            eventId = eventId,
            recordingId = context.recordingId,
            sourceSessionId = context.sourceSessionId,
            captureGeneration = context.captureGeneration,
            receiptUtc = utc,
            receiptMonotonicMillis = monotonicMillis,
            recordingOffsetMillis = (monotonicMillis - context.startedMonotonicMillis).coerceAtLeast(0L),
        )
    }
}
