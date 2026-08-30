package com.foresight.gateway.capture

internal data class EventMediaExtractionPlan(
    val eventId: String,
    val recording: LocalRecordingMetadata,
    val event: LocalEventMapping,
    val outputFileName: String,
) {
    val requestedStartOffsetMillis: Long get() = event.startOffsetMillis
    val requestedEndOffsetMillis: Long get() = requireNotNull(event.endOffsetMillis)
}

internal sealed interface EventMediaExtractionDecision {
    data class Extract(val plan: EventMediaExtractionPlan) : EventMediaExtractionDecision
    data class ExistingReady(val metadata: EventMediaMetadata) : EventMediaExtractionDecision
    data class Rejected(val reason: String) : EventMediaExtractionDecision
}

/** Pure precondition checks shared by durable metadata and the Android remux worker. */
internal object EventMediaExtractionPolicy {
    fun decide(
        recording: LocalRecordingMetadata?,
        event: LocalEventMapping?,
        existing: EventMediaMetadata?,
    ): EventMediaExtractionDecision {
        if (recording == null) return EventMediaExtractionDecision.Rejected("source recording metadata is missing")
        if (!recording.finalized) return EventMediaExtractionDecision.Rejected("source recording is not finalized")
        if (recording.interrupted) return EventMediaExtractionDecision.Rejected("source recording is interrupted")
        if (recording.sha256.isNullOrBlank()) return EventMediaExtractionDecision.Rejected("source recording SHA-256 is missing")
        if (event == null) return EventMediaExtractionDecision.Rejected("authoritative event mapping is missing")
        if (!event.eventId.matches(SAFE_EVENT_ID)) {
            return EventMediaExtractionDecision.Rejected("event ID is not safe for a private media filename")
        }
        if (event.recordingId != recording.recordingId) return EventMediaExtractionDecision.Rejected("event references another recording")
        if (event.state != LocalEventMappingState.READY) return EventMediaExtractionDecision.Rejected("event mapping is not READY")
        val endOffset = event.endOffsetMillis
            ?: return EventMediaExtractionDecision.Rejected("event end offset is missing")
        if (endOffset <= event.startOffsetMillis) {
            return EventMediaExtractionDecision.Rejected("event interval is not positive")
        }
        if (existing?.extractionState == EventMediaExtractionState.EXTRACTING) {
            return EventMediaExtractionDecision.Rejected("event-media extraction is already active")
        }
        if (existing?.extractionState == EventMediaExtractionState.READY) {
            return if (
                existing.recordingId == recording.recordingId &&
                existing.sourceRecordingSha256 == recording.sha256 &&
                existing.requestedStartOffsetMillis == event.startOffsetMillis &&
                existing.requestedEndOffsetMillis == endOffset
            ) {
                EventMediaExtractionDecision.ExistingReady(existing)
            } else {
                EventMediaExtractionDecision.Rejected("existing READY event media conflicts with current source metadata")
            }
        }
        return EventMediaExtractionDecision.Extract(
            EventMediaExtractionPlan(
                eventId = event.eventId,
                recording = recording,
                event = event,
                outputFileName = "event-${event.eventId}.mp4",
            ),
        )
    }

    private val SAFE_EVENT_ID = Regex("[A-Za-z0-9_-]+")
}
