package com.foresight.gateway.metadata

import android.os.SystemClock
import java.time.Instant
import java.util.UUID

/** Minimal Phase 1A provenance retained in memory for the active stream. */
data class CaptureSessionMetadata(
    val captureSessionId: String = UUID.randomUUID().toString(),
    val sessionStartUtc: Instant = Instant.now(),
    val elapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos(),
    val source: MediaSourceDescriptor = MediaSourceDescriptor(),
    val streamEndpoint: String,
    val streamStartedUtc: Instant? = null,
)
