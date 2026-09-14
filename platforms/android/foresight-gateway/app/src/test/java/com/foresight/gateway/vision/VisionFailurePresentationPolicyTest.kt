package com.foresight.gateway.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionFailurePresentationPolicyTest {
    @Test
    fun `detector failures only clear the active generation and do not change runtime authority`() {
        val runtime = LiveVisionRuntime()
        val started = runtime.reconcile(true) as LiveVisionRuntimeTransition.Started

        assertTrue(VisionFailurePresentationPolicy.shouldClearOverlay(runtime.activeGeneration(), started.generation))
        assertEquals(started.generation, runtime.activeGeneration())
        assertTrue(runtime.accepts(snapshot(started.generation)))
    }

    @Test
    fun `late detector failures cannot clear a newer Vision generation`() {
        val runtime = LiveVisionRuntime()
        val first = runtime.reconcile(true) as LiveVisionRuntimeTransition.Started
        runtime.reconcile(false)
        val second = runtime.reconcile(true) as LiveVisionRuntimeTransition.Started

        assertFalse(VisionFailurePresentationPolicy.shouldClearOverlay(runtime.activeGeneration(), first.generation))
        assertTrue(VisionFailurePresentationPolicy.shouldClearOverlay(runtime.activeGeneration(), second.generation))
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
