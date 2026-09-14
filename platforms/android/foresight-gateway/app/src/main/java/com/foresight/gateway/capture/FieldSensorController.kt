package com.foresight.gateway.capture

import com.foresight.gateway.metadata.CaptureSessionMetadata

/**
 * Narrow service-facing contract for FIELD sensor ownership.
 *
 * This is intentionally distinct from FieldSensorRuntime:
 * the service owns the support component, while the support component owns
 * the concrete Android sensor runtime.
 */
internal interface FieldSensorController {
    fun start(session: CaptureSessionMetadata)

    fun stop()
}
