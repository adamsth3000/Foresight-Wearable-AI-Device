package com.foresight.gateway.capture

import com.foresight.gateway.gopro.GoProSourceStatus
import com.foresight.gateway.metadata.CaptureSessionMetadata
import com.foresight.gateway.mode.GatewayOperatingMode
import com.foresight.gateway.transport.StreamLifecycle

/** Keeps the active FIELD GoPro session authoritative over phone-controller callbacks. */
internal object FieldGoProCaptureStateAuthority {
    fun acceptsControllerUpdate(
        operatingMode: GatewayOperatingMode,
        activeSource: LocalMediaSourceId?,
        activeSession: CaptureSessionMetadata?,
    ): Boolean =
        !(
            operatingMode == GatewayOperatingMode.FIELD &&
                activeSource == LocalMediaSourceId.GOPRO_RTMP &&
                activeSession != null
            )

    fun statusForControllerUpdate(
        authoritativeStatus: CaptureStatus,
        operatingMode: GatewayOperatingMode,
        activeSource: LocalMediaSourceId?,
        activeSession: CaptureSessionMetadata?,
        incomingLifecycle: StreamLifecycle,
        incomingSession: CaptureSessionMetadata?,
        incomingDetail: String?,
    ): CaptureStatus =
        if (acceptsControllerUpdate(operatingMode, activeSource, activeSession)) {
            CaptureStatus(incomingLifecycle, incomingSession, incomingDetail)
        } else {
            authoritativeStatus
        }

    fun statusForIngress(
        ingressStatus: GoProSourceStatus,
        activeSession: CaptureSessionMetadata?,
        eventActive: Boolean,
    ): CaptureStatus? {
        val session = activeSession ?: return null
        return when (ingressStatus) {
            GoProSourceStatus.LIVE -> CaptureStatus(
                lifecycle = StreamLifecycle.STREAMING,
                metadata = session,
                detail = if (eventActive) {
                    "FIELD GoPro event recording active"
                } else {
                    "FIELD GoPro capture ready for events"
                },
            )
            GoProSourceStatus.LOST,
            GoProSourceStatus.ERROR,
            -> CaptureStatus(
                lifecycle = StreamLifecycle.OFFLINE,
                metadata = session,
                detail = "FIELD GoPro capture active; ingest ${ingressStatus.name.lowercase()}",
            )
            else -> null
        }
    }

    fun stoppedStatus(): CaptureStatus = CaptureStatus(
        lifecycle = StreamLifecycle.IDLE,
        metadata = null,
        detail = "FIELD GoPro capture stopped; ingest remains active",
    )
}
