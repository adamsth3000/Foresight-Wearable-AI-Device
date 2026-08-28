package com.foresight.gateway.transport

/** Tracks app-owned retry timer state separately from RootEncoder's internal connection work. */
class RtspRetryState {
    var isTimerScheduled = false
        private set
    var isAttemptInFlight = false
        private set

    fun schedule(): Boolean {
        if (isTimerScheduled || isAttemptInFlight) return false
        isTimerScheduled = true
        return true
    }

    fun fireTimer(): Boolean {
        if (!isTimerScheduled) return false
        isTimerScheduled = false
        isAttemptInFlight = true
        return true
    }

    fun connectionStarted() {
        // A connection-start callback only proves RootEncoder entered its handshake. Keep the
        // attempt in flight until it succeeds or fails so the app-owned deadline stays active.
    }

    fun connectionFailed() {
        isAttemptInFlight = false
    }

    fun attemptDeadlineExpired(): Boolean {
        if (!isAttemptInFlight) return false
        isAttemptInFlight = false
        return true
    }

    fun reset() {
        isTimerScheduled = false
        isAttemptInFlight = false
    }
}
