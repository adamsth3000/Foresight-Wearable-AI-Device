package com.foresight.gateway.transport

/**
 * Keeps destructive RootEncoder replacement separate from recoverable network failure.
 * One RtspStream owns both RTP delivery and local recording, so a recording-active stream may
 * enter degraded transport mode but must not be released just to recover RTSP.
 */
internal object LocalFirstTransportPolicy {
    fun replacementAction(localRecordingActive: Boolean): ReplacementAction =
        if (localRecordingActive) ReplacementAction.DEGRADE else ReplacementAction.REBUILD

    fun shouldEnterDegradedMode(localRecordingActive: Boolean, retryAttempts: Int, maxAttempts: Int): Boolean =
        localRecordingActive && retryAttempts >= maxAttempts

    enum class ReplacementAction {
        DEGRADE,
        REBUILD,
    }
}
