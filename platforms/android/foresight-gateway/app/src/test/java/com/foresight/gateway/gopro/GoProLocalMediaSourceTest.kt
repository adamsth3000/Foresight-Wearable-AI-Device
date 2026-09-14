package com.foresight.gateway.gopro

import com.foresight.gateway.capture.LocalMediaAvailability
import com.foresight.gateway.capture.LocalMediaLocation
import com.foresight.gateway.capture.LocalMediaSourceId
import com.foresight.gateway.capture.LocalRecordingContext
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class GoProLocalMediaSourceTest {
    @Test
    fun `adapter delegates diagnostic recording and exposes GoPro context`() {
        val control = FakeControl()
        val source = GoProLocalMediaSource(control)

        val started = requireNotNull(source.startContinuousRecording())
        val stopped = requireNotNull(source.stopContinuousRecording())

        assertEquals(LocalMediaSourceId.GOPRO_RTMP, source.mediaSourceId)
        assertEquals(1, control.starts)
        assertEquals(1, control.stops)
        assertEquals(LocalMediaAvailability.AVAILABLE, source.availability())
        assertEquals("gopro-1", started.recordingId)
        assertEquals("gopro-1", stopped.recordingId)
    }

    private class FakeControl : GoProLocalRecordingControl {
        var starts = 0
        var stops = 0
        private val context = LocalRecordingContext(
            "gopro-1", "gopro-generation-1", 0, "gopro-gopro-1.mp4",
            Instant.parse("2026-09-02T12:00:00Z"), 1_000L, true,
            mediaSource = LocalMediaSourceId.GOPRO_RTMP,
            mediaLocation = LocalMediaLocation.goProRtmp("gopro-gopro-1.mp4"),
        )

        override fun startRecording(): GoProRecordingDiagnostics {
            starts += 1
            return GoProRecordingDiagnostics(state = GoProRecordingState.RECORDING)
        }

        override fun stopRecording(): GoProRecordingDiagnostics {
            stops += 1
            return GoProRecordingDiagnostics(state = GoProRecordingState.SAVED)
        }

        override fun currentRecordingContext(): LocalRecordingContext = context
    }
}
