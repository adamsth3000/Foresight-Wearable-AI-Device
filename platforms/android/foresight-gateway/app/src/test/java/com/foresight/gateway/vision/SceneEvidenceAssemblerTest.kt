package com.foresight.gateway.vision

import com.foresight.gateway.sensors.HeadingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneEvidenceAssemblerTest {
    @Test fun `assembles current generation evidence with optional heading`() {
        var now = 5_000L
        val detections = LatestDetectionState { now }
        val ocr = LatestOcrState { now }
        detections.publish(DetectionSnapshot(1, 7, 4_000, 640, 360, listOf(LiveDetectionPresentation("bottle", .9f, box()))))
        ocr.publish(OcrObservation(1, 7, 4_100, 640, 360, listOf(OcrRegion("EXIT", normalizedBoundingBox = box()))))
        val snapshot = SceneEvidenceAssembler(detections, ocr, { now }, heading = { HeadingState(45f, null, now) }).assemble(1)
        assertEquals(7L, snapshot.sourceFrameId)
        assertEquals("bottle", snapshot.detections!!.detections.single().label)
        assertEquals("EXIT", snapshot.ocrObservation!!.regions.single().text)
        assertTrue(snapshot.heading!!.isAvailable)
        assertNull(snapshot.location)
        assertTrue(snapshot.visualConceptMatches.isEmpty())
    }

    @Test fun `excludes stale and generation mismatched evidence`() {
        var now = 10_000L
        val detections = LatestDetectionState { now }
        val ocr = LatestOcrState { now }
        detections.publish(DetectionSnapshot(2, 1, 1_000, 10, 10, emptyList()))
        ocr.publish(OcrObservation(3, 2, 9_000, 10, 10, emptyList()))
        val snapshot = SceneEvidenceAssembler(detections, ocr, { now }, maxDetectionAgeNanos = 2_000, maxOcrAgeNanos = 2_000).assemble(2)
        assertNull(snapshot.detections)
        assertNull(snapshot.ocrObservation)
    }

    @Test fun `latest scene evidence expires before conversation can consume it`() {
        val state = LatestSceneEvidenceState()
        state.publish(SceneEvidenceSnapshot(generation = 1, capturedAtNanos = 1_000L))
        assertTrue(state.freshSnapshot(2_000L, 1_500L) != null)
        assertNull(state.freshSnapshot(3_000L, 1_500L))
    }

    @Test fun `includes 20 second location independently of heading and excludes stale location`() {
        var now = 20_000_000_000L
        val location = SceneLocation(1.0, 2.0, 8f, null, 15_000L, "gps")
        val assembler = SceneEvidenceAssembler(
            latestDetections = LatestDetectionState { now },
            latestOcr = LatestOcrState { now },
            elapsedRealtimeNanos = { now },
            elapsedRealtimeMillis = { now / 1_000_000L },
            location = { location },
        )
        assertEquals(location, assembler.assemble(1).location)

        now = 35_000_000_000L
        assertEquals(location, assembler.assemble(1).location)

        now = 45_001_000_000L
        assertNull(assembler.assemble(1).location)
        assertNull(assembler.assemble(1).heading)
    }

    private fun box() = LiveNormalizedBoundingBox(0.1f, 0.1f, 0.2f, 0.2f)
}
