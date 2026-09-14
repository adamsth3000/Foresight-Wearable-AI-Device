package com.foresight.gateway.vision.gesture

import com.foresight.gateway.vision.DetectionSnapshot
import com.foresight.gateway.vision.LiveNormalizedBoundingBox
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Selects the nearest forward detection intersected by a normalized image-space pointing ray. */
class GestureTargetSelector(
    private val config: Config = Config(),
) {
    data class Config(
        val maximumDetectionAgeNanos: Long = 1_200_000_000L,
        val boxInflationFraction: Float = 0.03f,
    ) {
        init {
            require(maximumDetectionAgeNanos >= 0L)
            require(boxInflationFraction in 0f..0.5f)
        }
    }

    fun select(pointing: PointingObservation, snapshot: DetectionSnapshot): GestureTargetCandidate? {
        if (pointing.runtimeGeneration != snapshot.runtimeGeneration) return null
        if (pointing.sourceWidth != snapshot.sourceWidth || pointing.sourceHeight != snapshot.sourceHeight) return null
        val age = pointing.timestampNanos - snapshot.captureElapsedRealtimeNanos
        if (age < 0L || age > config.maximumDetectionAgeNanos) return null

        return snapshot.detections.mapNotNull { detection ->
            val box = inflate(detection.boundingBox)
            val entry = forwardEntryDistance(pointing, box) ?: return@mapNotNull null
            val center = centerOf(detection.boundingBox)
            GestureTargetCandidate(
                identity = GestureTargetIdentity(detection.label, snapshot.frameId, detection.boundingBox),
                runtimeGeneration = snapshot.runtimeGeneration,
                sourceWidth = snapshot.sourceWidth,
                sourceHeight = snapshot.sourceHeight,
                confidence = detection.confidence,
                rayEntryDistance = entry,
                angleToCenterRadians = angleTo(pointing, center),
            )
        }.minWithOrNull(
            compareBy<GestureTargetCandidate> { it.rayEntryDistance }
                .thenByDescending { it.confidence }
                .thenBy { it.angleToCenterRadians }
                .thenBy { it.identity.label }
                .thenBy { it.identity.boundingBox.xMin }
                .thenBy { it.identity.boundingBox.yMin },
        )
    }

    private fun inflate(box: LiveNormalizedBoundingBox): LiveNormalizedBoundingBox {
        val horizontal = (box.xMax - box.xMin) * config.boxInflationFraction
        val vertical = (box.yMax - box.yMin) * config.boxInflationFraction
        return LiveNormalizedBoundingBox(
            (box.xMin - horizontal).coerceIn(0f, 1f),
            (box.yMin - vertical).coerceIn(0f, 1f),
            (box.xMax + horizontal).coerceIn(0f, 1f),
            (box.yMax + vertical).coerceIn(0f, 1f),
        )
    }

    private fun forwardEntryDistance(pointing: PointingObservation, box: LiveNormalizedBoundingBox): Float? {
        val direction = pointing.direction
        val xInterval = rayInterval(pointing.origin.x, direction.x, box.xMin, box.xMax) ?: return null
        val yInterval = rayInterval(pointing.origin.y, direction.y, box.yMin, box.yMax) ?: return null
        val entry = max(xInterval.first, yInterval.first)
        val exit = min(xInterval.second, yInterval.second)
        return entry.takeIf { it > 0f && entry <= exit }
    }

    private fun rayInterval(origin: Float, direction: Float, minimum: Float, maximum: Float): Pair<Float, Float>? {
        if (direction == 0f) return if (origin in minimum..maximum) {
            Float.NEGATIVE_INFINITY to Float.POSITIVE_INFINITY
        } else {
            null
        }
        val first = (minimum - origin) / direction
        val second = (maximum - origin) / direction
        return min(first, second) to max(first, second)
    }

    private fun centerOf(box: LiveNormalizedBoundingBox) = NormalizedImagePoint(
        (box.xMin + box.xMax) / 2f,
        (box.yMin + box.yMax) / 2f,
    )

    private fun angleTo(pointing: PointingObservation, center: NormalizedImagePoint): Float {
        val x = center.x - pointing.origin.x
        val y = center.y - pointing.origin.y
        val length = sqrt(x * x + y * y)
        if (length == 0f) return 0f
        val dot = ((pointing.direction.x * x) + (pointing.direction.y * y)) / length
        return acos(dot.coerceIn(-1f, 1f))
    }
}
