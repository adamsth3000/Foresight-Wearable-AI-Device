package com.foresight.gateway.gopro

/** Small JNI boundary; normal gateway code only receives typed diagnostic events. */
class NativeRtmpIngress(
    private val callbacks: GoProIngressCallbacks,
) : GoProIngressBackend {
    init {
        System.loadLibrary("foresight_gopro_ingress")
    }

    override fun run(host: String, port: Int, path: String) = nativeRun(host, port, path)

    override fun stop() = nativeStop()

    @Suppress("unused") // Invoked by gopro_rtmp_ingress.cpp.
    private fun onNativeEvent(
        eventCode: Int,
        detail: String,
        videoCodec: String,
        width: Int,
        height: Int,
        frameRate: Float,
        audioCodec: String,
        sampleRate: Int,
        channels: Int,
    ) {
        val event = NativeIngressEvent.entries.getOrNull(eventCode - 1) ?: NativeIngressEvent.ERROR
        val metadata = if (event == NativeIngressEvent.STREAM_METADATA) {
            GoProStreamMetadata(
                videoCodec = videoCodec.ifBlank { null },
                width = width,
                height = height,
                frameRate = frameRate,
                audioCodec = audioCodec.ifBlank { null },
                sampleRate = sampleRate,
                channels = channels,
            )
        } else {
            null
        }
        callbacks.eventListener(event, detail, metadata)
    }

    @Suppress("unused") // Invoked by gopro_rtmp_ingress.cpp at a bounded cadence.
    private fun onNativeMediaDiagnostics(
        generationId: Long,
        videoConfigReady: Boolean,
        videoExtradataBytes: Int,
        videoStreamIndex: Int,
        videoTimeBaseNumerator: Int,
        videoTimeBaseDenominator: Int,
        videoWidth: Int,
        videoHeight: Int,
        audioConfigReady: Boolean,
        audioExtradataBytes: Int,
        audioStreamIndex: Int,
        audioTimeBaseNumerator: Int,
        audioTimeBaseDenominator: Int,
        audioSampleRate: Int,
        audioChannelCount: Int,
        videoPacketCount: Long,
        audioPacketCount: Long,
        videoKeyframeCount: Long,
        lastVideoPtsUs: Long,
        lastVideoDtsUs: Long,
        lastAudioPtsUs: Long,
        lastAudioDtsUs: Long,
        lastVideoPacketBytes: Int,
        lastAudioPacketBytes: Int,
    ) {
        callbacks.mediaDiagnosticsListener(
            GoProMediaDiagnostics(
                generationId = generationId,
                videoConfigReady = videoConfigReady,
                videoExtradataBytes = videoExtradataBytes,
                videoStreamIndex = videoStreamIndex,
                videoTimeBaseNumerator = videoTimeBaseNumerator,
                videoTimeBaseDenominator = videoTimeBaseDenominator,
                videoWidth = videoWidth,
                videoHeight = videoHeight,
                audioConfigReady = audioConfigReady,
                audioExtradataBytes = audioExtradataBytes,
                audioStreamIndex = audioStreamIndex,
                audioTimeBaseNumerator = audioTimeBaseNumerator,
                audioTimeBaseDenominator = audioTimeBaseDenominator,
                audioSampleRate = audioSampleRate,
                audioChannelCount = audioChannelCount,
                videoPacketCount = videoPacketCount,
                audioPacketCount = audioPacketCount,
                videoKeyframeCount = videoKeyframeCount,
                lastVideoPtsUs = lastVideoPtsUs.takeUnless { it == TIMESTAMP_UNAVAILABLE },
                lastVideoDtsUs = lastVideoDtsUs.takeUnless { it == TIMESTAMP_UNAVAILABLE },
                lastAudioPtsUs = lastAudioPtsUs.takeUnless { it == TIMESTAMP_UNAVAILABLE },
                lastAudioDtsUs = lastAudioDtsUs.takeUnless { it == TIMESTAMP_UNAVAILABLE },
                lastVideoPacketBytes = lastVideoPacketBytes,
                lastAudioPacketBytes = lastAudioPacketBytes,
            ),
        )
    }

    @Suppress("unused") // Invoked by gopro_rtmp_ingress.cpp once per accepted publisher.
    private fun onNativeVideoFormat(
        generationId: Long,
        streamIndex: Int,
        width: Int,
        height: Int,
        timeBaseNumerator: Int,
        timeBaseDenominator: Int,
        extradata: ByteArray,
        representationCode: Int,
        nalLengthSize: Int,
        codecName: String,
    ) {
        callbacks.videoFormatListener(
            GoProH264Format(
                generationId = generationId,
                streamIndex = streamIndex,
                width = width,
                height = height,
                timeBaseNumerator = timeBaseNumerator,
                timeBaseDenominator = timeBaseDenominator,
                extradata = extradata.copyOf(),
                representation = GoProH264Representation.entries.getOrNull(representationCode) ?: GoProH264Representation.UNKNOWN,
                nalLengthSize = nalLengthSize.takeIf { it in 1..4 },
                codecName = codecName,
            ),
        )
    }

    @Suppress("unused") // Invoked by gopro_rtmp_ingress.cpp once per accepted publisher.
    private fun onNativeAudioFormat(
        generationId: Long,
        streamIndex: Int,
        sampleRate: Int,
        channelCount: Int,
        timeBaseNumerator: Int,
        timeBaseDenominator: Int,
        extradata: ByteArray,
        representationCode: Int,
        codecName: String,
    ) {
        callbacks.audioFormatListener(
            GoProAacFormat(
                generationId = generationId,
                streamIndex = streamIndex,
                sampleRate = sampleRate,
                channelCount = channelCount,
                timeBaseNumerator = timeBaseNumerator,
                timeBaseDenominator = timeBaseDenominator,
                extradata = extradata.copyOf(),
                representation = GoProAacRepresentation.entries.getOrNull(representationCode) ?: GoProAacRepresentation.UNKNOWN,
                codecName = codecName,
            ),
        )
    }

    @Suppress("unused") // Invoked synchronously by gopro_rtmp_ingress.cpp for copied video data.
    private fun onNativeVideoSample(
        generationId: Long,
        streamIndex: Int,
        data: ByteArray,
        presentationTimeUs: Long,
        decodingTimeUs: Long,
        keyFrame: Boolean,
        representationCode: Int,
    ) = callbacks.sampleListener(
        GoProEncodedSample(
            generationId = generationId,
            streamType = GoProStreamType.VIDEO,
            streamIndex = streamIndex,
            data = data.copyOf(),
            presentationTimeUs = presentationTimeUs.takeUnless { it == TIMESTAMP_UNAVAILABLE },
            decodingTimeUs = decodingTimeUs.takeUnless { it == TIMESTAMP_UNAVAILABLE },
            keyFrame = keyFrame,
            videoRepresentation = GoProH264Representation.entries.getOrNull(representationCode)
                ?: GoProH264Representation.UNKNOWN,
        ),
    )

    @Suppress("unused") // Invoked synchronously by gopro_rtmp_ingress.cpp for copied audio data.
    private fun onNativeAudioSample(
        generationId: Long,
        streamIndex: Int,
        data: ByteArray,
        presentationTimeUs: Long,
        decodingTimeUs: Long,
        representationCode: Int,
    ) = callbacks.sampleListener(
        GoProEncodedSample(
            generationId = generationId,
            streamType = GoProStreamType.AUDIO,
            streamIndex = streamIndex,
            data = data.copyOf(),
            presentationTimeUs = presentationTimeUs.takeUnless { it == TIMESTAMP_UNAVAILABLE },
            decodingTimeUs = decodingTimeUs.takeUnless { it == TIMESTAMP_UNAVAILABLE },
            keyFrame = false,
            audioRepresentation = GoProAacRepresentation.entries.getOrNull(representationCode)
                ?: GoProAacRepresentation.UNKNOWN,
        ),
    )

    private external fun nativeRun(host: String, port: Int, path: String)

    private external fun nativeStop()

    private companion object {
        const val TIMESTAMP_UNAVAILABLE = Long.MIN_VALUE
    }
}

interface GoProIngressBackend {
    fun run(host: String, port: Int, path: String)
    fun stop()
}
