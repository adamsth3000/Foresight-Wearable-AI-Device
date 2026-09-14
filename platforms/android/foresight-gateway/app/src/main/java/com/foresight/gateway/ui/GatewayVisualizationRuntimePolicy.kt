package com.foresight.gateway.ui

/** Pure presentation policy; capture authority remains with CaptureForegroundService status. */
internal object GatewayVisualizationRuntimePolicy {
    fun compassRuntimeActive(
        visualizationMode: GatewayVisualizationMode,
        captureActive: Boolean,
        activityResumed: Boolean,
    ): Boolean = visualizationMode.showsCompass && captureActive && activityResumed
}
