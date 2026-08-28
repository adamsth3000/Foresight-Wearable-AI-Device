package com.foresight.gateway.transport

/** Deterministic bounded exponential delays for one intended RTSP capture session. */
class RtspReconnectPolicy(
    private val initialDelayMillis: Long = 1_000L,
    private val maximumDelayMillis: Long = 8_000L,
) {
    private var attempts = 0

    fun nextDelayMillis(): Long {
        val exponent = attempts.coerceAtMost(3)
        attempts += 1
        return (initialDelayMillis shl exponent).coerceAtMost(maximumDelayMillis)
    }

    fun attempts(): Int = attempts

    fun reset() {
        attempts = 0
    }
}
