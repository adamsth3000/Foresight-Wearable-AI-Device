package com.foresight.gateway.gopro

enum class GoProSourceStatus {
    STOPPED,
    LISTENING,
    PUBLISHER_CONNECTED,
    LIVE,
    LOST,
    ERROR,
}

data class GoProStreamMetadata(
    val videoCodec: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val frameRate: Float = 0f,
    val audioCodec: String? = null,
    val sampleRate: Int = 0,
    val channels: Int = 0,
) {
    fun videoSummary(): String = videoCodec?.let {
        "$it ${width}x$height" + if (frameRate > 0f) " %.1ffps".format(frameRate) else ""
    } ?: "None"

    fun audioSummary(): String = audioCodec?.let {
        "$it ${sampleRate}Hz" + if (channels > 0) " ${if (channels == 2) "stereo" else "$channels ch"}" else ""
    } ?: "None"
}

data class GoProIngressSnapshot(
    val status: GoProSourceStatus = GoProSourceStatus.STOPPED,
    val destination: String? = null,
    val metadata: GoProStreamMetadata? = null,
    val detail: String? = null,
)

enum class NativeIngressEvent {
    LISTENING,
    PUBLISHER_CONNECTED,
    STREAM_METADATA,
    PUBLISHER_DISCONNECTED,
    ERROR,
}
