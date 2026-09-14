package com.foresight.gateway.vision.gesture

import com.foresight.gateway.vision.LiveNormalizedBoundingBox

/** Source-neutral normalized landmark compatible with standard 21-point hand models. */
data class NormalizedHandLandmark(
    val x: Float,
    val y: Float,
    val z: Float = 0f,
) {
    init {
        require(x.isFinite() && y.isFinite() && z.isFinite()) { "Hand landmark coordinates must be finite." }
        require(x in 0f..1f && y in 0f..1f) { "Hand landmark image coordinates must be normalized." }
    }
}

enum class Handedness {
    LEFT,
    RIGHT,
    UNKNOWN,
}

/** Landmark indices shared by MediaPipe-style 21-point hand models without importing their types. */
object HandLandmarkIndex {
    const val WRIST = 0
    const val INDEX_MCP = 5
    const val INDEX_PIP = 6
    const val INDEX_DIP = 7
    const val INDEX_TIP = 8
    const val COUNT = 21
}

/** Immutable hand result associated with one sampled preview frame. */
data class HandObservation(
    val runtimeGeneration: Long,
    val sourceFrameId: Long,
    val timestampNanos: Long,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val handedness: Handedness,
    val handednessConfidence: Float,
    val landmarks: List<NormalizedHandLandmark>,
    val confidence: Float,
) {
    init {
        require(runtimeGeneration > 0) { "Hand runtime generation must be positive." }
        require(sourceFrameId > 0) { "Hand source frame ID must be positive." }
        require(timestampNanos >= 0) { "Hand timestamp must be monotonic." }
        require(sourceWidth > 0 && sourceHeight > 0) { "Hand source dimensions must be positive." }
        require(handednessConfidence in 0f..1f && confidence in 0f..1f) {
            "Hand confidences must be normalized."
        }
    }
}

data class NormalizedImagePoint(val x: Float, val y: Float) {
    init {
        require(x.isFinite() && y.isFinite()) { "Image point coordinates must be finite." }
    }
}

data class NormalizedImageVector(val x: Float, val y: Float) {
    init {
        require(x.isFinite() && y.isFinite()) { "Image vector coordinates must be finite." }
    }
}

/** An image-space pointing ray. It intentionally carries no world-space claim. */
data class PointingObservation(
    val runtimeGeneration: Long,
    val sourceFrameId: Long,
    val timestampNanos: Long,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val origin: NormalizedImagePoint,
    val direction: NormalizedImageVector,
    val confidence: Float,
) {
    init {
        require(runtimeGeneration > 0 && sourceFrameId > 0) { "Pointing frame identity must be positive." }
        require(timestampNanos >= 0) { "Pointing timestamp must be monotonic." }
        require(sourceWidth > 0 && sourceHeight > 0) { "Pointing source dimensions must be positive." }
        require(confidence in 0f..1f) { "Pointing confidence must be normalized." }
    }
}

/** A deliberately frame-scoped identity until persistent detection tracks are introduced. */
data class GestureTargetIdentity(
    val label: String,
    val snapshotFrameId: Long,
    val boundingBox: LiveNormalizedBoundingBox,
)

data class GestureTargetCandidate(
    val identity: GestureTargetIdentity,
    val runtimeGeneration: Long,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val confidence: Float,
    val rayEntryDistance: Float,
    val angleToCenterRadians: Float,
)

enum class GestureTargetState {
    SELECTED,
}

/** UI-independent representation of the one subject selected by a pointing gesture. */
data class GestureTargetPresentation(
    val boundingBox: LiveNormalizedBoundingBox,
    val center: NormalizedImagePoint,
    val label: String,
    val confidence: Float,
    val state: GestureTargetState = GestureTargetState.SELECTED,
)
