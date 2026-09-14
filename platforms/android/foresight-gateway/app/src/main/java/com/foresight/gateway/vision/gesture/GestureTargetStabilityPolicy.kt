package com.foresight.gateway.vision.gesture

import com.foresight.gateway.vision.LiveNormalizedBoundingBox
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Pure hysteresis for frame-scoped detection identities until object tracking is introduced. */
class GestureTargetStabilityPolicy(
    private val config: Config = Config(),
) {
    data class Config(
        val consecutiveWinsRequired: Int = 3,
        val holdDurationNanos: Long = 400_000_000L,
        val overlapMatchThreshold: Float = 0.5f,
        val centerMatchDistance: Float = 0.08f,
    ) {
        init {
            require(consecutiveWinsRequired > 0)
            require(holdDurationNanos >= 0L)
            require(overlapMatchThreshold in 0f..1f)
            require(centerMatchDistance >= 0f)
        }
    }

    private var activeGeneration: Long? = null
    private var selected: GestureTargetCandidate? = null
    private var pending: GestureTargetCandidate? = null
    private var pendingWins = 0
    private var lastSelectedConfirmationNanos = Long.MIN_VALUE

    fun observe(candidate: GestureTargetCandidate?, runtimeGeneration: Long, timestampNanos: Long): GestureTargetCandidate? {
        if (activeGeneration != null && activeGeneration != runtimeGeneration) reset()
        activeGeneration = runtimeGeneration
        val current = selected
        if (candidate == null) {
            pending = null
            pendingWins = 0
            return if (current != null && timestampNanos - lastSelectedConfirmationNanos <= config.holdDurationNanos) {
                current
            } else {
                selected = null
                null
            }
        }

        if (current != null && matches(current, candidate)) {
            selected = candidate
            pending = null
            pendingWins = 0
            lastSelectedConfirmationNanos = timestampNanos
            return candidate
        }

        if (pending != null && matches(pending!!, candidate)) {
            pending = candidate
            pendingWins += 1
        } else {
            pending = candidate
            pendingWins = 1
        }
        if (pendingWins >= config.consecutiveWinsRequired) {
            selected = candidate
            pending = null
            pendingWins = 0
            lastSelectedConfirmationNanos = timestampNanos
        }
        return selected
    }

    fun clear() = reset()

    private fun reset() {
        activeGeneration = null
        selected = null
        pending = null
        pendingWins = 0
        lastSelectedConfirmationNanos = Long.MIN_VALUE
    }

    private fun matches(first: GestureTargetCandidate, second: GestureTargetCandidate): Boolean {
        if (first.identity.label != second.identity.label) return false
        val firstBox = first.identity.boundingBox
        val secondBox = second.identity.boundingBox
        return intersectionOverUnion(firstBox, secondBox) >= config.overlapMatchThreshold ||
            (abs(centerX(firstBox) - centerX(secondBox)) <= config.centerMatchDistance &&
                abs(centerY(firstBox) - centerY(secondBox)) <= config.centerMatchDistance)
    }

    private fun intersectionOverUnion(first: LiveNormalizedBoundingBox, second: LiveNormalizedBoundingBox): Float {
        val left = max(first.xMin, second.xMin)
        val top = max(first.yMin, second.yMin)
        val right = min(first.xMax, second.xMax)
        val bottom = min(first.yMax, second.yMax)
        val overlap = max(0f, right - left) * max(0f, bottom - top)
        val firstArea = (first.xMax - first.xMin) * (first.yMax - first.yMin)
        val secondArea = (second.xMax - second.xMin) * (second.yMax - second.yMin)
        return overlap / (firstArea + secondArea - overlap)
    }

    private fun centerX(box: LiveNormalizedBoundingBox): Float = (box.xMin + box.xMax) / 2f
    private fun centerY(box: LiveNormalizedBoundingBox): Float = (box.yMin + box.yMax) / 2f
}
