package com.foresight.gateway.capture

import com.foresight.gateway.transport.StreamLifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureControllerStateTest {
    @Test
    fun `retirement makes a second start legal`() {
        val state = CaptureControllerState()

        assertNull(state.startRejectionReason())
        state.beginStartDispatch()
        state.publisherLifecycleChanged(StreamLifecycle.PREPARING)
        state.publisherLifecycleChanged(StreamLifecycle.STREAMING)
        state.publisherLifecycleChanged(StreamLifecycle.STOPPING)
        state.publisherLifecycleChanged(StreamLifecycle.IDLE)

        assertFalse(state.hasActiveSession)
        assertEquals(StreamLifecycle.IDLE, state.lifecycle)
        assertNull(state.startRejectionReason())
        state.beginStartDispatch()
        assertTrue(state.startDispatchInFlight)
        assertFalse(state.hasActiveSession)
        state.publisherLifecycleChanged(StreamLifecycle.PREPARING)
        assertTrue(state.hasActiveSession)
        assertEquals(StreamLifecycle.PREPARING, state.lifecycle)
    }

    @Test
    fun `start remains rejected until publisher retirement completes`() {
        val state = CaptureControllerState()
        state.beginStartDispatch()
        state.publisherLifecycleChanged(StreamLifecycle.PREPARING)
        state.publisherLifecycleChanged(StreamLifecycle.STOPPING)

        assertEquals("an active capture session is still owned by the controller", state.startRejectionReason())

        state.publisherLifecycleChanged(StreamLifecycle.IDLE)

        assertNull(state.startRejectionReason())
    }

    @Test
    fun `terminal publisher error clears stale session ownership`() {
        val state = CaptureControllerState()
        state.beginStartDispatch()
        state.publisherLifecycleChanged(StreamLifecycle.PREPARING)
        state.publisherLifecycleChanged(StreamLifecycle.ERROR)

        assertFalse(state.hasActiveSession)
        assertEquals(StreamLifecycle.ERROR, state.lifecycle)
        assertEquals("controller lifecycle is ERROR", state.startRejectionReason())
    }

    @Test
    fun `publisher rejection rolls back provisional second start ownership`() {
        val state = CaptureControllerState()

        state.beginStartDispatch()
        assertTrue(state.startDispatchInFlight)
        assertFalse(state.hasActiveSession)

        state.rollbackStartDispatch()

        assertEquals(StreamLifecycle.IDLE, state.lifecycle)
        assertFalse(state.startDispatchInFlight)
        assertFalse(state.hasActiveSession)
        assertNull(state.startRejectionReason())
    }
}
