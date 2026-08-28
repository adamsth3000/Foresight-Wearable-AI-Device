package com.foresight.gateway.capture

import android.content.Context
import com.foresight.gateway.metadata.CaptureSessionMetadata
import com.foresight.gateway.sensors.PhoneSensorCapture
import com.foresight.gateway.telemetry.TelemetryClient
import com.foresight.gateway.transport.RtspPublisher
import com.foresight.gateway.transport.StreamLifecycle
import org.json.JSONObject
import java.time.Instant

/** Coordinates a source-neutral phone capture session without owning Android UI. */
class PhoneCaptureController(
    context: Context,
    private val listener: Listener,
) : RtspPublisher.Listener, TelemetryClient.Listener {
    interface Listener {
        fun onCaptureStateChanged(
            lifecycle: StreamLifecycle,
            metadata: CaptureSessionMetadata?,
            detail: String? = null,
        )
    }

    private val telemetry = TelemetryClient(this)
    private val sensors = PhoneSensorCapture(context, telemetry) { detail ->
        listener.onCaptureStateChanged(transportLifecycle, activeSession, detail)
    }
    private val publisher = RtspPublisher(context, this)
    @Volatile
    private var activeSession: CaptureSessionMetadata? = null
    @Volatile
    private var transportLifecycle = StreamLifecycle.IDLE

    fun start(endpoint: String, telemetryEndpoint: String) {
        require(endpoint.startsWith("rtsp://")) { "The endpoint must use rtsp://" }
        check(activeSession == null) { "Capture is already active." }

        activeSession = CaptureSessionMetadata(streamEndpoint = endpoint)
        listener.onCaptureStateChanged(StreamLifecycle.PREPARING, activeSession)
        telemetry.start(requireNotNull(activeSession), telemetryEndpoint)
        sensors.start()
        if (!publisher.start(endpoint)) {
            sensors.stop()
            telemetry.stop()
            activeSession = null
        }
    }

    fun stop() {
        sensors.stop()
        telemetry.stop()
        publisher.stop()
    }

    override fun onLifecycleChanged(lifecycle: StreamLifecycle, detail: String?) {
        transportLifecycle = lifecycle
        if (lifecycle == StreamLifecycle.STREAMING) {
            activeSession = activeSession?.copy(streamStartedUtc = Instant.now())
        }
        listener.onCaptureStateChanged(lifecycle, activeSession, detail)
        if (lifecycle == StreamLifecycle.IDLE || lifecycle == StreamLifecycle.ERROR) {
            activeSession = null
        }
    }

    override fun onBitrateChanged(bitsPerSecond: Long) {
        if (transportLifecycle != StreamLifecycle.STREAMING) return
        listener.onCaptureStateChanged(
            StreamLifecycle.STREAMING,
            activeSession,
            "${bitsPerSecond / 1_000} kbps",
        )
    }

    override fun onTelemetryBound(captureSessionId: String) {
        activeSession = activeSession?.copy(captureSessionId = captureSessionId)
        listener.onCaptureStateChanged(
            transportLifecycle,
            activeSession,
            "Telemetry bound to capture session $captureSessionId",
        )
    }

    override fun onTelemetryStatus(detail: String) {
        listener.onCaptureStateChanged(transportLifecycle, activeSession, detail)
    }

    override fun onCameraTimingCapability(timestampSource: String) {
        telemetry.enqueue(JSONObject().apply {
            put("record_type", "camera_timing_capability")
            put("timestamp_elapsed_realtime_nanos", android.os.SystemClock.elapsedRealtimeNanos())
            put("sensor_timestamp_source", timestampSource)
            put("encoded_pts_mapping", "not_available_from_rootencoder")
        })
    }

    override fun onCameraFrameCaptured(frameNumber: Long, timestampNanos: Long) {
        // RootEncoder exposes Camera2 exposure timestamps, but not encoded RTP/PTS mapping.
        // Rate-limit timing observations so camera callbacks cannot pressure telemetry transport.
        if (frameNumber % 30L != 0L) return
        telemetry.enqueue(JSONObject().apply {
            put("record_type", "camera_frame_timing")
            put("timestamp_elapsed_realtime_nanos", timestampNanos)
            put("frame_number", frameNumber)
            put("timestamp_semantics", "camera_capture_start_exposure")
        })
    }
}
