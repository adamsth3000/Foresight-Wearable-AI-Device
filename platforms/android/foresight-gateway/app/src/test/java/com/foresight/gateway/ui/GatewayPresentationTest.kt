package com.foresight.gateway.ui

import com.foresight.gateway.control.EventControlState
import com.foresight.gateway.capture.EventMediaExtractionState
import com.foresight.gateway.capture.EventMediaSyncState
import com.foresight.gateway.transport.StreamLifecycle
import com.foresight.gateway.mode.GatewayOperatingMode
import com.foresight.gateway.capture.FieldEventReadiness
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayPresentationTest {
    @Test
    fun `READY locally retained event is eligible for visible sync`() {
        val state = GatewaySyncPresentation(
            eventId = "event-1",
            extractionState = EventMediaExtractionState.READY,
            syncState = EventMediaSyncState.LOCAL_ONLY,
            serviceBound = true,
        )

        assertTrue(state.buttonVisible)
        assertTrue(state.buttonEnabled)
    }

    @Test
    fun `restart-recovered READY local event remains eligible for sync`() {
        val state = GatewaySyncPresentation(
            eventId = "event-1",
            extractionState = EventMediaExtractionState.READY,
            syncState = EventMediaSyncState.LOCAL_ONLY,
            serviceBound = true,
        )

        assertTrue(state.buttonEnabled)
        assertTrue(state.reason.contains("Ready"))
    }

    @Test
    fun `stopped capture has no red light and only start capture enabled`() {
        val state = GatewayPresentation(GatewayOperatingMode.LAB, StreamLifecycle.IDLE, EventControlState())

        assertFalse(state.captureLightOn)
        assertTrue(state.startCaptureEnabled)
        assertFalse(state.endCaptureEnabled)
        assertFalse(state.startEventEnabled)
        assertFalse(state.endEventEnabled)
        assertFalse(state.quickEventEnabled)
    }

    @Test
    fun `streaming capture has a red light and only end capture enabled`() {
        val state = GatewayPresentation(GatewayOperatingMode.LAB, StreamLifecycle.STREAMING, EventControlState())

        assertTrue(state.captureLightOn)
        assertFalse(state.startCaptureEnabled)
        assertTrue(state.endCaptureEnabled)
    }

    @Test
    fun `idle event exposes start and quick without a green light`() {
        val state = GatewayPresentation(GatewayOperatingMode.LAB, StreamLifecycle.STREAMING, EventControlState())

        assertFalse(state.eventLightOn)
        assertTrue(state.startEventEnabled)
        assertFalse(state.endEventEnabled)
        assertTrue(state.quickEventEnabled)
    }

    @Test
    fun `recording bounded event has green light and only end enabled`() {
        val state = GatewayPresentation(
            GatewayOperatingMode.LAB,
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
    fun `END event remains retryable while RTSP reconnects or is degraded`() {
        val event = EventControlState("recording_bounded_event", "event-1")

        assertTrue(GatewayPresentation(GatewayOperatingMode.LAB, StreamLifecycle.RECONNECTING, event).endEventEnabled)
        assertTrue(
            GatewayPresentation(
                GatewayOperatingMode.FIELD,
                StreamLifecycle.DEGRADED,
                event,
                FieldEventReadiness(true, true, true, "event already active"),
            ).endEventEnabled,
        )
    }

    @Test
    fun `field local capture remains controllable while RTSP is offline`() {
        val state = GatewayPresentation(GatewayOperatingMode.FIELD, StreamLifecycle.OFFLINE, EventControlState(), readyFieldRecording())

        assertFalse(state.startCaptureEnabled)
        assertTrue(state.endCaptureEnabled)
        assertTrue(state.captureLightOn)
        assertTrue(state.startEventEnabled)
        assertFalse(state.quickEventEnabled)
    }

    @Test
    fun `field event does not block capture stop because it closes locally`() {
        val state = GatewayPresentation(
            GatewayOperatingMode.FIELD,
            StreamLifecycle.OFFLINE,
            EventControlState("recording_bounded_event", "field-event"),
            FieldEventReadiness(true, true, true, "event already active"),
        )

        assertTrue(state.endCaptureEnabled)
        assertTrue(state.endEventEnabled)
    }

    @Test
    fun `finalizing event has no green light and disables all event actions`() {
        val state = GatewayPresentation(GatewayOperatingMode.LAB, StreamLifecycle.STREAMING, EventControlState("finalizing", "event-1"))

        assertFalse(state.eventLightOn)
        assertFalse(state.startEventEnabled)
        assertFalse(state.endEventEnabled)
        assertFalse(state.quickEventEnabled)
        assertFalse(state.endCaptureEnabled)
        assertTrue(state.eventLabel == "FINALIZING")
    }

    @Test
    fun `field start remains disabled before a local recording exists`() {
        val state = GatewayPresentation(GatewayOperatingMode.FIELD, StreamLifecycle.CONNECTING, EventControlState())

        assertFalse(state.startEventEnabled)
    }

    @Test
    fun `field start is ready during RTSP connecting once local recording exists`() {
        val state = GatewayPresentation(GatewayOperatingMode.FIELD, StreamLifecycle.CONNECTING, EventControlState(), readyFieldRecording())

        assertTrue(state.startEventEnabled)
    }

    @Test
    fun `field start remains ready during RTSP degradation with a local recording`() {
        val state = GatewayPresentation(GatewayOperatingMode.FIELD, StreamLifecycle.DEGRADED, EventControlState(), readyFieldRecording())

        assertTrue(state.startEventEnabled)
    }

    @Test
    fun `active field event prevents a second local start`() {
        val state = GatewayPresentation(
            GatewayOperatingMode.FIELD,
            StreamLifecycle.DEGRADED,
            EventControlState("recording_bounded_event", "field-event"),
            FieldEventReadiness(true, true, true, "event already active"),
        )

        assertFalse(state.startEventEnabled)
        assertTrue(state.endEventEnabled)
    }

    private fun readyFieldRecording() = FieldEventReadiness(true, true, false, "READY")
}
