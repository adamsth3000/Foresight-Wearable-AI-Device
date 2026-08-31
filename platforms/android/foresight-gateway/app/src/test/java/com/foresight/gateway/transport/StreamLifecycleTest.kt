package com.foresight.gateway.transport

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamLifecycleTest {
    @Test
    fun `idle is the initial state`() {
        assertEquals(StreamLifecycle.IDLE, StreamLifecycle.entries.first())
    }

    @Test
    fun `streaming state is available to service and UI`() {
        assertEquals("STREAMING", StreamLifecycle.STREAMING.name)
    }

    @Test
    fun `reconnecting is distinct from connected streaming`() {
        assertEquals("RECONNECTING", StreamLifecycle.RECONNECTING.name)
    }

    @Test
    fun `degraded is distinct from capture failure`() {
        assertEquals("DEGRADED", StreamLifecycle.DEGRADED.name)
    }
}
