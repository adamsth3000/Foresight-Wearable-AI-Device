package com.foresight.gateway.vision.gesture

import com.foresight.gateway.vision.DetectionSnapshot
import com.foresight.gateway.vision.LiveDetectionPresentation
import com.foresight.gateway.vision.LiveNormalizedBoundingBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GestureTargetSelectorTest {
    private val selector = GestureTargetSelector(GestureTargetSelector.Config(boxInflationFraction = 0f))

    @Test
    fun `ray intersects a single rectangle`() {
        val target = selector.select(pointing(), snapshot(detection("person", left = 0.4f, top = 0.2f, right = 0.6f, bottom = 0.4f)))

        assertEquals("person", target?.identity?.label)
    }

    @Test
    fun `nearest forward rectangle wins`() {
        val target = selector.select(pointing(), snapshot(
            detection("far", left = 0.4f, top = 0.1f, right = 0.6f, bottom = 0.3f),
            detection("near", left = 0.4f, top = 0.35f, right = 0.6f, bottom = 0.45f),
        ))

        assertEquals("near", target?.identity?.label)
    }

    @Test
    fun `rectangle behind fingertip is rejected`() {
        assertNull(selector.select(pointing(), snapshot(detection("behind", left = 0.4f, top = 0.7f, right = 0.6f, bottom = 0.9f))))
    }

    @Test
    fun `ray missing all rectangles has no target`() {
        assertNull(selector.select(pointing(), snapshot(detection("miss", left = 0.7f, top = 0.2f, right = 0.9f, bottom = 0.4f))))
    }

    @Test
    fun `overlapping rectangles are ranked deterministically by confidence then angle`() {
        val target = selector.select(pointing(), snapshot(
            detection("low", 0.6f, 0.4f, 0.2f, 0.6f, 0.4f),
            detection("high", 0.9f, 0.4f, 0.2f, 0.6f, 0.4f),
        ))

        assertEquals("high", target?.identity?.label)
    }

    @Test
    fun `stale snapshot generation mismatch and coordinate mismatch are rejected`() {
        assertNull(selector.select(pointing(timestamp = 1_300_000_001L), snapshot(timestamp = 0)))
        assertNull(selector.select(pointing(generation = 2), snapshot()))
        assertNull(selector.select(pointing(width = 320), snapshot()))
    }

    private fun pointing(
        generation: Long = 1,
        timestamp: Long = 1_000_000_000L,
        width: Int = 640,
    ) = PointingObservation(
        generation, 8, timestamp, width, 360,
        NormalizedImagePoint(0.5f, 0.6f), NormalizedImageVector(0f, -1f), 0.9f,
    )

    private fun snapshot(
        vararg detections: LiveDetectionPresentation,
        timestamp: Long = 0L,
    ) = DetectionSnapshot(1, 7, timestamp, 640, 360, detections.toList())

    private fun detection(label: String, confidence: Float = 0.8f, left: Float, top: Float, right: Float, bottom: Float) =
        LiveDetectionPresentation(label, confidence, LiveNormalizedBoundingBox(left, top, right, bottom))
}
