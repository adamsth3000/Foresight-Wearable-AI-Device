package com.foresight.gateway.capture

import android.view.SurfaceView
import com.foresight.gateway.metadata.CaptureSessionMetadata
import com.foresight.gateway.telemetry.TelemetryClient
import java.time.Instant

/** Narrow service-facing contract for the existing phone capture controller. */
internal interface PhoneFieldMediaController {
    /** Listener capability used by service-owned FIELD telemetry. */
    val telemetryListener: TelemetryClient.Listener

    fun start(
        endpoint: String?,
        telemetryEndpoint: String,
        fieldSupportExternallyOwned: Boolean = false,
        sessionOverride: CaptureSessionMetadata? = null,
    )

    fun stop(fieldSupportExternallyOwned: Boolean = false)

    fun startFieldSupport(session: CaptureSessionMetadata, telemetryEndpoint: String)

    fun stopFieldSupport()

    fun attachPreview(surfaceView: SurfaceView)

    fun detachPreview(surfaceView: SurfaceView)

    fun authoritativeEventStarted(
        eventId: String,
        receiptUtc: Instant,
        receiptMonotonicMillis: Long,
    )

    fun authoritativeEventEnded(
        eventId: String,
        receiptUtc: Instant,
        receiptMonotonicMillis: Long,
    )

    fun startFieldEvent(callback: (Result<LocalEventMapping>) -> Unit)

    fun endFieldEvent(callback: (Result<LocalEventMapping>) -> Unit)

    fun activeFieldEvent(callback: (LocalEventMapping?) -> Unit)

    fun fieldEventReadiness(): FieldEventReadiness

    fun localMediaAvailability(): LocalMediaAvailability

    fun syncReadyEventMedia(
        eventId: String,
        controlEndpoint: String,
        callback: (EventMediaSyncUiState) -> Unit,
    )

    fun syncAllReadyEventMedia(
        controlEndpoint: String,
        callback: (eventId: String, state: EventMediaSyncUiState, completed: Int, total: Int) -> Unit,
    )

    fun eventMediaSyncState(eventId: String): EventMediaSyncState?

    fun eventMediaExtractionState(eventId: String): EventMediaExtractionState?

    fun latestSyncableEventId(): String?

    fun syncHistory(): List<EventMediaSyncHistoryEntry>

    fun syncSummary(): EventMediaSyncSummary

    fun syncableEventIds(): List<String>

    fun startDiagnostics(): String
}
