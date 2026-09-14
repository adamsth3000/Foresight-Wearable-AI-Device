package com.foresight.gateway.capture

import com.foresight.gateway.gopro.GoProSourceStatus
import com.foresight.gateway.metadata.CaptureSessionMetadata
import com.foresight.gateway.mode.GatewayOperatingMode
import com.foresight.gateway.transport.StreamLifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldGoProCaptureStateAuthorityTest {
    @Test
    fun `stale phone idle is rejected while a GoPro FIELD session is active`() {
        val activeSession = session()
        val authoritativeStatus = CaptureStatus(
            StreamLifecycle.STREAMING,
            activeSession,
            "FIELD GoPro capture ready for events",
        )

        assertFalse(
            FieldGoProCaptureStateAuthority.acceptsControllerUpdate(
                GatewayOperatingMode.FIELD,
                LocalMediaSourceId.GOPRO_RTMP,
                activeSession,
            ),
        )
        assertEquals(
            authoritativeStatus,
            FieldGoProCaptureStateAuthority.statusForControllerUpdate(
                authoritativeStatus,
                GatewayOperatingMode.FIELD,
                LocalMediaSourceId.GOPRO_RTMP,
                activeSession,
                StreamLifecycle.IDLE,
                null,
                "Telemetry waiting for laptop",
            ),
        )
    }

    @Test
    fun `rejecting a stale controller update retains the active GoPro session`() {
        val activeSession = session()

        assertFalse(
            FieldGoProCaptureStateAuthority.acceptsControllerUpdate(
                GatewayOperatingMode.FIELD,
                LocalMediaSourceId.GOPRO_RTMP,
                activeSession,
            ),
        )
        val retainedStatus = FieldGoProCaptureStateAuthority.statusForControllerUpdate(
            CaptureStatus(StreamLifecycle.STREAMING, activeSession, "active"),
            GatewayOperatingMode.FIELD,
            LocalMediaSourceId.GOPRO_RTMP,
            activeSession,
            StreamLifecycle.IDLE,
            null,
            "stale phone callback",
        )

        assertEquals(activeSession, retainedStatus.metadata)
    }

    @Test
    fun `GoPro LIVE is authoritative streaming`() {
        val status = FieldGoProCaptureStateAuthority.statusForIngress(
            GoProSourceStatus.LIVE,
            session(),
            eventActive = false,
        )

        assertEquals(StreamLifecycle.STREAMING, status?.lifecycle)
    }

    @Test
    fun `GoPro LOST and ERROR are authoritative offline`() {
        listOf(GoProSourceStatus.LOST, GoProSourceStatus.ERROR).forEach { ingressStatus ->
            val status = FieldGoProCaptureStateAuthority.statusForIngress(
                ingressStatus,
                session(),
                eventActive = false,
            )

            assertEquals(StreamLifecycle.OFFLINE, status?.lifecycle)
        }
    }

    @Test
    fun `explicit GoPro FIELD stop returns idle without a session`() {
        val status = FieldGoProCaptureStateAuthority.stoppedStatus()

        assertEquals(StreamLifecycle.IDLE, status.lifecycle)
        assertNull(status.metadata)
    }

    @Test
    fun `phone FIELD and LAB controller updates remain authoritative`() {
        assertTrue(
            FieldGoProCaptureStateAuthority.acceptsControllerUpdate(
                GatewayOperatingMode.FIELD,
                LocalMediaSourceId.PHONE_CAMERA,
                session(),
            ),
        )
        assertTrue(
            FieldGoProCaptureStateAuthority.acceptsControllerUpdate(
                GatewayOperatingMode.LAB,
                null,
                null,
            ),
        )
        assertEquals(
            StreamLifecycle.IDLE,
            FieldGoProCaptureStateAuthority.statusForControllerUpdate(
                CaptureStatus(StreamLifecycle.STREAMING, session(), "active"),
                GatewayOperatingMode.FIELD,
                LocalMediaSourceId.PHONE_CAMERA,
                session(),
                StreamLifecycle.IDLE,
                null,
                "phone stopped",
            ).lifecycle,
        )
        assertEquals(
            StreamLifecycle.ERROR,
            FieldGoProCaptureStateAuthority.statusForControllerUpdate(
                CaptureStatus(StreamLifecycle.STREAMING, session(), "active"),
                GatewayOperatingMode.LAB,
                null,
                null,
                StreamLifecycle.ERROR,
                null,
                "lab error",
            ).lifecycle,
        )
    }

    private fun session() = CaptureSessionMetadata(streamEndpoint = "rtmp://local/gopro")
}
