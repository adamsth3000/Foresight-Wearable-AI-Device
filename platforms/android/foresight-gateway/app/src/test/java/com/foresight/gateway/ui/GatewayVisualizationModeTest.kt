package com.foresight.gateway.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayVisualizationModeTest {
    @Test
    fun `standard hides the shared compass`() {
        assertFalse(GatewayVisualizationMode.STANDARD.showsCompass)
    }

    @Test
    fun `vision and augmented reality show the shared compass`() {
        assertTrue(GatewayVisualizationMode.VISION.showsCompass)
        assertTrue(GatewayVisualizationMode.AUGMENTED_REALITY.showsCompass)
    }

    @Test
    fun `missing or invalid persisted visualization mode defaults to standard`() {
        assertEquals(GatewayVisualizationMode.STANDARD, GatewayVisualizationMode.restore(null))
        assertEquals(GatewayVisualizationMode.STANDARD, GatewayVisualizationMode.restore("UNKNOWN"))
    }
}
