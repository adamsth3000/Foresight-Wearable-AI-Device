package com.foresight.gateway.gopro

enum class GoProSourceStatus {
    STOPPED,
    LISTENING,
    PUBLISHER_CONNECTED,
    LIVE,
    LOST,
    ERROR,
}

enum class GoProStreamType {
    VIDEO,
    AUDIO,
}

enum class GoProH264Representation {
    AVCC,
    ANNEX_B,
    UNKNOWN,
}

enum class GoProAacRepresentation {
    RAW_AAC,
    ADTS,
    UNKNOWN,
}

data class GoProEncodedSample(
    val generationId: Long,
    val streamType: GoProStreamType,
    val streamIndex: Int,
    val data: ByteArray,
    val presentationTimeUs: Long?,
    val decodingTimeUs: Long?,
    val keyFrame: Boolean,
    val videoRepresentation: GoProH264Representation? = null,
    val audioRepresentation: GoProAacRepresentation? = null,
)

data class GoProH264Format(
    val generationId: Long,
    val streamIndex: Int,
    val width: Int,
    val height: Int,
    val timeBaseNumerator: Int,
    val timeBaseDenominator: Int,
    val extradata: ByteArray,
    val representation: GoProH264Representation,
    val nalLengthSize: Int?,
    val codecName: String,
)

data class GoProAacFormat(
    val generationId: Long,
    val streamIndex: Int,
    val sampleRate: Int,
    val channelCount: Int,
    val timeBaseNumerator: Int,
    val timeBaseDenominator: Int,
    val extradata: ByteArray,
    val representation: GoProAacRepresentation,
    val codecName: String,
)

data class GoProEncodedTransportDiagnostics(
    val generationId: Long? = null,
    val queueCapacity: Int,
    val queueDepth: Int = 0,
    val peakQueueDepth: Int = 0,
    val videoSamplesReceived: Long = 0,
    val audioSamplesReceived: Long = 0,
    val videoBytesReceived: Long = 0,
    val audioBytesReceived: Long = 0,
    val samplesDropped: Long = 0,
    val videoSamplesDropped: Long = 0,
    val audioSamplesDropped: Long = 0,
    val lastVideoPayloadBytes: Int = 0,
    val lastAudioPayloadBytes: Int = 0,
    val videoRepresentation: GoProH264Representation = GoProH264Representation.UNKNOWN,
    val audioRepresentation: GoProAacRepresentation = GoProAacRepresentation.UNKNOWN,
)

/** Preview state is deliberately independent from RTMP source state. */
enum class GoProPreviewState {
    DETACHED,
    WAITING_FOR_STREAM,
    WAITING_FOR_CONFIG,
    WAITING_FOR_KEYFRAME,
    CONFIGURING,
    DECODING,
    ERROR,
}

enum class GoProRecordingState {
    STOPPED,
    ARMING,
    WAITING_FOR_KEYFRAME,
    RECORDING,
    FINALIZING,
    SAVED,
    INTERRUPTED,
    ERROR,
}

data class GoProRecordingDiagnostics(
    val state: GoProRecordingState = GoProRecordingState.STOPPED,
    val recordingId: String? = null,
    val generationId: Long? = null,
    val outputFileName: String? = null,
    val metadataFileName: String? = null,
    val durationUs: Long = 0,
    val videoSamplesWritten: Long = 0,
    val audioSamplesWritten: Long = 0,
    val queueDepth: Int = 0,
    val queueCapacity: Int = 0,
    val peakQueueDepth: Int = 0,
    val fileSizeBytes: Long = 0,
    val sha256: String? = null,
    val terminationReason: String? = null,
    val detail: String? = null,
)

/** Low-rate diagnostics for the optional, video-only hardware preview. */
data class GoProPreviewDiagnostics(
    val state: GoProPreviewState = GoProPreviewState.DETACHED,
    val generationId: Long? = null,
    val decoderName: String? = null,
    val framesQueued: Long = 0,
    val framesRendered: Long = 0,
    val framesDropped: Long = 0,
    val queueDepth: Int = 0,
    val queueCapacity: Int = 0,
    val detail: String? = null,
)

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

/** Low-rate, payload-free observability emitted by the native RTMP reader. */
data class GoProMediaDiagnostics(
    val generationId: Long,
    val videoConfigReady: Boolean,
    val videoExtradataBytes: Int,
    val videoStreamIndex: Int,
    val videoTimeBaseNumerator: Int,
    val videoTimeBaseDenominator: Int,
    val videoWidth: Int,
    val videoHeight: Int,
    val audioConfigReady: Boolean,
    val audioExtradataBytes: Int,
    val audioStreamIndex: Int,
    val audioTimeBaseNumerator: Int,
    val audioTimeBaseDenominator: Int,
    val audioSampleRate: Int,
    val audioChannelCount: Int,
    val videoPacketCount: Long,
    val audioPacketCount: Long,
    val videoKeyframeCount: Long,
    val lastVideoPtsUs: Long?,
    val lastVideoDtsUs: Long?,
    val lastAudioPtsUs: Long?,
    val lastAudioDtsUs: Long?,
    val lastVideoPacketBytes: Int,
    val lastAudioPacketBytes: Int,
)

data class GoProIngressSnapshot(
    val status: GoProSourceStatus = GoProSourceStatus.STOPPED,
    val destination: String? = null,
    val metadata: GoProStreamMetadata? = null,
    val mediaDiagnostics: GoProMediaDiagnostics? = null,
    val encodedTransportDiagnostics: GoProEncodedTransportDiagnostics? = null,
    val previewDiagnostics: GoProPreviewDiagnostics? = null,
    val recordingDiagnostics: GoProRecordingDiagnostics? = null,
    val detail: String? = null,
)

enum class NativeIngressEvent {
    LISTENING,
    PUBLISHER_CONNECTED,
    STREAM_METADATA,
    PUBLISHER_DISCONNECTED,
    ERROR,
}

data class GoProIngressCallbacks(
    val eventListener: (NativeIngressEvent, String, GoProStreamMetadata?) -> Unit,
    val mediaDiagnosticsListener: (GoProMediaDiagnostics) -> Unit,
    val videoFormatListener: (GoProH264Format) -> Unit,
    val audioFormatListener: (GoProAacFormat) -> Unit,
    val sampleListener: (GoProEncodedSample) -> Unit,
)
