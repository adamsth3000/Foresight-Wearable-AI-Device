package com.foresight.gateway.voice.conversation

import com.foresight.gateway.vision.SceneLocation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapsGroundingPolicyTest {
    @Test fun `geographic query with fresh location enables maps`() {
        assertTrue(MapsGroundingPolicy.decision("What is nearby?", location(10_000L), false, 20_000L).mapsEnabled)
    }

    @Test fun `current position queries preserve deterministic phone position over Maps grounding`() {
        assertTrue(MapsGroundingPolicy.decision("What is nearby?", location(0L), false, 20_000L).mapsEnabled)
        assertFalse(MapsGroundingPolicy.decision("Where am I?", location(0L), false, 60_000L).mapsEnabled)
        assertFalse(MapsGroundingPolicy.decision("Where am I?", location(0L), false, 60_000L).searchEnabled)
    }

    @Test fun `unrelated query does not enable maps`() {
        assertFalse(MapsGroundingPolicy.decision("Why is the sky blue?", location(10_000L), false, 20_000L).mapsEnabled)
    }

    @Test fun `visual place identification never enables maps without an image`() {
        val decision = MapsGroundingPolicy.decision("What building am I looking at?", location(10_000L), false, 20_000L)
        assertFalse(decision.mapsEnabled)
        assertTrue(decision.requiresVisualEvidence)
        assertTrue(MapsGroundingPolicy.decision("What building am I looking at?", location(10_000L), true, 20_000L).mapsEnabled)
    }

    @Test fun `only geographic questions request a fresher location`() {
        assertTrue(MapsGroundingPolicy.requiresLocationRefresh("Where am I?", location(0L), 30_001L))
        assertFalse(MapsGroundingPolicy.requiresLocationRefresh("Why is the sky blue?", null, 30_001L))
    }

    private fun location(capturedAt: Long) = SceneLocation(1.0, 2.0, 10f, null, capturedAt, "gps")
}
