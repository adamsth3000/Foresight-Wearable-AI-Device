package com.foresight.gateway.transport

import org.junit.Assert.assertEquals
import org.junit.Test

class RtspReconnectPolicyTest {
    @Test
    fun `retry delays use bounded exponential backoff`() {
        val policy = RtspReconnectPolicy()

        assertEquals(1_000L, policy.nextDelayMillis())
        assertEquals(2_000L, policy.nextDelayMillis())
        assertEquals(4_000L, policy.nextDelayMillis())
        assertEquals(8_000L, policy.nextDelayMillis())
        assertEquals(8_000L, policy.nextDelayMillis())
        assertEquals(5, policy.attempts())
    }

    @Test
    fun `successful recovery resets retry backoff`() {
        val policy = RtspReconnectPolicy()
        policy.nextDelayMillis()
        policy.nextDelayMillis()

        policy.reset()

        assertEquals(0, policy.attempts())
        assertEquals(1_000L, policy.nextDelayMillis())
    }
}
