package com.foresight.gateway.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrStateTest {
    @Test fun `maps line bounds into normalized coordinates`() {
        val region = OcrRegionMapper.region("EXIT", 10, 20, 50, 60, 100, 200, 0, 0)!!
        assertEquals(0.1f, region.normalizedBoundingBox.xMin, 0f)
        assertEquals(0.1f, region.normalizedBoundingBox.yMin, 0f)
        assertEquals(0.5f, region.normalizedBoundingBox.xMax, 0f)
        assertEquals(0.3f, region.normalizedBoundingBox.yMax, 0f)
    }

    @Test fun `drops empty malformed OCR regions`() {
        assertNull(OcrRegionMapper.region("  ", 0, 0, 2, 2, 10, 10, 0, 0))
        assertNull(OcrRegionMapper.region("text", 4, 4, 4, 8, 10, 10, 0, 0))
    }

    @Test fun `rejects stale and mismatched generation observations`() {
        var now = 10_000L
        val state = LatestOcrState { now }
        state.publish(observation(2, 8_000L))
        assertTrue(state.freshObservation(3_000L, 2) != null)
        assertNull(state.freshObservation(3_000L, 3))
        now = 12_000L
        assertNull(state.freshObservation(3_000L, 2))
    }

    private fun observation(generation: Long, timestamp: Long) = OcrObservation(generation, 5, timestamp, 100, 100, emptyList())
}
