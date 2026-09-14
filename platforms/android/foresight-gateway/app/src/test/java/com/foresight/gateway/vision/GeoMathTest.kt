package com.foresight.gateway.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoMathTest {
    @Test fun `distance and bearing use geographic coordinates`() {
        val origin = location(0.0, 0.0)
        val east = location(0.0, 1.0)
        assertTrue(GeoMath.distanceMeters(origin, east) in 111_000.0..112_500.0)
        assertEquals(90.0, GeoMath.initialBearingDegrees(origin, east), 0.01)
    }

    @Test fun `shortest delta wraps at north`() {
        assertEquals(2.0, GeoMath.shortestAngularDeltaDegrees(359.0, 1.0), 0.001)
        assertEquals(-2.0, GeoMath.shortestAngularDeltaDegrees(1.0, 359.0), 0.001)
    }

    @Test fun `candidate ahead ranks before candidate behind user`() {
        val phone = location(0.0, 0.0)
        val ranked = DirectionalPlaceCandidateFilter.rank(
            phone,
            headingDegrees = 90f,
            candidates = listOf(
                PlaceCandidate("behind", location(0.0, -0.01)),
                PlaceCandidate("ahead", location(0.0, 0.01)),
            ),
        )

        assertEquals("ahead", ranked.first().candidate.candidateId)
        assertEquals(listOf("ahead"), DirectionalPlaceCandidateFilter.inFrontOfUser(ranked).map { it.candidate.candidateId })
    }

    private fun location(latitude: Double, longitude: Double) = SceneLocation(latitude, longitude, null, null, 0L, null)
}
