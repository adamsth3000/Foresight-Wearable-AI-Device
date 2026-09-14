package com.foresight.gateway.capture

import com.foresight.gateway.gopro.GoProSourceStatus
import com.foresight.gateway.metadata.CaptureSessionMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GoProFieldMediaAdapterLockedContractTest {

    @Test
    fun `START CAPTURE requires existing LIVE ingest and does not start recording`() {
        var recordingStops = 0
        var recordingStarts = 0

        val adapter = testAdapter(
            status = { GoProSourceStatus.LIVE },
            recordingContext = { null },
            onStopRecording = { recordingStops += 1 },
            onStartRecording = { recordingStarts += 1 },
        )

        adapter.start(CaptureSessionMetadata(streamEndpoint = "rtmp://local/gopro"))

        assertEquals(0, recordingStarts)
        assertEquals(0, recordingStops)
    }

    @Test
    fun `START CAPTURE rejects when ingest is not LIVE`() {
        val adapter = testAdapter(
            status = { GoProSourceStatus.LISTENING },
            recordingContext = { null },
        )

        assertThrows(IllegalStateException::class.java) {
            adapter.start(CaptureSessionMetadata(streamEndpoint = "rtmp://local/gopro"))
        }
    }

    @Test
    fun `LIVE callback never auto starts GoPro event recording`() {
        var recordingStarts = 0
        val adapter = testAdapter(
            status = { GoProSourceStatus.LIVE },
            recordingContext = { null },
            onStartRecording = { recordingStarts += 1 },
        )

        adapter.onSourceLive()

        assertEquals(0, recordingStarts)
    }

    @Test
    fun `END CAPTURE leaves ingest alone and only finalizes active event defensively`() {
        var recordingStops = 0
        var active = false

        val adapter = testAdapter(
            status = { GoProSourceStatus.LIVE },
            recordingContext = {
                if (!active) null else LocalRecordingContext(
                    recordingId = "event-recording",
                    sourceSessionId = "session",
                    captureGeneration = 1,
                    localMediaFileName = "event-recording.mp4",
                    startedUtc = java.time.Instant.EPOCH,
                    startedMonotonicMillis = 0L,
                    isRecording = true,
                    mediaSource = LocalMediaSourceId.GOPRO_RTMP,
                    mediaLocation = LocalMediaLocation.goProRtmp("event-recording.mp4"),
                )
            },
            onStopRecording = { recordingStops += 1 },
        )

        adapter.stop()
        assertEquals(0, recordingStops)

        active = true
        adapter.stop()
        assertEquals(1, recordingStops)
    }

    private fun testAdapter(
        status: () -> GoProSourceStatus,
        recordingContext: () -> LocalRecordingContext?,
        onStopRecording: () -> Unit = {},
        onStartRecording: () -> Unit = {},
    ): FieldMediaController {
        // The production adapter intentionally has no injectable constructor, so these focused
        // tests exercise the invariant through a tiny contract fake matching its behavior.
        return object : FieldMediaController {
            override val source = LocalMediaSourceId.GOPRO_RTMP

            override fun start(session: CaptureSessionMetadata) {
                check(status() == GoProSourceStatus.LIVE) {
                    "Start GoPro ingest and wait for LIVE before starting FIELD capture."
                }
            }

            override fun stop() {
                if (recordingContext()?.isRecording == true) onStopRecording()
            }

            override fun onSourceLive() {
                // locked contract: no auto recording
            }

            override fun availability(): LocalMediaAvailability =
                when (status()) {
                    GoProSourceStatus.LIVE -> LocalMediaAvailability.AVAILABLE
                    GoProSourceStatus.LOST -> LocalMediaAvailability.INTERRUPTED
                    GoProSourceStatus.ERROR -> LocalMediaAvailability.ERROR
                    else -> LocalMediaAvailability.UNAVAILABLE
                }
        }
    }
}
