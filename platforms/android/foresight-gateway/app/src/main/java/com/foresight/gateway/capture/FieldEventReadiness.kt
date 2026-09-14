package com.foresight.gateway.capture

/** FIELD authority depends on local capture evidence, never laptop or RTSP transport success. */
internal data class FieldEventReadiness(
    val captureSessionActive: Boolean,
    val localRecordingReady: Boolean,
    val eventAlreadyActive: Boolean,
    val reason: String,
) {
    val canStartEvent: Boolean get() = captureSessionActive && localRecordingReady && !eventAlreadyActive
    val canEndEvent: Boolean get() = captureSessionActive && localRecordingReady && eventAlreadyActive

    companion object {
        val NOT_CAPTURING = FieldEventReadiness(false, false, false, "waiting for capture")
    }
}
