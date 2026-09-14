package com.foresight.gateway.vision

/** Limits detector failures to presentation cleanup for the still-active Vision generation. */
internal object VisionFailurePresentationPolicy {
    fun shouldClearOverlay(activeGeneration: Long?, failedGeneration: Long): Boolean =
        activeGeneration == failedGeneration
}
