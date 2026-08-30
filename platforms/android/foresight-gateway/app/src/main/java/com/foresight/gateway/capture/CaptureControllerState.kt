package com.foresight.gateway.capture

import com.foresight.gateway.transport.StreamLifecycle

/** Keeps controller admission separate from UI status and RootEncoder callbacks. */
internal class CaptureControllerState {
    var lifecycle: StreamLifecycle = StreamLifecycle.IDLE
        private set
    var hasActiveSession: Boolean = false
        private set
    var startDispatchInFlight: Boolean = false
        private set

    fun startRejectionReason(): String? = when {
        hasActiveSession -> "an active capture session is still owned by the controller"
        startDispatchInFlight -> "a publisher start dispatch is already in flight"
        lifecycle != StreamLifecycle.IDLE -> "controller lifecycle is $lifecycle"
        else -> null
    }

    fun beginStartDispatch() {
        check(startRejectionReason() == null) { "Capture start is not legal." }
        startDispatchInFlight = true
    }

    fun rollbackStartDispatch() {
        hasActiveSession = false
        startDispatchInFlight = false
        lifecycle = StreamLifecycle.IDLE
    }

    fun publisherLifecycleChanged(next: StreamLifecycle) {
        lifecycle = next
        if (next == StreamLifecycle.PREPARING && startDispatchInFlight) {
            hasActiveSession = true
            startDispatchInFlight = false
        }
        if (next == StreamLifecycle.IDLE || next == StreamLifecycle.ERROR) {
            hasActiveSession = false
            startDispatchInFlight = false
        }
    }
}
