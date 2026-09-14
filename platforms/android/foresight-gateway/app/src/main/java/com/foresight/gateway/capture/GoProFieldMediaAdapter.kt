package com.foresight.gateway.capture

import com.foresight.gateway.gopro.GoProRtmpIngress
import com.foresight.gateway.gopro.GoProSourceStatus
import com.foresight.gateway.metadata.CaptureSessionMetadata

/**
 * GOPRO_RTMP FIELD media adapter under the locked Gateway control contract.
 *
 * START GOPRO INGEST owns the RTMP listener.
 * START CAPTURE owns only the FIELD session/support/source lock.
 * START/END EVENT own GoPro MP4 recording.
 * END CAPTURE does not stop ingest.
 */
internal class GoProFieldMediaAdapter(
    private val ingress: GoProRtmpIngress,
) : FieldMediaController {

    override val source: LocalMediaSourceId = LocalMediaSourceId.GOPRO_RTMP

    override fun start(session: CaptureSessionMetadata) {
        check(ingress.snapshot().status == GoProSourceStatus.LIVE) {
            "Start GoPro ingest and wait for LIVE before starting FIELD capture."
        }
    }

    override fun stop() {
        // FIELD capture does not own the RTMP listener.
        // If an event recording is still active, finalize it defensively.
        if (ingress.currentRecordingContext()?.isRecording == true) {
            ingress.stopRecording()
        }
    }

    override fun onSourceLive() {
        // Locked contract: LIVE must not automatically start recording.
        // START EVENT owns GoPro MP4 recording.
    }

    override fun availability(): LocalMediaAvailability =
        when (ingress.snapshot().status) {
            GoProSourceStatus.LIVE -> LocalMediaAvailability.AVAILABLE
            GoProSourceStatus.LOST -> LocalMediaAvailability.INTERRUPTED
            GoProSourceStatus.ERROR -> LocalMediaAvailability.ERROR
            else -> LocalMediaAvailability.UNAVAILABLE
        }
}
