package com.foresight.gateway.vision

import com.foresight.gateway.ui.GatewayVisualizationMode

internal object LiveVisionRuntimePolicy {
    fun runtimeActive(
        visualizationMode: GatewayVisualizationMode,
        captureActive: Boolean,
        activityResumed: Boolean,
    ): Boolean = visualizationMode in setOf(GatewayVisualizationMode.VISION, GatewayVisualizationMode.AUGMENTED_REALITY) && captureActive && activityResumed
}

internal sealed interface LiveVisionRuntimeTransition {
    data class Started(val generation: Long) : LiveVisionRuntimeTransition
    data object Stopped : LiveVisionRuntimeTransition
    data object Unchanged : LiveVisionRuntimeTransition
}

/** Owns only presentation-runtime generations; it never starts or stops capture. */
internal class LiveVisionRuntime {
    private var active = false
    private var generation = 0L

    fun reconcile(shouldBeActive: Boolean): LiveVisionRuntimeTransition = when {
        shouldBeActive && !active -> {
            active = true
            generation += 1
            LiveVisionRuntimeTransition.Started(generation)
        }
        !shouldBeActive && active -> {
            active = false
            generation += 1 // Invalidate every late response from the stopped runtime.
            LiveVisionRuntimeTransition.Stopped
        }
        else -> LiveVisionRuntimeTransition.Unchanged
    }

    fun accepts(snapshot: DetectionSnapshot): Boolean = active && snapshot.runtimeGeneration == generation

    fun activeGeneration(): Long? = generation.takeIf { active }
}
