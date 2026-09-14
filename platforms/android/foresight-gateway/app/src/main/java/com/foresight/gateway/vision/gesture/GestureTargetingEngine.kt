package com.foresight.gateway.vision.gesture

import com.foresight.gateway.vision.DetectionSnapshot

/** Testable injection boundary for a future hand-landmark source; it owns no capture or inference work. */
class GestureTargetingEngine(
    private val rayResolver: PointingRayResolver = PointingRayResolver(),
    private val selector: GestureTargetSelector = GestureTargetSelector(),
    private val stabilityPolicy: GestureTargetStabilityPolicy = GestureTargetStabilityPolicy(),
) {
    fun submit(hand: HandObservation, detections: DetectionSnapshot): GestureTargetPresentation? {
        val pointing = rayResolver.resolve(hand)
        val candidate = pointing?.let { selector.select(it, detections) }
        return stabilityPolicy.observe(candidate, hand.runtimeGeneration, hand.timestampNanos)?.toPresentation()
    }

    fun submitNoHandObservation(runtimeGeneration: Long, timestampNanos: Long): GestureTargetPresentation? =
        stabilityPolicy.observe(null, runtimeGeneration, timestampNanos)?.toPresentation()

    fun clear() = stabilityPolicy.clear()

    private fun GestureTargetCandidate.toPresentation(): GestureTargetPresentation {
        val box = identity.boundingBox
        return GestureTargetPresentation(
            boundingBox = box,
            center = NormalizedImagePoint((box.xMin + box.xMax) / 2f, (box.yMin + box.yMax) / 2f),
            label = identity.label,
            confidence = confidence,
        )
    }
}
