package com.foresight.gateway.vision

internal data class MediaPipeDetectionCandidate(
    val label: String?,
    val confidence: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/** Converts model-pixel rectangles to the existing source-relative presentation contract. */
internal object MediaPipeDetectionMapper {
    fun map(
        candidates: List<MediaPipeDetectionCandidate>,
        sourceWidth: Int,
        sourceHeight: Int,
    ): List<LiveDetectionPresentation> {
        if (sourceWidth <= 0 || sourceHeight <= 0) return emptyList()
        return candidates.mapNotNull { candidate ->
            val label = candidate.label?.trim().orEmpty()
            if (label.isBlank() || !candidate.confidence.isFinite()) return@mapNotNull null

            val xMin = (candidate.left / sourceWidth).coerceIn(0f, 1f)
            val yMin = (candidate.top / sourceHeight).coerceIn(0f, 1f)
            val xMax = (candidate.right / sourceWidth).coerceIn(0f, 1f)
            val yMax = (candidate.bottom / sourceHeight).coerceIn(0f, 1f)
            if (xMax <= xMin || yMax <= yMin) return@mapNotNull null

            LiveDetectionPresentation(
                label = label,
                confidence = candidate.confidence.coerceIn(0f, 1f),
                boundingBox = LiveNormalizedBoundingBox(xMin, yMin, xMax, yMax),
            )
        }
    }
}
