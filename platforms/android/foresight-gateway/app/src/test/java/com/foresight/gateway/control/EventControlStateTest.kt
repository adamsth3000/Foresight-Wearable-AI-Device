package com.foresight.gateway.control

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventControlStateTest {
    @Test
    fun `authoritative event states enable only valid bounded controls`() {
        assertTrue(EventControlState().canStartBounded)
        assertFalse(EventControlState().canEndBounded)
        assertFalse(EventControlState("recording_bounded_event", "event-1").canStartBounded)
        assertTrue(EventControlState("recording_bounded_event", "event-1").canEndBounded)
        assertFalse(EventControlState("finalizing", "event-1").canStartBounded)
        assertFalse(EventControlState("finalizing", "event-1").canEndBounded)
    }
}
