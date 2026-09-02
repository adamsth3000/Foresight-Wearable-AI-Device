package com.foresight.gateway.gopro

/** Small JNI boundary; normal gateway code only receives typed diagnostic events. */
class NativeRtmpIngress(
    private val eventListener: (NativeIngressEvent, String, GoProStreamMetadata?) -> Unit,
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
        eventListener(event, detail, metadata)
    }

    private external fun nativeRun(host: String, port: Int, path: String)

    private external fun nativeStop()
}

interface GoProIngressBackend {
    fun run(host: String, port: Int, path: String)
    fun stop()
}
