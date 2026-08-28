package com.foresight.gateway.capture

import android.content.Context
import com.foresight.gateway.metadata.CaptureSessionMetadata
import com.foresight.gateway.transport.RtspPublisher
import com.foresight.gateway.transport.StreamLifecycle
import java.time.Instant

/** Coordinates a source-neutral phone capture session without owning Android UI. */
class PhoneCaptureController(
    context: Context,
    private val listener: Listener,
) : RtspPublisher.Listener {
    interface Listener {
        fun onCaptureStateChanged(
            lifecycle: StreamLifecycle,
            metadata: CaptureSessionMetadata?,
            detail: String? = null,
        )
    }

    private val publisher = RtspPublisher(context, this)
    private var activeSession: CaptureSessionMetadata? = null

    fun start(endpoint: String) {
        require(endpoint.startsWith("rtsp://")) { "The endpoint must use rtsp://" }
        check(activeSession == null) { "Capture is already active." }

        activeSession = CaptureSessionMetadata(streamEndpoint = endpoint)
        listener.onCaptureStateChanged(StreamLifecycle.PREPARING, activeSession)
        if (!publisher.start(endpoint)) {
            activeSession = null
        }
    }

    fun stop() {
        publisher.stop()
    }

    override fun onLifecycleChanged(lifecycle: StreamLifecycle, detail: String?) {
        if (lifecycle == StreamLifecycle.STREAMING) {
            activeSession = activeSession?.copy(streamStartedUtc = Instant.now())
        }
        listener.onCaptureStateChanged(lifecycle, activeSession, detail)
        if (lifecycle == StreamLifecycle.IDLE || lifecycle == StreamLifecycle.ERROR) {
            activeSession = null
        }
    }

    override fun onBitrateChanged(bitsPerSecond: Long) {
        listener.onCaptureStateChanged(
            StreamLifecycle.STREAMING,
            activeSession,
            "${bitsPerSecond / 1_000} kbps",
        )
    }
}
