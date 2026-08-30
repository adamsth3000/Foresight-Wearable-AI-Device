package com.foresight.gateway.transport

/**
 * Tracks an activity-owned preview surface independently from the service-owned stream.
 * A transport rebuild detaches the old EGL target but retains the activity's request so it
 * can be attached to the replacement RootEncoder stream.
 */
internal class PreviewAttachmentState {
    private var requested = false
    private var attached = false

    fun request(): Boolean {
        requested = true
        return !attached
    }

    fun markAttached() {
        check(requested) { "A preview cannot attach without an activity request." }
        attached = true
    }

    fun releaseRequest(): Boolean {
        val wasAttached = attached
        requested = false
        attached = false
        return wasAttached
    }

    fun detachForTransportStop(): Boolean {
        val wasAttached = attached
        attached = false
        return wasAttached
    }

    fun shouldAttach(): Boolean = requested && !attached

    fun isAttached(): Boolean = attached
}
