package com.foresight.gateway.vision.gesture

import com.foresight.gateway.vision.LiveNormalizedBoundingBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureTargetStabilityPolicyTest {
    @Test
    fun `requires three consecutive wins and rejects unstable alternation`() {
        val policy = GestureTargetStabilityPolicy()
        assertNull(policy.observe(candidate("person"), 1, 1))
        assertNull(policy.observe(candidate("dog"), 1, 2))
        assertNull(policy.observe(candidate("person"), 1, 3))
        assertNull(policy.observe(candidate("person"), 1, 4))
        assertEquals("person", policy.observe(candidate("person"), 1, 5)?.identity?.label)
    }

    @Test
    fun `alternating targets never become selected`() {
        val policy = GestureTargetStabilityPolicy()

        repeat(6) { index ->
            assertNull(policy.observe(candidate(if (index % 2 == 0) "person" else "dog"), 1, index.toLong()))
        }
    }

    @Test
    fun `selected target survives temporary loss then clears after hold timeout`() {
        val policy = GestureTargetStabilityPolicy()
        repeat(3) { policy.observe(candidate("person"), 1, 100L + it) }

        assertEquals("person", policy.observe(null, 1, 400_000_002L)?.identity?.label)
        assertNull(policy.observe(null, 1, 400_000_103L))
    }

    @Test
    fun `generation reset clears selected target immediately`() {
        val policy = GestureTargetStabilityPolicy()
        repeat(3) { policy.observe(candidate("person"), 1, 100L + it) }

        assertNull(policy.observe(null, 2, 103L))
        assertNull(policy.observe(candidate("person"), 2, 104L))
    }

    @Test
    fun `explicit clear removes selected target`() {
        val policy = GestureTargetStabilityPolicy()
        repeat(3) { policy.observe(candidate("person"), 1, 100L + it) }
        policy.clear()

        assertNull(policy.observe(null, 1, 103L))
    }

    private fun candidate(label: String) = GestureTargetCandidate(
        GestureTargetIdentity(label, 1, LiveNormalizedBoundingBox(0.4f, 0.2f, 0.6f, 0.4f)),
        1, 640, 360, 0.9f, 0.2f, 0f,
    )
}
