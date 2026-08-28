package com.foresight.gateway.metadata

import java.time.Instant
import java.util.UUID

/** Source-local provenance plus an optional later binding to a laptop capture session. */
data class CaptureSessionMetadata(
    val sourceSessionId: String = UUID.randomUUID().toString(),
    val captureSessionId: String? = null,
    val clockAnchor: ClockAnchor = ClockAnchor(),
    val sessionStartUtc: Instant = clockAnchor.utc,
    val elapsedRealtimeNanos: Long = clockAnchor.elapsedRealtimeNanos,
    val source: MediaSourceDescriptor = MediaSourceDescriptor(),
    val streamEndpoint: String,
    val streamStartedUtc: Instant? = null,
)
