package com.foresight.gateway.transport

/**
 * Detects a RootEncoder sender that is no longer draining its bounded media queue.
 *
 * The RootEncoder Java socket path has no write timeout, so this is a fallback for a sender
 * blocked before it can surface an I/O exception through ConnectChecker.
 */
class RtspTransportHealthMonitor(
    private val saturationSamplesBeforeReconnect: Int = 2,
) {
    data class Snapshot(
        val queueItems: Int,
        val queueCapacity: Int,
        val sentVideoFrames: Long,
        val sentAudioFrames: Long,
        val droppedVideoFrames: Long,
        val droppedAudioFrames: Long,
        val bytesSent: Long,
    )

    data class Result(
        val shouldReconnect: Boolean,
        val reason: String? = null,
    )

    private var previousDroppedFrames = 0L
    private var saturatedSamples = 0

    fun observe(snapshot: Snapshot): Result {
        val droppedFrames = snapshot.droppedVideoFrames + snapshot.droppedAudioFrames
        val droppedFramesIncreased = droppedFrames > previousDroppedFrames
        previousDroppedFrames = droppedFrames

        val saturated = snapshot.queueCapacity > 0 &&
            snapshot.queueItems * 4 >= snapshot.queueCapacity * 3
        saturatedSamples = if (saturated) saturatedSamples + 1 else 0

        return when {
            droppedFramesIncreased -> Result(
                shouldReconnect = true,
                reason = "RTSP sender dropped frames; queue=${snapshot.queueItems}/${snapshot.queueCapacity}, " +
                    "dropped=$droppedFrames",
            )
            saturatedSamples >= saturationSamplesBeforeReconnect -> Result(
                shouldReconnect = true,
                reason = "RTSP sender queue remained saturated for $saturatedSamples probes; " +
                    "queue=${snapshot.queueItems}/${snapshot.queueCapacity}",
            )
            else -> Result(shouldReconnect = false)
        }
    }

    fun reset() {
        previousDroppedFrames = 0
        saturatedSamples = 0
    }
}
