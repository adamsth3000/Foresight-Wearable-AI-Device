package com.foresight.gateway.ui

import com.foresight.gateway.control.EventControlState
import com.foresight.gateway.capture.CaptureEventInterlock
import com.foresight.gateway.transport.StreamLifecycle

/** Maps authoritative service and laptop responses to the visible gateway controls. */
internal data class GatewayPresentation(
    val capture: StreamLifecycle,
    val event: EventControlState,
) {
    val captureLightOn: Boolean get() = capture == StreamLifecycle.STREAMING
    val startCaptureEnabled: Boolean get() = capture == StreamLifecycle.IDLE || capture == StreamLifecycle.ERROR
    val endCaptureEnabled: Boolean
        get() = !startCaptureEnabled && capture != StreamLifecycle.STOPPING &&
            !CaptureEventInterlock.blocksCaptureStop(event.state)

    val captureLabel: String get() = when (capture) {
        StreamLifecycle.IDLE -> "STOPPED"
        StreamLifecycle.STREAMING -> "STREAMING"
        else -> capture.name
    }

    val eventLightOn: Boolean get() = event.state == "recording_bounded_event"
    val startEventEnabled: Boolean get() = capture == StreamLifecycle.STREAMING && event.canStartBounded
    val endEventEnabled: Boolean get() = capture == StreamLifecycle.STREAMING && event.canEndBounded
    val quickEventEnabled: Boolean get() = capture == StreamLifecycle.STREAMING && event.state == "idle"

    val eventLabel: String get() = when (event.state) {
        "idle" -> "IDLE"
        "recording_bounded_event" -> "RECORDING"
        "finalizing" -> "FINALIZING"
        "quick_event_pending" -> "PENDING"
        else -> "ERROR"
    }
}
