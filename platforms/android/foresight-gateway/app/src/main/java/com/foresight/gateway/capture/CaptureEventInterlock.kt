package com.foresight.gateway.capture

/** Laptop-authoritative event states that must retain the media source. */
internal object CaptureEventInterlock {
    private val captureStopBlockingStates = setOf("recording_bounded_event", "finalizing")

    fun blocksCaptureStop(eventState: String): Boolean = eventState in captureStopBlockingStates
}
