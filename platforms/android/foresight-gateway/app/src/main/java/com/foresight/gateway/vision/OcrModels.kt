package com.foresight.gateway.vision

/** Local text-recognition result scoped to one sampled preview frame. */
data class OcrObservation(
    val runtimeGeneration: Long,
    val sourceFrameId: Long,
    val timestampNanos: Long,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val regions: List<OcrRegion>,
) {
    init {
        require(runtimeGeneration > 0 && sourceFrameId > 0)
        require(timestampNanos >= 0 && sourceWidth > 0 && sourceHeight > 0)
    }
}

data class OcrRegion(
    val text: String,
    val confidence: Float? = null,
    val normalizedBoundingBox: LiveNormalizedBoundingBox,
    val lineIndex: Int? = null,
    val blockIndex: Int? = null,
) {
    init {
        require(text.isNotBlank())
        require(confidence == null || confidence in 0f..1f)
    }
}

/** Latest-only OCR state. OCR remains independently fresh and frame-scoped. */
class LatestOcrState(private val elapsedRealtimeNanos: () -> Long) {
    @Volatile private var latest: OcrObservation? = null

    fun publish(observation: OcrObservation) { latest = observation }
    fun clear() { latest = null }
    fun latestObservation(): OcrObservation? = latest

    fun freshObservation(maxAgeNanos: Long, runtimeGeneration: Long? = null): OcrObservation? {
        require(maxAgeNanos >= 0)
        val observation = latest ?: return null
        return observation.takeIf {
            elapsedRealtimeNanos() - it.timestampNanos <= maxAgeNanos &&
                (runtimeGeneration == null || it.runtimeGeneration == runtimeGeneration)
        }
    }
}
