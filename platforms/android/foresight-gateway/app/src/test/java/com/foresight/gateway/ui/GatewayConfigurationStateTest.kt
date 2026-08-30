package com.foresight.gateway.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayConfigurationStateTest {
    @Test
    fun `entered control base becomes the authoritative trimmed value`() {
        val state = GatewayConfigurationState().updateControlBaseUrl(" http://192.168.1.171:8766/ ")

        assertEquals("http://192.168.1.171:8766/", state.controlBaseUrl)
    }

    @Test
    fun `saved control base restores across activity recreation`() {
        val state = GatewayConfigurationState.restore("http://192.168.1.171:8766", null)

        assertEquals("http://192.168.1.171:8766", state.controlBaseUrl)
    }

    @Test
    fun `existing telemetry base migrates only when no control base was previously saved`() {
        val state = GatewayConfigurationState.restore(null, "http://192.168.1.171:8766")

        assertEquals("http://192.168.1.171:8766", state.controlBaseUrl)
        assertTrue(state.controlBaseUrl.isNotBlank())
    }
}
