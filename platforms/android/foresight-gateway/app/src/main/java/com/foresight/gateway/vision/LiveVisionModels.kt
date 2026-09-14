package com.foresight.gateway.vision

/** Canonical source-frame-relative rectangle used only by the live presentation path. */
data class LiveNormalizedBoundingBox(
    val xMin: Float,
    val yMin: Float,
    val xMax: Float,
    val yMax: Float,
) {
    init {
        require(listOf(xMin, yMin, xMax, yMax).all { it in 0f..1f }) {
            "Live detection coordinates must be normalized between 0 and 1."
        }
        require(xMin <= xMax && yMin <= yMax) {
            "Live detection minimum coordinates cannot exceed maximum coordinates."
        }
    }
}

data class LiveDetectionPresentation(
    val label: String,
    val confidence: Float,
    val boundingBox: LiveNormalizedBoundingBox,
    val prompt: String? = null,
) {
    init {
        require(label.isNotBlank()) { "Live detection labels cannot be blank." }
        require(confidence in 0f..1f) { "Live detection confidence must be normalized." }
    }
}

/** Immutable result associated with one Activity-owned sampled preview frame. */
data class DetectionSnapshot(
    val runtimeGeneration: Long,
    val frameId: Long,
    val captureElapsedRealtimeNanos: Long,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val detections: List<LiveDetectionPresentation>,
    val detectorBackend: String? = null,
    val detectorModel: String? = null,
) {
    init {
        require(runtimeGeneration > 0) { "Live Vision runtime generation must be positive." }
        require(frameId > 0) { "Live Vision frame ID must be positive." }
        require(captureElapsedRealtimeNanos >= 0) { "Live Vision sample time must be monotonic." }
        require(sourceWidth > 0 && sourceHeight > 0) { "Live Vision source dimensions must be positive." }
    }
}

