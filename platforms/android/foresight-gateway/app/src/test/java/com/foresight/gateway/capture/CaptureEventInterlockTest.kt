package com.foresight.gateway.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureEventInterlockTest {
    @Test
    fun `capture stop is blocked only while bounded media is still required`() {
        assertTrue(CaptureEventInterlock.blocksCaptureStop("recording_bounded_event"))
        assertTrue(CaptureEventInterlock.blocksCaptureStop("finalizing"))
        assertFalse(CaptureEventInterlock.blocksCaptureStop("idle"))
    }
}
