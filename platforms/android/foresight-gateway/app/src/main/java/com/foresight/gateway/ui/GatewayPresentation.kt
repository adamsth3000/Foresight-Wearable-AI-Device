package com.foresight.gateway.ui

import com.foresight.gateway.control.EventControlState
import com.foresight.gateway.capture.CaptureEventInterlock
import com.foresight.gateway.capture.EventMediaExtractionState
import com.foresight.gateway.capture.EventMediaSyncState
import com.foresight.gateway.transport.StreamLifecycle
import com.foresight.gateway.mode.GatewayOperatingMode
import com.foresight.gateway.capture.FieldEventReadiness

/** Maps authoritative service and laptop responses to the visible gateway controls. */
internal data class GatewayPresentation(
    val mode: GatewayOperatingMode,
    val capture: StreamLifecycle,
    val event: EventControlState,
    val fieldEventReadiness: FieldEventReadiness = FieldEventReadiness.NOT_CAPTURING,
) {
    val captureLightOn: Boolean
        get() = capture == StreamLifecycle.STREAMING ||
            capture == StreamLifecycle.RECONNECTING ||
            capture == StreamLifecycle.DEGRADED ||
            capture == StreamLifecycle.OFFLINE

    val startCaptureEnabled: Boolean
        get() = capture == StreamLifecycle.IDLE ||
            capture == StreamLifecycle.ERROR

    val endCaptureEnabled: Boolean
        get() = !startCaptureEnabled &&
            capture != StreamLifecycle.STOPPING &&
            (
                mode == GatewayOperatingMode.FIELD ||
                    !CaptureEventInterlock.blocksCaptureStop(event.state)
                )

    val captureLabel: String
        get() = when (capture) {
            StreamLifecycle.IDLE -> "STOPPED"
            StreamLifecycle.STREAMING -> "STREAMING"
            StreamLifecycle.DEGRADED -> "LOCAL RECORDING / RTSP DEGRADED"
            StreamLifecycle.OFFLINE -> "LOCAL RECORDING / RTSP OFFLINE"
            else -> capture.name
        }

    val eventLightOn: Boolean
        get() = event.state == "recording_bounded_event"

    val localCaptureActive: Boolean
        get() = capture == StreamLifecycle.STREAMING ||
            capture == StreamLifecycle.RECONNECTING ||
            capture == StreamLifecycle.DEGRADED ||
            capture == StreamLifecycle.OFFLINE

    /**
     * FIELD event eligibility is authoritative in FieldEventReadiness.
     *
     * The visible EventControlState may intentionally remain FINALIZING while a completed event's
     * local media is being extracted. That historical UI state must not block a new FIELD event
     * once the capture service reports that another bounded event may start.
     *
     * LAB mode continues using EventControlState because its event lifecycle is controlled by the
     * laptop/control API path.
     */
    val startEventEnabled: Boolean
        get() = if (mode == GatewayOperatingMode.FIELD) {
            fieldEventReadiness.canStartEvent
        } else {
            capture == StreamLifecycle.STREAMING &&
                event.canStartBounded
        }

    /**
     * As with START, FIELD END eligibility comes from the service's durable local-event state.
     * LAB retains the existing EventControlState gate.
     */
    val endEventEnabled: Boolean
        get() = if (mode == GatewayOperatingMode.FIELD) {
            fieldEventReadiness.canEndEvent
        } else {
            localCaptureActive &&
                event.canEndBounded
        }

    val quickEventEnabled: Boolean
        get() = mode == GatewayOperatingMode.LAB &&
            capture == StreamLifecycle.STREAMING &&
            event.state == "idle"

    val eventLabel: String
        get() = when (event.state) {
            "idle" -> "IDLE"
            "recording_bounded_event" -> "RECORDING"
            "finalizing" -> "FINALIZING"
            "quick_event_pending" -> "PENDING"
            else -> "ERROR"
        }
}

/** Keeps the visible sync control tied to durable local-media state, not control API state. */
internal data class GatewaySyncPresentation(
    val eventId: String?,
    val extractionState: EventMediaExtractionState?,
    val syncState: EventMediaSyncState?,
    val serviceBound: Boolean,
) {
    val buttonVisible: Boolean
        get() = true

    val buttonEnabled: Boolean
        get() = serviceBound &&
            extractionState == EventMediaExtractionState.READY &&
            syncState != EventMediaSyncState.UPLOADING

    val reason: String
        get() = when {
            eventId == null ->
                "No local event is available"

            !serviceBound ->
                "Capture service unavailable"

            extractionState == null ->
                "Awaiting local event metadata"

            extractionState == EventMediaExtractionState.PENDING ||
                extractionState == EventMediaExtractionState.EXTRACTING ->
                "Waiting for local extraction"

            extractionState == EventMediaExtractionState.FAILED ->
                "Local extraction failed"

            syncState == EventMediaSyncState.UPLOADING ->
                "Sync in progress"

            syncState == EventMediaSyncState.SYNCED ->
                "Verified by laptop"

            syncState == EventMediaSyncState.FAILED ->
                "Previous sync failed; retry is available"

            else ->
                "Ready to sync retained local media"
        }
}