package com.foresight.gateway.vision

/** Maps source-normalized detections through explicit source and destination dimensions. */
internal data class PreviewCoordinateTransform(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val destinationWidth: Int,
    val destinationHeight: Int,
) {
    init {
        require(sourceWidth > 0 && sourceHeight > 0) { "Source dimensions must be positive." }
        require(destinationWidth > 0 && destinationHeight > 0) { "Destination dimensions must be positive." }
    }

    fun map(box: LiveNormalizedBoundingBox): PresentationRectangle {
        val sourceLeft = box.xMin * sourceWidth
        val sourceTop = box.yMin * sourceHeight
        val sourceRight = box.xMax * sourceWidth
        val sourceBottom = box.yMax * sourceHeight
        return PresentationRectangle(
            sourceLeft * destinationWidth / sourceWidth,
            sourceTop * destinationHeight / sourceHeight,
            sourceRight * destinationWidth / sourceWidth,
            sourceBottom * destinationHeight / sourceHeight,
        )
    }
}

internal data class PresentationRectangle(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)
