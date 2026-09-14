package com.foresight.gateway.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayVisualizationRuntimePolicyTest {
    @Test
    fun `inactive capture never activates compass runtime`() {
        GatewayVisualizationMode.entries.forEach { mode ->
            assertFalse(
                GatewayVisualizationRuntimePolicy.compassRuntimeActive(
                    visualizationMode = mode,
                    captureActive = false,
                    activityResumed = true,
                ),
            )
        }
    }

    @Test
    fun `active capture enables runtime only for vision and augmented reality`() {
        assertFalse(runtimeActive(GatewayVisualizationMode.STANDARD))
        assertTrue(runtimeActive(GatewayVisualizationMode.VISION))
        assertTrue(runtimeActive(GatewayVisualizationMode.AUGMENTED_REALITY))
    }

    @Test
    fun `vision to augmented reality retains active runtime without a stop state`() {
        assertTrue(runtimeActive(GatewayVisualizationMode.VISION))
        assertTrue(runtimeActive(GatewayVisualizationMode.AUGMENTED_REALITY))
        assertTrue(runtimeActive(GatewayVisualizationMode.VISION))
    }

    @Test
    fun `standard and stopped capture disable an active visualization runtime`() {
        assertFalse(runtimeActive(GatewayVisualizationMode.STANDARD))
        assertFalse(
            GatewayVisualizationRuntimePolicy.compassRuntimeActive(
                visualizationMode = GatewayVisualizationMode.VISION,
                captureActive = false,
                activityResumed = true,
            ),
        )
    }

    @Test
    fun `paused activity disables runtime without changing visualization selection`() {
        assertFalse(
            GatewayVisualizationRuntimePolicy.compassRuntimeActive(
                visualizationMode = GatewayVisualizationMode.AUGMENTED_REALITY,
                captureActive = true,
                activityResumed = false,
            ),
        )
    }

    private fun runtimeActive(mode: GatewayVisualizationMode): Boolean =
        GatewayVisualizationRuntimePolicy.compassRuntimeActive(
            visualizationMode = mode,
            captureActive = true,
            activityResumed = true,
        )
}
