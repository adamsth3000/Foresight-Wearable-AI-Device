package com.foresight.gateway.vision

/**
 * Local-only detector boundary. Sampling, capture state, and UI presentation remain Activity-owned.
 */
internal interface OnDeviceVisionDetector : AutoCloseable {
    val backendIdentity: String
    val modelIdentity: String

    fun isReadyForFrame(): Boolean

    fun submit(
        frame: SampledPreviewFrame,
        onSnapshot: (DetectionSnapshot) -> Unit,
        onFailure: (Throwable) -> Unit,
    ): Boolean
}

/** Keeps the local detector newest-only without introducing an inference queue. */
internal class OnDeviceVisionInferenceGate {
    private var inferenceInFlight = false
    private var skippedSamples = 0L

    fun tryBegin(): Boolean {
        if (inferenceInFlight) {
            skippedSamples += 1
            return false
        }
        inferenceInFlight = true
        return true
    }

    fun complete() {
        inferenceInFlight = false
    }

    fun isInFlight(): Boolean = inferenceInFlight

    fun skippedSamples(): Long = skippedSamples
}
