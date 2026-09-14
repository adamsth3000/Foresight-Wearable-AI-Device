package com.foresight.gateway.vision

import com.foresight.gateway.sensors.HeadingState
import com.foresight.gateway.vision.gesture.GestureTargetPresentation

data class SceneLocation(
    val latitude: Double,
    val longitude: Double,
    val horizontalAccuracyMeters: Float?,
    val altitudeMeters: Double?,
    val capturedAtElapsedMillis: Long,
    val provider: String?,
) {
    init {
        require(latitude in -90.0..90.0 && longitude in -180.0..180.0)
        require(horizontalAccuracyMeters == null || horizontalAccuracyMeters >= 0f)
        require(capturedAtElapsedMillis >= 0)
    }

    fun ageMillis(nowElapsedMillis: Long): Long = (nowElapsedMillis - capturedAtElapsedMillis).coerceAtLeast(0L)
}

enum class SceneLocationFreshness { FRESH, AGING, STALE }

enum class SceneLocationQuality { EXCELLENT, GOOD, APPROXIMATE, POOR }

object SceneLocationQualityPolicy {
    fun classify(horizontalAccuracyMeters: Float?): SceneLocationQuality = when {
        horizontalAccuracyMeters == null -> SceneLocationQuality.POOR
        horizontalAccuracyMeters <= 3f -> SceneLocationQuality.EXCELLENT
        horizontalAccuracyMeters <= 10f -> SceneLocationQuality.GOOD
        horizontalAccuracyMeters <= 25f -> SceneLocationQuality.APPROXIMATE
        else -> SceneLocationQuality.POOR
    }
}

object SceneLocationFreshnessPolicy {
    const val SCENE_FRESH_MAX_AGE_MILLIS = 30_000L
    const val MAPS_FRESH_MAX_AGE_MILLIS = 60_000L
    const val AGING_MAX_AGE_MILLIS = 60_000L

    fun classify(location: SceneLocation, nowElapsedMillis: Long): SceneLocationFreshness = when {
        location.ageMillis(nowElapsedMillis) <= SCENE_FRESH_MAX_AGE_MILLIS -> SceneLocationFreshness.FRESH
        location.ageMillis(nowElapsedMillis) <= AGING_MAX_AGE_MILLIS -> SceneLocationFreshness.AGING
        else -> SceneLocationFreshness.STALE
    }
}

class LatestSceneLocationState(private val elapsedRealtimeMillis: () -> Long) {
    @Volatile private var latest: SceneLocation? = null

    fun publish(value: SceneLocation) { latest = value }
    fun clear() { latest = null }
    fun latestLocation(): SceneLocation? = latest
    fun freshLocation(maxAgeMillis: Long = SceneLocationFreshnessPolicy.SCENE_FRESH_MAX_AGE_MILLIS): SceneLocation? {
        require(maxAgeMillis >= 0)
        return latest?.takeIf { it.ageMillis(elapsedRealtimeMillis()) <= maxAgeMillis }
    }
}

data class VisualConceptMatch(val conceptId: String, val name: String, val confidence: Float) {
    init { require(conceptId.isNotBlank() && name.isNotBlank() && confidence in 0f..1f) }
}

/** Immutable current-scene evidence. Observations retain their own frame IDs and timestamps. */
data class SceneEvidenceSnapshot(
    val generation: Long,
    val capturedAtNanos: Long,
    val sourceFrameId: Long? = null,
    val sourceWidth: Int? = null,
    val sourceHeight: Int? = null,
    val detections: DetectionSnapshot? = null,
    val ocrObservation: OcrObservation? = null,
    val selectedTarget: GestureTargetPresentation? = null,
    val heading: HeadingState? = null,
    val location: SceneLocation? = null,
    val visualConceptMatches: List<VisualConceptMatch> = emptyList(),
)

class LatestSceneEvidenceState {
    @Volatile private var latest: SceneEvidenceSnapshot? = null
    fun publish(value: SceneEvidenceSnapshot) { latest = value }
    fun clear() { latest = null }
    fun latestSnapshot() = latest
    fun freshSnapshot(nowNanos: Long, maxAgeNanos: Long): SceneEvidenceSnapshot? {
        require(maxAgeNanos >= 0)
        return latest?.takeIf { nowNanos - it.capturedAtNanos <= maxAgeNanos }
    }
}

class SceneEvidenceAssembler(
    private val latestDetections: LatestDetectionState,
    private val latestOcr: LatestOcrState,
    private val elapsedRealtimeNanos: () -> Long,
    private val elapsedRealtimeMillis: () -> Long = { elapsedRealtimeNanos() / 1_000_000L },
    private val selectedTarget: () -> GestureTargetPresentation? = { null },
    private val heading: () -> HeadingState? = { null },
    private val location: () -> SceneLocation? = { null },
    private val conceptMatches: () -> List<VisualConceptMatch> = { emptyList() },
    private val maxDetectionAgeNanos: Long = 3_000_000_000L,
    private val maxOcrAgeNanos: Long = 2_500_000_000L,
) {
    fun assemble(generation: Long): SceneEvidenceSnapshot {
        val detections = latestDetections.freshSnapshot(maxDetectionAgeNanos)?.takeIf { it.runtimeGeneration == generation }
        val ocr = latestOcr.freshObservation(maxOcrAgeNanos, generation)
        val primary = detections?.takeIf { it.frameId == ocr?.sourceFrameId } ?: detections ?: ocr
        return SceneEvidenceSnapshot(
            generation = generation,
            capturedAtNanos = elapsedRealtimeNanos(),
            sourceFrameId = when (primary) { is DetectionSnapshot -> primary.frameId; is OcrObservation -> primary.sourceFrameId; else -> null },
            sourceWidth = when (primary) { is DetectionSnapshot -> primary.sourceWidth; is OcrObservation -> primary.sourceWidth; else -> null },
            sourceHeight = when (primary) { is DetectionSnapshot -> primary.sourceHeight; is OcrObservation -> primary.sourceHeight; else -> null },
            detections = detections, ocrObservation = ocr, selectedTarget = selectedTarget(), heading = heading(),
            location = location()?.takeIf { it.ageMillis(elapsedRealtimeMillis()) <= SceneLocationFreshnessPolicy.SCENE_FRESH_MAX_AGE_MILLIS },
            visualConceptMatches = conceptMatches(),
        )
    }
}
