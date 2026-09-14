package com.foresight.gateway.capture

import com.foresight.gateway.metadata.CaptureSessionMetadata

/**
 * Narrow service-owned seam for the existing pre-coordinator PHONE FIELD lifecycle.
 *
 * This class does not choose the FIELD media source and does not replace
 * FieldSessionCoordinator. It only guarantees exactly-once ownership of the
 * already-selected PHONE FIELD media/support lifecycle.
 */
internal class PhoneFieldServiceLifecycle(
    private val startMedia: (
        endpoint: String?,
        telemetryEndpoint: String,
        session: CaptureSessionMetadata,
    ) -> Unit,
    private val stopMedia: () -> Unit,
    private val telemetry: FieldTelemetryController,
    private val sensors: FieldSensorController,
) {
    private var active = false

    @Synchronized
    fun start(
        endpoint: String?,
        telemetryEndpoint: String,
        session: CaptureSessionMetadata,
    ): Boolean {
        if (active) return false

        telemetry.start(session, telemetryEndpoint)

        try {
            sensors.start(session)
        } catch (error: RuntimeException) {
            telemetry.stop()
            throw error
        }

        try {
            startMedia(endpoint, telemetryEndpoint, session)
        } catch (error: RuntimeException) {
            sensors.stop()
            telemetry.stop()
            throw error
        }

        active = true
        return true
    }

    /**
     * Stops PHONE media plus service-owned FIELD supports.
     *
     * If the media stop synchronously reports IDLE/ERROR back to the service,
     * [onMediaEnded] may run before [stopMedia] returns. The active guard makes
     * support cleanup idempotent in either ordering.
     */
    fun stop() {
        synchronized(this) {
            if (!active) return
        }

        stopMedia()
        stopSupports()
    }

    /** Releases only service-owned supports after media has already ended. */
    fun onMediaEnded() {
        stopSupports()
    }

    @Synchronized
    fun isActive(): Boolean = active

    private fun stopSupports() {
        synchronized(this) {
            if (!active) return
            active = false
        }

        sensors.stop()
        telemetry.stop()
    }
}
