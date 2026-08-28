package com.foresight.gateway.transport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RtspRetryStateTest {
    @Test
    fun `scheduled retry transitions to an executable attempt when its timer fires`() {
        val state = RtspRetryState()

        assertTrue(state.schedule())
        assertTrue(state.isTimerScheduled)
        assertTrue(state.fireTimer())

        assertFalse(state.isTimerScheduled)
        assertTrue(state.isAttemptInFlight)
    }

    @Test
    fun `failed retry releases state so the next backoff attempt can be scheduled`() {
        val state = RtspRetryState()

        state.schedule()
        state.fireTimer()
        state.connectionFailed()

        assertFalse(state.isAttemptInFlight)
        assertTrue(state.schedule())
    }

    @Test
    fun `accepted retry without callbacks expires and permits another recovery attempt`() {
        val state = RtspRetryState()

        state.schedule()
        state.fireTimer() // Equivalent to RootEncoder reTry() returning true.

        assertTrue(state.isAttemptInFlight)
        assertTrue(state.attemptDeadlineExpired())
        assertFalse(state.isAttemptInFlight)
        assertTrue(state.schedule())
    }
}
