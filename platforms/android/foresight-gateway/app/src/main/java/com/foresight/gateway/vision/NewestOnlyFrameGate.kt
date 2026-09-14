package com.foresight.gateway.vision

/** Pure gate used by the sampler to ensure only one PixelCopy request exists at a time. */
internal class NewestOnlyFrameGate {
    private var requestInFlight = false
    private var lastRequestElapsedMs: Long? = null

    fun tryBegin(nowElapsedMs: Long, intervalMs: Long): Boolean {
        require(intervalMs > 0) { "Vision sampling interval must be positive." }
        if (requestInFlight || lastRequestElapsedMs?.let { nowElapsedMs - it < intervalMs } == true) return false
        requestInFlight = true
        lastRequestElapsedMs = nowElapsedMs
        return true
    }

    fun complete() {
        requestInFlight = false
    }

    fun reset() {
        requestInFlight = false
        lastRequestElapsedMs = null
    }

    fun isInFlight(): Boolean = requestInFlight
}
