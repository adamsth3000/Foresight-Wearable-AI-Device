package com.foresight.gateway.vision

import com.foresight.gateway.ui.GatewayVisualizationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveVisionRuntimeTest {
    @Test
    fun `Vision runtime needs Vision active capture and resumed activity`() {
        assertFalse(LiveVisionRuntimePolicy.runtimeActive(GatewayVisualizationMode.STANDARD, true, true))
        assertFalse(LiveVisionRuntimePolicy.runtimeActive(GatewayVisualizationMode.VISION, false, true))
        assertFalse(LiveVisionRuntimePolicy.runtimeActive(GatewayVisualizationMode.VISION, true, false))
        assertTrue(LiveVisionRuntimePolicy.runtimeActive(GatewayVisualizationMode.VISION, true, true))
        assertTrue(
            LiveVisionRuntimePolicy.runtimeActive(GatewayVisualizationMode.AUGMENTED_REALITY, true, true),
        )
    }

    @Test
    fun `stopping runtime invalidates late Vision responses without changing capture authority`() {
        val runtime = LiveVisionRuntime()
        val started = runtime.reconcile(true) as LiveVisionRuntimeTransition.Started
        val snapshot = snapshot(started.generation)
        assertTrue(runtime.accepts(snapshot))

        assertEquals(LiveVisionRuntimeTransition.Stopped, runtime.reconcile(false))
        assertFalse(runtime.accepts(snapshot))
        assertNull(runtime.activeGeneration())
    }

    @Test
    fun `active Vision reconciliation does not restart its runtime`() {
        val runtime = LiveVisionRuntime()
        val started = runtime.reconcile(true) as LiveVisionRuntimeTransition.Started

        assertEquals(LiveVisionRuntimeTransition.Unchanged, runtime.reconcile(true))
        assertEquals(started.generation, runtime.activeGeneration())
    }

    private fun snapshot(generation: Long) = DetectionSnapshot(
        runtimeGeneration = generation,
        frameId = 1,
        captureElapsedRealtimeNanos = 1,
        sourceWidth = 640,
        sourceHeight = 360,
        detections = emptyList(),
    )
}
