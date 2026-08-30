package com.foresight.gateway.ui

import com.foresight.gateway.control.EventControlState
import com.foresight.gateway.transport.StreamLifecycle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayPresentationTest {
    @Test
    fun `stopped capture has no red light and only start capture enabled`() {
        val state = GatewayPresentation(StreamLifecycle.IDLE, EventControlState())

        assertFalse(state.captureLightOn)
        assertTrue(state.startCaptureEnabled)
        assertFalse(state.endCaptureEnabled)
        assertFalse(state.startEventEnabled)
        assertFalse(state.endEventEnabled)
        assertFalse(state.quickEventEnabled)
    }

    @Test
    fun `streaming capture has a red light and only end capture enabled`() {
        val state = GatewayPresentation(StreamLifecycle.STREAMING, EventControlState())

        assertTrue(state.captureLightOn)
        assertFalse(state.startCaptureEnabled)
        assertTrue(state.endCaptureEnabled)
    }

    @Test
    fun `idle event exposes start and quick without a green light`() {
        val state = GatewayPresentation(StreamLifecycle.STREAMING, EventControlState())

        assertFalse(state.eventLightOn)
        assertTrue(state.startEventEnabled)
        assertFalse(state.endEventEnabled)
        assertTrue(state.quickEventEnabled)
    }

    @Test
    fun `recording bounded event has green light and only end enabled`() {
        val state = GatewayPresentation(
            StreamLifecycle.STREAMING,
            EventControlState("recording_bounded_event", "event-1"),
        )

        assertTrue(state.eventLightOn)
        assertFalse(state.startEventEnabled)
        assertTrue(state.endEventEnabled)
        assertFalse(state.quickEventEnabled)
        assertFalse(state.endCaptureEnabled)
    }

    @Test
    fun `finalizing event has no green light and disables all event actions`() {
        val state = GatewayPresentation(StreamLifecycle.STREAMING, EventControlState("finalizing", "event-1"))

        assertFalse(state.eventLightOn)
        assertFalse(state.startEventEnabled)
        assertFalse(state.endEventEnabled)
        assertFalse(state.quickEventEnabled)
        assertFalse(state.endCaptureEnabled)
        assertTrue(state.eventLabel == "FINALIZING")
    }
}
