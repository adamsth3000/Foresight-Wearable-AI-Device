package com.foresight.gateway.gopro

import com.foresight.gateway.capture.LocalMediaAvailability
import com.foresight.gateway.capture.LocalMediaSource
import com.foresight.gateway.capture.LocalMediaSourceId
import com.foresight.gateway.capture.LocalRecordingContext

/** C1 adapter around the existing diagnostic GoPro recorder; FIELD does not select it yet. */
internal interface GoProLocalRecordingControl {
    fun startRecording(): GoProRecordingDiagnostics
    fun stopRecording(): GoProRecordingDiagnostics
    fun currentRecordingContext(): LocalRecordingContext?
}

internal class GoProLocalMediaSource(
    private val ingress: GoProLocalRecordingControl,
) : LocalMediaSource {
    override val mediaSourceId = LocalMediaSourceId.GOPRO_RTMP

    override fun availability(): LocalMediaAvailability = ingress.currentRecordingContext()?.availability
        ?: LocalMediaAvailability.UNAVAILABLE

    override fun currentRecordingContext(): LocalRecordingContext? = ingress.currentRecordingContext()

    override fun startContinuousRecording(): LocalRecordingContext? {
        ingress.startRecording()
        return ingress.currentRecordingContext()
    }

    override fun stopContinuousRecording(): LocalRecordingContext? {
        ingress.stopRecording()
        return ingress.currentRecordingContext()
    }
}
