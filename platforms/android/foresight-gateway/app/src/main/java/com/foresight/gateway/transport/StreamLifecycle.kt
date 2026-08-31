package com.foresight.gateway.transport

/** Explicit stream states surfaced to the foreground notification and UI. */
enum class StreamLifecycle {
    IDLE,
    PREPARING,
    CONNECTING,
    RECONNECTING,
    /** Live RTSP is unavailable while the local camera/audio recording remains authoritative. */
    DEGRADED,
    /** No RTSP endpoint is configured; a Field local recording is active. */
    OFFLINE,
    STREAMING,
    STOPPING,
    ERROR,
}
