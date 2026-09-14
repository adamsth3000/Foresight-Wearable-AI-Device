package com.foresight.gateway.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SceneLocationTest {
    @Test fun `latest state returns fresh location and excludes stale location`() {
        var now = 1_000L
        val state = LatestSceneLocationState { now }
        val location = location(capturedAt = now)
        state.publish(location)

        assertSame(location, state.freshLocation())
        now += 30_001L
        assertNull(state.freshLocation())
    }

    @Test fun `freshness distinguishes fresh aging and stale locations`() {
        val location = location(capturedAt = 100L)
        assertEquals(SceneLocationFreshness.FRESH, SceneLocationFreshnessPolicy.classify(location, 20_100L))
        assertEquals(SceneLocationFreshness.AGING, SceneLocationFreshnessPolicy.classify(location, 30_101L))
        assertEquals(SceneLocationFreshness.STALE, SceneLocationFreshnessPolicy.classify(location, 60_101L))
    }

    @Test fun `quality reports accuracy without claiming exact position`() {
        assertEquals(SceneLocationQuality.EXCELLENT, SceneLocationQualityPolicy.classify(2f))
        assertEquals(SceneLocationQuality.GOOD, SceneLocationQualityPolicy.classify(8f))
        assertEquals(SceneLocationQuality.APPROXIMATE, SceneLocationQualityPolicy.classify(17f))
        assertEquals(SceneLocationQuality.POOR, SceneLocationQualityPolicy.classify(null))
    }

    private fun location(capturedAt: Long) = SceneLocation(1.0, 2.0, 12f, null, capturedAt, "network")
}
