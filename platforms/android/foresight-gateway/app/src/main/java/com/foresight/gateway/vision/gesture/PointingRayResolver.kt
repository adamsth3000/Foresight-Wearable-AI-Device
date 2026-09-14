package com.foresight.gateway.vision.gesture

import kotlin.math.sqrt

/** Resolves an extended index finger into a normalized image-space pointing ray. */
class PointingRayResolver(
    private val config: Config = Config(),
) {
    data class Config(
        val minimumHandConfidence: Float = 0.5f,
        val minimumSegmentLength: Float = 0.01f,
        val extensionDotThreshold: Float = 0.85f,
    ) {
        init {
            require(minimumHandConfidence in 0f..1f)
            require(minimumSegmentLength > 0f)
            require(extensionDotThreshold in -1f..1f)
        }
    }

    fun resolve(hand: HandObservation): PointingObservation? {
        if (hand.confidence < config.minimumHandConfidence || hand.landmarks.size < HandLandmarkIndex.COUNT) return null
        val mcp = hand.landmarks.getOrNull(HandLandmarkIndex.INDEX_MCP) ?: return null
        val pip = hand.landmarks.getOrNull(HandLandmarkIndex.INDEX_PIP) ?: return null
        val dip = hand.landmarks.getOrNull(HandLandmarkIndex.INDEX_DIP) ?: return null
        val tip = hand.landmarks.getOrNull(HandLandmarkIndex.INDEX_TIP) ?: return null
        val aspect = hand.sourceWidth.toFloat() / hand.sourceHeight.toFloat()
        val first = aspectNormalizedSegment(mcp, pip, aspect) ?: return null
        val second = aspectNormalizedSegment(pip, dip, aspect) ?: return null
        val third = aspectNormalizedSegment(dip, tip, aspect) ?: return null
        if (dot(first, second) < config.extensionDotThreshold || dot(second, third) < config.extensionDotThreshold) return null

        val weighted = Vector(
            first.x + 2f * second.x + 3f * third.x,
            first.y + 2f * second.y + 3f * third.y,
        )
        // Convert from aspect-corrected space back to the normalized coordinate system of detections.
        val direction = normalize(Vector(weighted.x / aspect, weighted.y)) ?: return null
        return PointingObservation(
            runtimeGeneration = hand.runtimeGeneration,
            sourceFrameId = hand.sourceFrameId,
            timestampNanos = hand.timestampNanos,
            sourceWidth = hand.sourceWidth,
            sourceHeight = hand.sourceHeight,
            origin = NormalizedImagePoint(tip.x, tip.y),
            direction = NormalizedImageVector(direction.x, direction.y),
            confidence = hand.confidence,
        )
    }

    private fun aspectNormalizedSegment(
        start: NormalizedHandLandmark,
        end: NormalizedHandLandmark,
        aspect: Float,
    ): Vector? = normalize(Vector((end.x - start.x) * aspect, end.y - start.y))
        ?.takeIf { length(it) >= config.minimumSegmentLength }

    private fun dot(first: Vector, second: Vector): Float = first.x * second.x + first.y * second.y

    private fun normalize(vector: Vector): Vector? {
        val length = length(vector)
        return if (length > config.minimumSegmentLength) Vector(vector.x / length, vector.y / length) else null
    }

    private fun length(vector: Vector): Float = sqrt(vector.x * vector.x + vector.y * vector.y)

    private data class Vector(val x: Float, val y: Float)
}
