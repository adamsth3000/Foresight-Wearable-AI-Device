package com.foresight.gateway.ui

/** Stores the last model applied to a UI region so unchanged polls do not rebuild it. */
internal class GatewayUiRenderCache<T> {
    private var hasRendered = false
    private var renderedValue: T? = null

    fun shouldRender(value: T): Boolean {
        if (hasRendered && renderedValue == value) return false
        renderedValue = value
        hasRendered = true
        return true
    }
}

internal data class SyncHistoryRenderState(
    val entries: List<com.foresight.gateway.capture.EventMediaSyncHistoryEntry>,
    val retryableEventIds: Set<String>,
    val selectedAttemptId: String?,
)

internal data class StatusLightRenderState(
    val isOn: Boolean,
    val onColor: Int,
)
