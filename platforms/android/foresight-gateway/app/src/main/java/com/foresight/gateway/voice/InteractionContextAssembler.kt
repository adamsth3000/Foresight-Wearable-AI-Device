package com.foresight.gateway.voice

import com.foresight.gateway.ui.GatewayVisualizationMode
import com.foresight.gateway.vision.LatestDetectionState

class InteractionContextAssembler(
    private val captureActive: () -> Boolean,
    private val visualizationMode: () -> GatewayVisualizationMode,
    private val latestDetectionState: LatestDetectionState,
    private val selectedGestureTargetId: () -> String? = { null },
    private val elapsedRealtimeMillis: () -> Long,
    private val maxDetectionAgeNanos: Long,
) {
    fun assemble(): InteractionContext = InteractionContext(
        captureActive = captureActive(),
        visualizationMode = visualizationMode(),
        latestDetections = latestDetectionState.freshSnapshot(maxDetectionAgeNanos),
        selectedGestureTargetId = selectedGestureTargetId(),
        elapsedRealtimeMillis = elapsedRealtimeMillis(),
    )
}
