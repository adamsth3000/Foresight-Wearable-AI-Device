package com.foresight.gateway.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPipeDetectionMapperTest {
    @Test
    fun `maps model pixels into normalized presentation coordinates`() {
        val detection = MediaPipeDetectionMapper.map(
            candidates = listOf(
                MediaPipeDetectionCandidate("person", 0.9f, 64f, 36f, 320f, 180f),
            ),
            sourceWidth = 640,
            sourceHeight = 360,
        ).single()

        assertEquals("person", detection.label)
        assertEquals(0.9f, detection.confidence, 0f)
        assertEquals(0.1f, detection.boundingBox.xMin, 0f)
        assertEquals(0.1f, detection.boundingBox.yMin, 0f)
        assertEquals(0.5f, detection.boundingBox.xMax, 0f)
        assertEquals(0.5f, detection.boundingBox.yMax, 0f)
    }

    @Test
    fun `clamps valid out of bounds coordinates and preserves COCO labels`() {
        val detection = MediaPipeDetectionMapper.map(
            candidates = listOf(
                MediaPipeDetectionCandidate("stop sign", 1.2f, -10f, -20f, 700f, 400f),
            ),
            sourceWidth = 640,
            sourceHeight = 360,
        ).single()

        assertEquals("stop sign", detection.label)
        assertEquals(1f, detection.confidence, 0f)
        assertEquals(LiveNormalizedBoundingBox(0f, 0f, 1f, 1f), detection.boundingBox)
    }

    @Test
    fun `drops empty invalid and degenerate results`() {
        val detections = MediaPipeDetectionMapper.map(
            candidates = listOf(
                MediaPipeDetectionCandidate("", 0.8f, 0f, 0f, 10f, 10f),
                MediaPipeDetectionCandidate("dog", Float.NaN, 0f, 0f, 10f, 10f),
                MediaPipeDetectionCandidate("car", 0.8f, 10f, 10f, 10f, 30f),
            ),
            sourceWidth = 640,
            sourceHeight = 360,
        )

        assertTrue(detections.isEmpty())
    }

    @Test
    fun `returns no detections for invalid source dimensions`() {
        assertTrue(
            MediaPipeDetectionMapper.map(
                candidates = listOf(MediaPipeDetectionCandidate("person", 0.8f, 0f, 0f, 10f, 10f)),
                sourceWidth = 0,
                sourceHeight = 360,
            ).isEmpty(),
        )
    }
}
