package com.foresight.gateway.transport

import com.foresight.gateway.control.EventControlState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewAttachmentStateTest {
    @Test
    fun `activity attach request becomes attached only once`() {
        val state = PreviewAttachmentState()

        assertTrue(state.request())
        assertTrue(state.shouldAttach())
        state.markAttached()

        assertTrue(state.isAttached())
        assertFalse(state.request())
    }

    @Test
    fun `activity recreation releases then reattaches the preview request`() {
        val state = PreviewAttachmentState()
        state.request()
        state.markAttached()

        assertTrue(state.releaseRequest())
        assertFalse(state.shouldAttach())

        assertTrue(state.request())
        state.markAttached()
        assertTrue(state.isAttached())
    }

    @Test
    fun `transport replacement retains an activity preview request`() {
        val state = PreviewAttachmentState()
        state.request()
        state.markAttached()

        assertTrue(state.detachForTransportStop())
        assertTrue(state.shouldAttach())
        assertFalse(state.isAttached())
    }

    @Test
    fun `repeated transport replacement retains one preview request`() {
        val preview = PreviewAttachmentState()

        preview.request()
        preview.markAttached()
        preview.detachForTransportStop()
        preview.markAttached()
        preview.detachForTransportStop()

        assertTrue(preview.shouldAttach())
        assertFalse(preview.isAttached())
    }

    @Test
    fun `preview lifecycle does not alter authoritative event-control state`() {
        val preview = PreviewAttachmentState()
        val recordingEvent = EventControlState("recording_bounded_event", "event-1")

        preview.request()
        preview.markAttached()
        preview.releaseRequest()

        assertFalse(recordingEvent.canStartBounded)
        assertTrue(recordingEvent.canEndBounded)
        assertTrue(recordingEvent.eventId == "event-1")
    }
}
