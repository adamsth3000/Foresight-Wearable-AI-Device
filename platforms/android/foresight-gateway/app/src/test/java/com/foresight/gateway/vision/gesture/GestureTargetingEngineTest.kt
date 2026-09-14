package com.foresight.gateway.vision.gesture

import com.foresight.gateway.vision.DetectionSnapshot
import com.foresight.gateway.vision.LiveDetectionPresentation
import com.foresight.gateway.vision.LiveNormalizedBoundingBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GestureTargetingEngineTest {
    @Test
    fun `selected detection produces circular target presentation`() {
        val engine = GestureTargetingEngine()
        val hand = hand()
        val snapshot = DetectionSnapshot(
            runtimeGeneration = 1,
            frameId = 9,
            captureElapsedRealtimeNanos = 0,
            sourceWidth = 640,
            sourceHeight = 360,
            detections = listOf(LiveDetectionPresentation("person", 0.9f, LiveNormalizedBoundingBox(0.4f, 0.1f, 0.6f, 0.3f))),
        )

        assertNull(engine.submit(hand, snapshot))
        assertNull(engine.submit(hand.copy(sourceFrameId = 2, timestampNanos = 2), snapshot))
        val presentation = engine.submit(hand.copy(sourceFrameId = 3, timestampNanos = 3), snapshot)

        assertEquals("person", presentation?.label)
        assertEquals(0.5f, presentation?.center?.x ?: 0f, 0f)
    }

    @Test
    fun `no target remains empty`() {
        val engine = GestureTargetingEngine()
        assertNull(engine.submitNoHandObservation(1, 1))
    }

    private fun hand(): HandObservation {
        val landmarks = MutableList(HandLandmarkIndex.COUNT) { NormalizedHandLandmark(0.5f, 0.5f) }
        landmarks[5] = NormalizedHandLandmark(0.4f, 0.7f)
        landmarks[6] = NormalizedHandLandmark(0.45f, 0.55f)
        landmarks[7] = NormalizedHandLandmark(0.5f, 0.4f)
        landmarks[8] = NormalizedHandLandmark(0.5f, 0.35f)
        return HandObservation(1, 1, 1, 640, 360, Handedness.RIGHT, 0.9f, landmarks, 0.9f)
    }
}
