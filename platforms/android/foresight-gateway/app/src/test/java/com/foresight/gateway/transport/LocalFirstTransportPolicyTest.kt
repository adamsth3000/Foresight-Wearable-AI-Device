package com.foresight.gateway.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalFirstTransportPolicyTest {
    @Test
    fun `active local recording defers destructive transport replacement`() {
        assertEquals(
            LocalFirstTransportPolicy.ReplacementAction.DEGRADE,
            LocalFirstTransportPolicy.replacementAction(localRecordingActive = true),
        )
    }

    @Test
    fun `inactive recording permits replacement after the session is gone`() {
        assertEquals(
            LocalFirstTransportPolicy.ReplacementAction.REBUILD,
            LocalFirstTransportPolicy.replacementAction(localRecordingActive = false),
        )
    }

    @Test
    fun `local recording enters bounded degraded mode after retry budget`() {
        assertFalse(LocalFirstTransportPolicy.shouldEnterDegradedMode(true, 4, 5))
        assertTrue(LocalFirstTransportPolicy.shouldEnterDegradedMode(true, 5, 5))
        assertFalse(LocalFirstTransportPolicy.shouldEnterDegradedMode(false, 99, 5))
    }
}
