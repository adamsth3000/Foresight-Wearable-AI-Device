package com.foresight.gateway.capture

import com.foresight.gateway.metadata.CaptureSessionMetadata
import com.foresight.gateway.metadata.ClockAnchor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class FieldSessionCoordinatorTest {
    @Test fun `GoPro session starts support before media becomes available and arms once live`() {
        val phone = FakeMedia(LocalMediaSourceId.PHONE_CAMERA)
        val goPro = FakeMedia(LocalMediaSourceId.GOPRO_RTMP)
        val sensors = FakeSupport()
        val telemetry = FakeSupport()
        val coordinator = FieldSessionCoordinator(phone, goPro, sensors, telemetry)

        assertTrue(coordinator.start(LocalMediaSourceId.GOPRO_RTMP, session()))
        assertTrue(coordinator.snapshot().active)
        assertEquals(LocalMediaSourceId.GOPRO_RTMP, coordinator.snapshot().source)
        assertEquals(1, sensors.starts)
        assertEquals(1, telemetry.starts)
        assertEquals(1, goPro.starts)
        coordinator.onGoProLive()
        coordinator.onGoProLive()
        assertEquals(1, goPro.liveCalls)
    }

    @Test fun `source is locked until end and diagnostic GoPro recording is rejected`() {
        val coordinator = FieldSessionCoordinator(FakeMedia(LocalMediaSourceId.PHONE_CAMERA), FakeMedia(LocalMediaSourceId.GOPRO_RTMP), FakeSupport(), FakeSupport())
        assertTrue(coordinator.start(LocalMediaSourceId.GOPRO_RTMP, session()))
        assertFalse(coordinator.start(LocalMediaSourceId.PHONE_CAMERA, session()))
        assertFalse(coordinator.allowsDiagnosticGoProRecording())
        coordinator.end()
        assertFalse(coordinator.snapshot().active)
        assertTrue(coordinator.allowsDiagnosticGoProRecording())
    }

    private class FakeMedia(override val source: LocalMediaSourceId) : FieldMediaController {
        var starts = 0; var stops = 0; var liveCalls = 0
        override fun start(session: CaptureSessionMetadata) { starts++ }
        override fun stop() { stops++ }
        override fun onSourceLive() { if (liveCalls == 0) liveCalls++ }
        override fun availability() = LocalMediaAvailability.UNAVAILABLE
    }
    private class FakeSupport : FieldSupportController {
        var starts = 0; var stops = 0
        override fun start(session: CaptureSessionMetadata) { starts++ }
        override fun stop() { stops++ }
    }

    private fun session() = CaptureSessionMetadata(
        streamEndpoint = "field-test",
        clockAnchor = ClockAnchor(Instant.EPOCH, 1L),
    )
}
