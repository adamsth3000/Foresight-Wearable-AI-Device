package com.foresight.gateway.transport

/** Explicit stream states surfaced to the foreground notification and UI. */
enum class StreamLifecycle {
    IDLE,
    PREPARING,
    CONNECTING,
    RECONNECTING,
    STREAMING,
    STOPPING,
    ERROR,
}
