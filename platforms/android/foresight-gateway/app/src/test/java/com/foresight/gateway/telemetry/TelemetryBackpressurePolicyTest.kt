package com.foresight.gateway.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryBackpressurePolicyTest {
    @Test
    fun `imu samples are retained at a bounded ten hertz per stream`() {
        val policy = TelemetryBackpressurePolicy()

        assertTrue(policy.retain("accelerometer", 1_000_000_000L))
        assertFalse(policy.retain("accelerometer", 1_050_000_000L))
        assertTrue(policy.retain("accelerometer", 1_100_000_000L))
        assertTrue(policy.retain("gyroscope", 1_050_000_000L))
    }

    @Test
    fun `failure backoff is bounded and success resets it`() {
        val policy = TelemetryBackpressurePolicy()

        assertEquals(1_000L, policy.nextFailureDelayMillis())
        assertEquals(2_000L, policy.nextFailureDelayMillis())
        repeat(10) { policy.nextFailureDelayMillis() }
        assertEquals(30_000L, policy.nextFailureDelayMillis())

        policy.recordSuccess()

        assertEquals(1_000L, policy.nextFailureDelayMillis())
    }

    @Test
    fun `normal batch scheduling observes a minimum upload interval`() {
        val policy = TelemetryBackpressurePolicy()

        assertEquals(1_000L, policy.normalUploadDelayMillis(10_000L, 10_000L))
        assertEquals(0L, policy.normalUploadDelayMillis(11_000L, 10_000L))
    }
}
