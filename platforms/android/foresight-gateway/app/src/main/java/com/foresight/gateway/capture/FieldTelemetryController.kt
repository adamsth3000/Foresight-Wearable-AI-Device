package com.foresight.gateway.capture

import com.foresight.gateway.metadata.CaptureSessionMetadata

/**
 * Narrow service-facing contract for FIELD telemetry ownership.
 *
 * This is intentionally distinct from FieldTelemetryRuntime:
 * the service owns the support component, while the support component owns
 * the concrete telemetry runtime/client.
 */
internal interface FieldTelemetryController {
    fun start(session: CaptureSessionMetadata, endpoint: String)

    fun stop()

    fun client(): FieldTelemetryRuntime?
}
