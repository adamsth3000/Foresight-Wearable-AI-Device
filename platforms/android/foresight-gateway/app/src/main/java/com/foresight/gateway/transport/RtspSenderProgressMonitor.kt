package com.foresight.gateway.transport

/**
 * Detects encoded media accumulating behind a non-progressing RootEncoder sender.
 *
 * RootEncoder exposes aggregate queue and sender counters but no per-write callbacks. A rising
 * queue plus active camera input and frozen sender counters is therefore the earliest safe public
 * signal of a blocked sender coroutine.
 */
class RtspSenderProgressMonitor(
    private val stalledSamplesBeforeRebuild: Int = 2,
) {
    data class Snapshot(
        val elapsedRealtimeMillis: Long,
        val cameraFrames: Long,
        val queueItems: Int,
        val sentVideoFrames: Long,
        val sentAudioFrames: Long,
        val senderByteCounter: Long,
    )

    data class Result(
        val cameraFramesDelta: Long,
        val queueDelta: Int,
        val senderFramesDelta: Long,
        val senderBytesDelta: Long,
        val stalledSamples: Int,
        val shouldRebuild: Boolean,
        val lastSenderProgressElapsedMillis: Long?,
    )

    private var previous: Snapshot? = null
    private var stalledSamples = 0
    private var lastSenderProgressElapsedMillis: Long? = null

    fun observe(current: Snapshot): Result {
        val prior = previous
        previous = current
        if (prior == null) {
            lastSenderProgressElapsedMillis = current.elapsedRealtimeMillis
            return Result(0, 0, 0, 0, 0, false, lastSenderProgressElapsedMillis)
        }

        val cameraFramesDelta = current.cameraFrames - prior.cameraFrames
        val queueDelta = current.queueItems - prior.queueItems
        val senderFramesDelta =
            (current.sentVideoFrames - prior.sentVideoFrames) +
                (current.sentAudioFrames - prior.sentAudioFrames)
        val senderBytesDelta = current.senderByteCounter - prior.senderByteCounter
        val senderProgressed = senderFramesDelta > 0 || senderBytesDelta > 0
        if (senderProgressed) lastSenderProgressElapsedMillis = current.elapsedRealtimeMillis

        val senderStalled = cameraFramesDelta > 0 && queueDelta > 0 && current.queueItems > 0 && !senderProgressed
        stalledSamples = if (senderStalled) stalledSamples + 1 else 0
        return Result(
            cameraFramesDelta = cameraFramesDelta,
            queueDelta = queueDelta,
            senderFramesDelta = senderFramesDelta,
            senderBytesDelta = senderBytesDelta,
            stalledSamples = stalledSamples,
            shouldRebuild = stalledSamples >= stalledSamplesBeforeRebuild,
            lastSenderProgressElapsedMillis = lastSenderProgressElapsedMillis,
        )
    }

    fun reset() {
        previous = null
        stalledSamples = 0
        lastSenderProgressElapsedMillis = null
    }
}
