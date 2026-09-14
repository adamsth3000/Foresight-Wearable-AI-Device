package com.foresight.gateway.vision

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceVisionInferenceGateTest {
    @Test
    fun `allows exactly one in flight inference and drops busy samples`() {
        val gate = OnDeviceVisionInferenceGate()

        assertTrue(gate.tryBegin())
        assertFalse(gate.tryBegin())
        assertTrue(gate.isInFlight())
        assertTrue(gate.skippedSamples() == 1L)

        gate.complete()

        assertFalse(gate.isInFlight())
        assertTrue(gate.tryBegin())
    }
}
