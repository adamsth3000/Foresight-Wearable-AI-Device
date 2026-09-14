package com.foresight.gateway.capture

import android.content.Context
import android.util.Log
import android.view.SurfaceView
import com.foresight.gateway.metadata.CaptureSessionMetadata
import com.foresight.gateway.sensors.PhoneSensorCapture
import com.foresight.gateway.telemetry.TelemetryClient
import com.foresight.gateway.transport.RtspPublisher
import com.foresight.gateway.transport.StreamLifecycle
import org.json.JSONObject
import java.time.Instant
import java.io.File
import java.util.UUID

/** Coordinates a source-neutral phone capture session without owning Android UI. */
internal class PhoneCaptureController(
    context: Context,
    private val listener: Listener,
    private val recordingRepository: LocalRecordingMetadataRepository = LocalRecordingMetadataRepository(
        metadataDirectory = File(context.applicationContext.filesDir, "recording_metadata"),
        recordingsDirectory = File(context.applicationContext.filesDir, "recordings"),
        mediaDirectories = mapOf(
            LocalMediaSourceId.PHONE_CAMERA to File(context.applicationContext.filesDir, "recordings"),
            LocalMediaSourceId.GOPRO_RTMP to File(context.applicationContext.filesDir, "gopro_ingest_recordings"),
        ),
    ),
    private val worker: CaptureWorker = HandlerCaptureWorker(),
    private val publisherFactory: PhonePublisherRuntimeFactory = defaultPhonePublisherRuntimeFactory,
    private val eventMediaExtractor: LocalEventMediaExtractor = LocalEventMediaExtractor(context.applicationContext, recordingRepository),
) : PhoneFieldMediaController, RtspPublisher.Listener, TelemetryClient.Listener {
    interface Listener {
        fun onCaptureStateChanged(
            lifecycle: StreamLifecycle,
            metadata: CaptureSessionMetadata?,
            detail: String? = null,
        )
    }

    override val telemetryListener: TelemetryClient.Listener
        get() = this

    private val applicationContext = context.applicationContext
    // All RootEncoder and recorder ownership mutations are serialized away from the UI looper.
    private val captureWorker = worker
    private var telemetry: TelemetryClient? = null
    private var sensors: PhoneSensorCapture? = null
    private val publisher = publisherFactory(context, this, worker)
    private val phoneMediaSource = PhoneLocalMediaSource(publisher)
    private val eventMediaSyncClient = EventMediaSyncClient(recordingRepository)
    private val state = CaptureControllerState()
    private val eventMapper = LocalRecordingEventMapper()
    @Volatile
    private var activeSession: CaptureSessionMetadata? = null
    @Volatile
    private var transportLifecycle = StreamLifecycle.IDLE
    @Volatile
    private var cameraTimestampSource: String? = null
    @Volatile
    private var fieldEventReadiness = FieldEventReadiness.NOT_CAPTURING

    init {
        eventMediaExtractor.enqueueRecoverableEvents()
    }

    override fun start(
        endpoint: String?,
        telemetryEndpoint: String,
        fieldSupportExternallyOwned: Boolean,
        sessionOverride: CaptureSessionMetadata?,
    ) {
        captureWorker.execute { startOnCaptureWorker(endpoint, telemetryEndpoint, fieldSupportExternallyOwned, sessionOverride) }
    }

    private fun startOnCaptureWorker(endpoint: String?, telemetryEndpoint: String, fieldSupportExternallyOwned: Boolean, sessionOverride: CaptureSessionMetadata?): Boolean {
        require(endpoint.isNullOrBlank() || endpoint.startsWith("rtsp://")) { "The endpoint must use rtsp://" }
        val session: CaptureSessionMetadata
        synchronized(this) {
            val rejection = state.startRejectionReason()
            Log.i(
                TAG,
                "Capture start requested: controllerState=${state.lifecycle}, " +
                    "hasActiveSession=${state.hasActiveSession}, dispatchInFlight=${state.startDispatchInFlight}, " +
                    "publisherGeneration=${publisher.generation()}, publisherState=${publisher.lifecycle()}, endpoint=$endpoint",
            )
            if (rejection != null) {
                Log.w(TAG, "Capture start rejected: $rejection")
                listener.onCaptureStateChanged(state.lifecycle, activeSession, "Capture start rejected: $rejection")
                return false
            }
            state.beginStartDispatch()
            // This is provisional until the publisher synchronously reports PREPARING.
            session = sessionOverride ?: CaptureSessionMetadata(streamEndpoint = endpoint.orEmpty())
            activeSession = session
            Log.i(TAG, "Capture start dispatch accepted: generation=${publisher.generation()}.")
        }

        val publisherAccepted = try {
            Log.i(TAG, "Publisher.start invocation beginning: generation=${publisher.generation()}.")
            publisher.start(endpoint, session.sourceSessionId).also { accepted ->
                Log.i(TAG, "Publisher.start invocation returned: generation=${publisher.generation()}, accepted=$accepted.")
            }
        } catch (error: RuntimeException) {
            Log.e(
                TAG,
                "Capture start failed before publisher acceptance: generation=${publisher.generation()}, " +
                    "exception=${error.javaClass.simpleName}: ${error.message}",
                error,
            )
            rollbackStart("Publisher start threw ${error.javaClass.simpleName}.", fieldSupportExternallyOwned)
            return false
        }
        if (!publisherAccepted) {
            rollbackStart("Publisher rejected the capture start.", fieldSupportExternallyOwned)
            return false
        }

        val publisherPrepared = synchronized(this) { state.hasActiveSession }
        if (!publisherPrepared) {
            // RootEncoder currently reports PREPARING synchronously. Retain no ownership if a
            // future implementation accepts a start without publishing that lifecycle fact.
            rollbackStart("Publisher accepted start without entering PREPARING.", fieldSupportExternallyOwned)
            return false
        }

        if (!fieldSupportExternallyOwned) startFieldSupport(session, telemetryEndpoint)
        return true
    }

    /** Starts phone sensors and telemetry for either FIELD media source. */
    override fun startFieldSupport(session: CaptureSessionMetadata, telemetryEndpoint: String) {
        captureWorker.execute {
            activeSession = session
            val telemetryClient = newTelemetryClient()
            telemetry = telemetryClient
            sensors = newSensorCapture(telemetryClient)
            try {
                Log.i(TAG, "FIELD telemetry and sensor startup beginning.")
                telemetryClient.start(session, telemetryEndpoint)
                cameraTimestampSource?.let(::enqueueCameraTimingCapability)
                sensors?.start()
            } catch (error: RuntimeException) {
                Log.w(TAG, "FIELD telemetry or sensor startup failed.", error)
                listener.onCaptureStateChanged(transportLifecycle, activeSession, "Telemetry unavailable: ${error.message}")
            }
        }
    }

    /** Stops side-channel capture without touching the selected media source. */
    override fun stopFieldSupport() {
        captureWorker.execute {
            sensors?.stop()
            telemetry?.stop()
        }
    }

    @Synchronized
    private fun rollbackStart(detail: String, fieldSupportExternallyOwned: Boolean) {
        if (!fieldSupportExternallyOwned) {
            sensors?.stop()
            telemetry?.stop()
        }
        activeSession = null
        state.rollbackStartDispatch()
        transportLifecycle = StreamLifecycle.IDLE
        Log.w(TAG, "Capture start rolled back: $detail")
        listener.onCaptureStateChanged(StreamLifecycle.IDLE, null, detail)
    }

    private fun newTelemetryClient(): TelemetryClient = TelemetryClient(this)

    private fun newSensorCapture(client: TelemetryClient): PhoneSensorCapture =
        PhoneSensorCapture(applicationContext, client) { detail ->
            listener.onCaptureStateChanged(transportLifecycle, activeSession, detail)
        }

    override fun stop(fieldSupportExternallyOwned: Boolean) {
        captureWorker.execute { stopOnCaptureWorker(fieldSupportExternallyOwned) }
    }

    private fun stopOnCaptureWorker(fieldSupportExternallyOwned: Boolean) {
        if (state.lifecycle == StreamLifecycle.IDLE && !state.hasActiveSession) {
            Log.i(TAG, "Capture stop ignored: controller is already IDLE.")
            return
        }
        Log.i(TAG, "Capture stop requested: controllerState=${state.lifecycle}, publisherGeneration=${publisher.generation()}.")
        completeActiveFieldEventForCaptureStop()
        if (!fieldSupportExternallyOwned) {
            sensors?.stop()
            telemetry?.stop()
        }
        publisher.stop()
    }

    override fun attachPreview(surfaceView: SurfaceView) {
        captureWorker.execute { publisher.attachPreview(surfaceView) }
    }

    override fun detachPreview(surfaceView: SurfaceView) {
        captureWorker.execute { publisher.detachPreview(surfaceView) }
    }

    override fun authoritativeEventStarted(eventId: String, receiptUtc: Instant, receiptMonotonicMillis: Long) {
        captureWorker.execute {
            runCatching {
                val context = requireNotNull(phoneMediaSource.currentRecordingContext()) { "no active local recording" }
                val boundary = eventMapper.start(eventId, context, receiptUtc, receiptMonotonicMillis)
                recordingRepository.recordAuthoritativeStart(boundary)
                Log.i(TAG, "Authoritative event START received: eventId=$eventId recordingId=${boundary.recordingId} sourceSessionId=${context.sourceSessionId} receiptUtc=$receiptUtc receiptMonotonicMs=$receiptMonotonicMillis recordingOffsetMs=${boundary.recordingOffsetMillis}")
            }.onFailure { Log.w(TAG, "Authoritative event START rejected: ${it.message}") }
        }
    }

    override fun authoritativeEventEnded(eventId: String, receiptUtc: Instant, receiptMonotonicMillis: Long) {
        captureWorker.execute {
            runCatching {
                val (start, end) = eventMapper.end(eventId, requireNotNull(phoneMediaSource.currentRecordingContext()) { "no active local recording" }, receiptUtc, receiptMonotonicMillis)
                recordingRepository.recordAuthoritativeEnd(start, end)
                Log.i(TAG, "Authoritative event END received: eventId=$eventId recordingId=${end.recordingId} receiptUtc=$receiptUtc receiptMonotonicMs=$receiptMonotonicMillis recordingOffsetMs=${end.recordingOffsetMillis}")
            }.onFailure { Log.w(TAG, "Authoritative event END rejected: ${it.message}") }
        }
    }

    override fun startFieldEvent(callback: (Result<LocalEventMapping>) -> Unit) {
        captureWorker.execute {
            val result = runCatching {
                val context = requireNotNull(phoneMediaSource.currentRecordingContext()) { "no active local recording" }
                val boundary = eventMapper.boundary(
                    UUID.randomUUID().toString(),
                    context,
                    Instant.now(),
                    android.os.SystemClock.elapsedRealtime(),
                )
                val mapping = recordingRepository.recordFieldStart(boundary)
                refreshFieldEventReadiness()
                mapping
            }
            result.onSuccess { event ->
                Log.i(TAG, "FIELD event START persisted: eventId=${event.eventId} recordingId=${event.recordingId} offsetMs=${event.startOffsetMillis}")
            }.onFailure { error ->
                Log.w(TAG, "FIELD event START rejected: ${error.message}", error)
            }
            callback(result)
        }
    }

    override fun endFieldEvent(callback: (Result<LocalEventMapping>) -> Unit) {
        captureWorker.execute {
            val result = runCatching {
                val active = requireNotNull(recordingRepository.activeFieldEvent()) { "no active FIELD event" }
                val context = requireNotNull(phoneMediaSource.currentRecordingContext()) { "no active local recording" }
                val end = eventMapper.boundary(
                    active.eventId,
                    context,
                    Instant.now(),
                    android.os.SystemClock.elapsedRealtime(),
                )
                val mapping = recordingRepository.completeFieldEvent(
                    active.eventId,
                    end,
                    LocalEventTerminationReason.USER_END,
                )
                refreshFieldEventReadiness()
                mapping
            }
            result.onSuccess { event ->
                Log.i(TAG, "FIELD event END persisted: eventId=${event.eventId} durationMs=${event.durationMillis}")
            }.onFailure { error ->
                Log.w(TAG, "FIELD event END rejected: ${error.message}", error)
            }
            callback(result)
        }
    }

    override fun activeFieldEvent(callback: (LocalEventMapping?) -> Unit) {
        captureWorker.execute { callback(recordingRepository.activeFieldEvent()) }
    }

    override fun fieldEventReadiness(): FieldEventReadiness = fieldEventReadiness

    override fun localMediaAvailability(): LocalMediaAvailability = phoneMediaSource.availability()

    private fun refreshFieldEventReadiness() {
        val sessionActive = state.hasActiveSession
        val context = phoneMediaSource.currentRecordingContext()
        val recordingReady = sessionActive && context?.isRecording == true &&
            recordingRepository.snapshot().recordings.containsKey(context.recordingId)
        val active = recordingRepository.activeFieldEvent() != null
        fieldEventReadiness = when {
            !sessionActive -> FieldEventReadiness.NOT_CAPTURING
            !recordingReady -> FieldEventReadiness(true, false, active, "waiting for local recording")
            active -> FieldEventReadiness(true, true, true, "event already active")
            else -> FieldEventReadiness(true, true, false, "READY")
        }
    }

    private fun completeActiveFieldEventForCaptureStop() {
        val active = recordingRepository.activeFieldEvent() ?: return
        runCatching {
            val context = requireNotNull(phoneMediaSource.currentRecordingContext()) { "no active local recording" }
            val end = eventMapper.boundary(
                active.eventId,
                context,
                Instant.now(),
                android.os.SystemClock.elapsedRealtime(),
            )
            val mapping = recordingRepository.completeFieldEvent(
                active.eventId,
                end,
                LocalEventTerminationReason.CAPTURE_STOP,
            )
            refreshFieldEventReadiness()
            mapping
        }.onSuccess { event ->
            Log.i(TAG, "FIELD event closed for capture stop: eventId=${event.eventId} durationMs=${event.durationMillis}")
        }.onFailure { error ->
            Log.e(TAG, "Unable to close FIELD event for capture stop: eventId=${active.eventId}", error)
        }
    }

    override fun syncReadyEventMedia(
        eventId: String,
        controlEndpoint: String,
        callback: (EventMediaSyncUiState) -> Unit,
    ) {
        eventMediaSyncClient.sync(eventId, controlEndpoint, callback)
    }

    override fun syncAllReadyEventMedia(
        controlEndpoint: String,
        callback: (eventId: String, state: EventMediaSyncUiState, completed: Int, total: Int) -> Unit,
    ) {
        eventMediaSyncClient.syncAll(recordingRepository.syncableEventIds(), controlEndpoint, callback)
    }

    override fun eventMediaSyncState(eventId: String): EventMediaSyncState? =
        recordingRepository.eventMediaSyncState(eventId)

    override fun eventMediaExtractionState(eventId: String): EventMediaExtractionState? =
        recordingRepository.eventMediaExtractionState(eventId)

    override fun latestSyncableEventId(): String? = recordingRepository.latestSyncableEventId()

    override fun syncHistory(): List<EventMediaSyncHistoryEntry> = recordingRepository.syncHistory()

    override fun syncSummary(): EventMediaSyncSummary = recordingRepository.syncSummary()

    override fun syncableEventIds(): List<String> = recordingRepository.syncableEventIds()

    override fun onLifecycleChanged(lifecycle: StreamLifecycle, detail: String?) {
        // Clear ownership before publishing IDLE so a UI-enabled second START cannot observe
        // a stale controller session while the replacement publisher is already idle.
        state.publisherLifecycleChanged(lifecycle)
        transportLifecycle = lifecycle
        if (lifecycle == StreamLifecycle.STREAMING) {
            activeSession = activeSession?.copy(streamStartedUtc = Instant.now())
        }
        if (lifecycle == StreamLifecycle.IDLE || lifecycle == StreamLifecycle.ERROR) {
            activeSession = null
        }
        refreshFieldEventReadiness()
        Log.i(
            TAG,
            "Publisher lifecycle accepted: lifecycle=$lifecycle, controllerState=${state.lifecycle}, " +
                "hasActiveSession=${state.hasActiveSession}, publisherGeneration=${publisher.generation()}, " +
                "publisherState=${publisher.lifecycle()}.",
        )
        listener.onCaptureStateChanged(lifecycle, activeSession, detail)
    }

    override fun onBitrateChanged(bitsPerSecond: Long) {
        if (transportLifecycle != StreamLifecycle.STREAMING) return
        listener.onCaptureStateChanged(
            StreamLifecycle.STREAMING,
            activeSession,
            "${bitsPerSecond / 1_000} kbps",
        )
    }

    override fun onTelemetryBound(captureSessionId: String) {
        activeSession = activeSession?.copy(captureSessionId = captureSessionId)
        listener.onCaptureStateChanged(
            transportLifecycle,
            activeSession,
            "Telemetry bound to capture session $captureSessionId",
        )
    }

    override fun onTelemetryStatus(detail: String) {
        listener.onCaptureStateChanged(transportLifecycle, activeSession, detail)
    }

    override fun onCameraTimingCapability(timestampSource: String) {
        cameraTimestampSource = timestampSource
        enqueueCameraTimingCapability(timestampSource)
    }

    private fun enqueueCameraTimingCapability(timestampSource: String) {
        telemetry?.enqueue(JSONObject().apply {
            put("record_type", "camera_timing_capability")
            put("timestamp_elapsed_realtime_nanos", android.os.SystemClock.elapsedRealtimeNanos())
            put("sensor_timestamp_source", timestampSource)
            put("encoded_pts_mapping", "not_available_from_rootencoder")
        })
    }

    override fun onCameraFrameCaptured(frameNumber: Long, timestampNanos: Long) {
        // RootEncoder exposes Camera2 exposure timestamps, but not encoded RTP/PTS mapping.
        // Rate-limit timing observations so camera callbacks cannot pressure telemetry transport.
        if (frameNumber % 30L != 0L) return
        telemetry?.enqueue(JSONObject().apply {
            put("record_type", "camera_frame_timing")
            put("timestamp_elapsed_realtime_nanos", timestampNanos)
            put("frame_number", frameNumber)
            put("timestamp_semantics", "camera_capture_start_exposure")
        })
    }

    override fun onLocalRecordingStarted(context: LocalRecordingContext) {
        runCatching { recordingRepository.createRecording(context) }
            .onSuccess {
                Log.i(TAG, "Persisted local recording creation: recordingId=${context.recordingId} file=${context.localMediaFileName}")
            }
            .onFailure { error ->
                Log.e(TAG, "Unable to persist local recording creation: ${context.recordingId}", error)
            }
        refreshFieldEventReadiness()
    }

    override fun onLocalRecordingFinalized(context: LocalRecordingContext, stopUtc: Instant) {
        runCatching { recordingRepository.finalizeRecording(context, stopUtc) }
            .onSuccess { metadata ->
                Log.i(
                    TAG,
                    "Persisted local recording finalization: recordingId=${metadata.recordingId} " +
                        "bytes=${metadata.byteSize} sha256=${metadata.sha256}",
                )
                eventMediaExtractor.enqueueReadyEventsForRecording(metadata.recordingId)
            }
            .onFailure { error ->
                Log.e(TAG, "Unable to persist local recording finalization: ${context.recordingId}", error)
                runCatching {
                    recordingRepository.markRecordingInterrupted(
                        context.recordingId,
                        "final metadata persistence failed: ${error.message}",
                    )
                }.onFailure { markError ->
                    Log.e(TAG, "Unable to persist local recording interruption: ${context.recordingId}", markError)
                }
            }
        refreshFieldEventReadiness()
    }

    override fun onLocalRecordingInterrupted(context: LocalRecordingContext, detail: String) {
        runCatching { recordingRepository.markRecordingInterrupted(context.recordingId, detail) }
            .onSuccess {
                Log.w(TAG, "Persisted interrupted local recording: recordingId=${context.recordingId}; $detail")
            }
            .onFailure { error ->
                Log.e(TAG, "Unable to persist interrupted local recording: ${context.recordingId}", error)
            }
        refreshFieldEventReadiness()
    }

    override fun startDiagnostics(): String =
        "controllerState=${state.lifecycle}, hasActiveSession=${state.hasActiveSession}, " +
            "dispatchInFlight=${state.startDispatchInFlight}, " +
            "publisherGeneration=${publisher.generation()}, publisherState=${publisher.lifecycle()}"

    companion object {
        private const val TAG = "PhoneCaptureController"
    }
}
