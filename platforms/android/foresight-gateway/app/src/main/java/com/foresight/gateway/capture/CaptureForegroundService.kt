package com.foresight.gateway.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.SurfaceView
import android.view.Surface
import com.foresight.gateway.R
import com.foresight.gateway.gopro.GoProIngressSnapshot
import com.foresight.gateway.gopro.GoProLanAddressProvider
import com.foresight.gateway.gopro.GoProNetworkMode
import com.foresight.gateway.gopro.GoProNetworkDiagnostic
import com.foresight.gateway.gopro.GoProRtmpIngress
import com.foresight.gateway.gopro.GoProRecordingDiagnostics
import com.foresight.gateway.gopro.GoProSourceStatus
import com.foresight.gateway.metadata.CaptureSessionMetadata
import com.foresight.gateway.mode.GatewayOperatingMode
import com.foresight.gateway.transport.StreamLifecycle

/** User-started foreground service that keeps camera and microphone capture visible. */
class CaptureForegroundService : Service(), PhoneCaptureController.Listener, GoProRtmpIngress.Listener {
    private lateinit var controller: PhoneFieldMediaController
    private lateinit var goProIngress: GoProRtmpIngress
    private lateinit var recordingRepository: LocalRecordingMetadataRepository
    private lateinit var fieldTelemetry: FieldTelemetryController
    private lateinit var fieldSensors: FieldSensorController
    private lateinit var fieldCoordinator: FieldSessionCoordinator
    private val dependencies = CaptureServiceDependencies()
    @Volatile
    private var authoritativeEventState: String = "idle"
    @Volatile
    private var operatingMode = GatewayOperatingMode.LAB
    @Volatile
    private var activeFieldMediaSource: LocalMediaSourceId? = null
    @Volatile
    private var activeFieldSession: CaptureSessionMetadata? = null
    @Volatile
    private var activeGoProFieldEvent: LocalEventMapping? = null
    @Volatile
    private var goProNetworkMode = GoProNetworkMode.NORMAL_LAN

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        recordingRepository = LocalRecordingMetadataRepository(
            metadataDirectory = java.io.File(filesDir, "recording_metadata"),
            recordingsDirectory = java.io.File(filesDir, "recordings"),
            mediaDirectories = mapOf(
                LocalMediaSourceId.PHONE_CAMERA to java.io.File(filesDir, "recordings"),
                LocalMediaSourceId.GOPRO_RTMP to java.io.File(filesDir, "gopro_ingest_recordings"),
            ),
            logger = LocalRecordingRepositoryLogger { message, error ->
                if (error == null) Log.w(TAG, message) else Log.w(TAG, message, error)
            },
        )
        controller = dependencies.controller(this, this, recordingRepository)
        fieldTelemetry = dependencies.telemetry(controller)
        fieldSensors = dependencies.sensors(
            context = this,
            telemetry = fieldTelemetry::client,
            status = { detail -> onCaptureStateChanged(currentStatus.lifecycle, currentStatus.metadata, detail) },
        )
        goProIngress = GoProRtmpIngress(
            listener = this,
            addressProvider = {
                val selectedAddress = GoProLanAddressProvider.discover(this, goProNetworkMode)
                GoProNetworkDiagnostic.log(
                    context = this,
                    selectedAddress = selectedAddress,
                    port = GoProRtmpIngress.DEFAULT_PORT,
                    path = GoProRtmpIngress.DEFAULT_PATH,
                )
                selectedAddress
            },
            recordingDirectory = java.io.File(filesDir, "gopro_ingest_recordings"),
            recordingMetadataRepository = recordingRepository,
        )
        fieldCoordinator = FieldSessionCoordinator(
            phone = PhoneFieldMediaAdapter(controller),
            goPro = GoProFieldMediaAdapter(goProIngress),
            sensors = fieldSensors,
            telemetry = fieldTelemetry,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startAsForeground("Preparing capture")
                val mode = intent.operatingMode()
                operatingMode = mode
                val endpoint = intent.captureEndpoint(mode)
                val selectedSource = intent.fieldMediaSource()
                if (mode == GatewayOperatingMode.FIELD && activeFieldSession != null) {
                    Log.w(
                        TAG,
                        "FIELD start rejected; active FIELD session already exists for source=$activeFieldMediaSource",
                    )
                    return START_NOT_STICKY
                }
                Log.i(
                    TAG,
                    "Capture start requested: mode=$mode rtsp=${endpoint ?: "not configured"}; ${controller.startDiagnostics()}; " +
                        "${captureStartDispatchDetail(mode, selectedSource)}.",
                )
                if (mode == GatewayOperatingMode.FIELD && selectedSource == LocalMediaSourceId.GOPRO_RTMP) {
                    logFieldGoProStartSnapshot("FIELD_GOPRO_START_BEFORE", selectedSource)
                    startGoProFieldCapture(intent.telemetryEndpoint())
                } else {
                    activeFieldMediaSource = if (mode == GatewayOperatingMode.FIELD) LocalMediaSourceId.PHONE_CAMERA else null
                    val fieldSession = if (mode == GatewayOperatingMode.FIELD) {
                        CaptureSessionMetadata(streamEndpoint = endpoint.orEmpty())
                    } else {
                        null
                    }
                    if (fieldSession != null) {
                        activeFieldSession = fieldSession
                        fieldCoordinator.start(
                            source = LocalMediaSourceId.PHONE_CAMERA,
                            session = fieldSession,
                            telemetryEndpoint = intent.telemetryEndpoint(),
                        )
                    } else {
                        // LAB retains the existing bundled PhoneCaptureController behavior.
                        controller.start(
                            endpoint = endpoint,
                            telemetryEndpoint = intent.telemetryEndpoint(),
                            fieldSupportExternallyOwned = false,
                            sessionOverride = null,
                        )
                    }
                }
            }

            ACTION_STOP -> {
                if (operatingMode == GatewayOperatingMode.LAB && CaptureEventInterlock.blocksCaptureStop(authoritativeEventState)) {
                    onCaptureStateChanged(
                        currentStatus.lifecycle,
                        currentStatus.metadata,
                        "End the active event before stopping capture.",
                    )
                } else {
                    stopSelectedCapture()
                }
            }

            ACTION_START_GOPRO_INGRESS -> {
                goProNetworkMode = intent.goProNetworkMode()
                startGoProAsForeground("Starting GoPro RTMP ingest")
                goProIngress.start()
            }

            ACTION_STOP_GOPRO_INGRESS -> {
                goProIngress.stop()
                if (currentStatus.lifecycle == StreamLifecycle.IDLE) removeForegroundIfUnused()
            }
        }
        return START_NOT_STICKY
    }

    private fun startGoProFieldCapture(telemetryEndpoint: String) {
        if (currentGoProStatus.status != GoProSourceStatus.LIVE) {
            Log.w(TAG, "FIELD GoPro capture rejected; GoPro ingest must already be LIVE.")
            currentStatus = CaptureStatus(
                StreamLifecycle.IDLE,
                null,
                "Start GoPro ingest and wait for LIVE before starting capture.",
            )
            return
        }

        val session = CaptureSessionMetadata(streamEndpoint = "rtmp://local/gopro")
        startAsForeground("Starting FIELD GoPro capture")

        try {
            val started = fieldCoordinator.start(
                source = LocalMediaSourceId.GOPRO_RTMP,
                session = session,
                telemetryEndpoint = telemetryEndpoint,
            )
            check(started) { "FIELD coordinator already owns another source." }

            activeFieldMediaSource = LocalMediaSourceId.GOPRO_RTMP
            activeFieldSession = session
            currentStatus = CaptureStatus(
                StreamLifecycle.STREAMING,
                session,
                "FIELD GoPro capture ready for events",
            )
            logFieldGoProStartSnapshot("FIELD_GOPRO_START_AFTER", LocalMediaSourceId.GOPRO_RTMP)
            notificationManager().notify(
                NOTIFICATION_ID,
                buildNotification("FIELD GoPro capture ready for events"),
            )
            Log.i(TAG, "FIELD GoPro capture started through FieldSessionCoordinator.")
        } catch (error: RuntimeException) {
            Log.e(TAG, "FIELD GoPro capture failed to start: ${error.message}", error)
            activeGoProFieldEvent = null
            activeFieldMediaSource = null
            activeFieldSession = null
            currentStatus = CaptureStatus(
                StreamLifecycle.IDLE,
                null,
                "FIELD GoPro capture failed: ${error.message ?: "unknown error"}",
            )
        }
    }

    private fun captureStartDispatchDetail(
        mode: GatewayOperatingMode,
        selectedSource: LocalMediaSourceId,
    ): String =
        if (mode == GatewayOperatingMode.FIELD) {
            "dispatching FIELD capture start; mediaSource=$selectedSource"
        } else {
            "dispatching LAB PhoneCaptureController.start()"
        }

    private fun logFieldGoProStartSnapshot(event: String, configuredSource: LocalMediaSourceId) {
        val preview = currentGoProStatus.previewDiagnostics
        val nowElapsedMs = android.os.SystemClock.elapsedRealtime()
        val msSinceLastRendered = preview?.lastRenderedElapsedMs?.let { nowElapsedMs - it }
        Log.i(
            TAG,
            "$event status=${currentStatus.lifecycle} statusDetail=${currentStatus.detail} " +
                "configuredSource=$configuredSource activeSource=$activeFieldMediaSource " +
                "activeSession=${activeFieldSession != null} ingress=${currentGoProStatus.status} " +
                "previewState=${preview?.state} previewSurface=${preview?.surfaceIdentity} " +
                "decoderGeneration=${preview?.generationId} received=${preview?.videoAccessUnitsReceived} " +
                "rendered=${preview?.outputBuffersRendered} lastInputPts=${preview?.lastInputPtsUs} " +
                "msSinceLastRendered=$msSinceLastRendered elapsedMs=$nowElapsedMs",
        )
    }

    private fun stopSelectedCapture() {
        if (activeFieldMediaSource == LocalMediaSourceId.GOPRO_RTMP) {
            if (activeGoProFieldEvent != null) {
                runCatching { endGoProFieldEvent(LocalEventTerminationReason.CAPTURE_STOP) }
                    .onFailure { error ->
                        Log.w(TAG, "Active GoPro FIELD event could not finalize during capture stop: ${error.message}")
                        runCatching { goProIngress.stopRecording() }
                        activeGoProFieldEvent = null
                    }
            }

            fieldCoordinator.end()
            activeGoProFieldEvent = null
            activeFieldMediaSource = null
            activeFieldSession = null
            currentStatus = FieldGoProCaptureStateAuthority.stoppedStatus()
            removeForegroundIfUnused()
            return
        }
        if (activeFieldMediaSource == LocalMediaSourceId.PHONE_CAMERA) {
            fieldCoordinator.end()
        } else {
            controller.stop(fieldSupportExternallyOwned = false)
        }
        activeFieldMediaSource = null
        activeFieldSession = null
    }

    override fun onDestroy() {
        Log.i(TAG, "Service destroyed; requesting controller shutdown if it is still active.")
        if (activeFieldMediaSource != null) {
            fieldCoordinator.end()
        } else {
            controller.stop(fieldSupportExternallyOwned = false)
        }
        activeGoProFieldEvent = null
        activeFieldMediaSource = null
        activeFieldSession = null
        goProIngress.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = CaptureBinder()

    inner class CaptureBinder : android.os.Binder() {
        fun attachPreview(surfaceView: SurfaceView) {
            controller.attachPreview(surfaceView)
        }

        fun detachPreview(surfaceView: SurfaceView) {
            controller.detachPreview(surfaceView)
        }

        fun attachGoProPreviewSurface(surface: Surface) {
            goProIngress.attachPreviewSurface(surface)
        }

        fun detachGoProPreviewSurface(surface: Surface? = null) {
            goProIngress.detachPreviewSurface(surface)
        }

        fun updateEventState(state: String) {
            authoritativeEventState = state
        }

        fun onAuthoritativeEventStarted(eventId: String, receiptUtc: java.time.Instant, receiptMonotonicMillis: Long) {
            runCatching { controller.authoritativeEventStarted(eventId, receiptUtc, receiptMonotonicMillis) }
                .onFailure { Log.w(TAG, "Authoritative event START rejected: ${it.message}") }
        }

        fun onAuthoritativeEventEnded(eventId: String, receiptUtc: java.time.Instant, receiptMonotonicMillis: Long) {
            runCatching { controller.authoritativeEventEnded(eventId, receiptUtc, receiptMonotonicMillis) }
                .onFailure { Log.w(TAG, "Authoritative event END rejected: ${it.message}") }
        }

        internal fun startFieldEvent(callback: (Result<LocalEventMapping>) -> Unit) {
            if (activeFieldMediaSource == LocalMediaSourceId.GOPRO_RTMP) {
                callback(runCatching { startGoProFieldEvent() })
            } else {
                controller.startFieldEvent(callback)
            }
        }

        internal fun endFieldEvent(callback: (Result<LocalEventMapping>) -> Unit) {
            if (activeFieldMediaSource == LocalMediaSourceId.GOPRO_RTMP) {
                callback(runCatching { endGoProFieldEvent(LocalEventTerminationReason.USER_END) })
            } else {
                controller.endFieldEvent(callback)
            }
        }

        internal fun activeFieldEvent(callback: (LocalEventMapping?) -> Unit) {
            if (activeFieldMediaSource == LocalMediaSourceId.GOPRO_RTMP) {
                callback(activeGoProFieldEvent)
            } else {
                controller.activeFieldEvent(callback)
            }
        }

        internal fun fieldEventReadiness(): FieldEventReadiness {
            if (activeFieldMediaSource != LocalMediaSourceId.GOPRO_RTMP) {
                return controller.fieldEventReadiness()
            }

            val eventActive = activeGoProFieldEvent != null
            return when {
                activeFieldSession == null ->
                    FieldEventReadiness(true, false, false, "Start FIELD capture first")
                currentGoProStatus.status != GoProSourceStatus.LIVE ->
                    FieldEventReadiness(true, false, eventActive, "GoPro ingest is not LIVE")
                eventActive ->
                    FieldEventReadiness(true, true, true, "GoPro event recording active")
                else ->
                    FieldEventReadiness(true, true, false, "READY")
            }
        }

        internal fun activeFieldMediaSource(): LocalMediaSourceId? = activeFieldMediaSource

        fun syncReadyEventMedia(
            eventId: String,
            controlEndpoint: String,
            callback: (EventMediaSyncUiState) -> Unit,
        ) {
            controller.syncReadyEventMedia(eventId, controlEndpoint, callback)
        }

        fun syncAllReadyEventMedia(
            controlEndpoint: String,
            callback: (eventId: String, state: EventMediaSyncUiState, completed: Int, total: Int) -> Unit,
        ) {
            controller.syncAllReadyEventMedia(controlEndpoint, callback)
        }

        fun eventMediaSyncState(eventId: String): EventMediaSyncState? =
            controller.eventMediaSyncState(eventId)

        internal fun eventMediaExtractionState(eventId: String): EventMediaExtractionState? =
            controller.eventMediaExtractionState(eventId)

        fun latestSyncableEventId(): String? = controller.latestSyncableEventId()

        internal fun syncHistory(): List<EventMediaSyncHistoryEntry> = controller.syncHistory()

        internal fun syncSummary(): EventMediaSyncSummary = controller.syncSummary()

        internal fun syncableEventIds(): List<String> = controller.syncableEventIds()

        fun startGoProIngress(): GoProIngressSnapshot = goProIngress.start()

        fun stopGoProIngress(): GoProIngressSnapshot = goProIngress.stop()

        fun goProIngressSnapshot(): GoProIngressSnapshot = goProIngress.snapshot()

        fun startGoProRecording(): GoProRecordingDiagnostics {
            check(fieldCoordinator.allowsDiagnosticGoProRecording()) {
                "GoPro recording is owned by the active FIELD capture."
            }
            return goProIngress.startRecording()
        }

        fun stopGoProRecording(): GoProRecordingDiagnostics {
            check(fieldCoordinator.allowsDiagnosticGoProRecording()) {
                "GoPro recording is owned by the active FIELD capture. End FIELD capture instead."
            }
            return goProIngress.stopRecording()
        }
    }

    override fun onCaptureStateChanged(
        lifecycle: StreamLifecycle,
        metadata: CaptureSessionMetadata?,
        detail: String?,
    ) {
        if (
            !FieldGoProCaptureStateAuthority.acceptsControllerUpdate(
                operatingMode = operatingMode,
                activeSource = activeFieldMediaSource,
                activeSession = activeFieldSession,
            )
        ) {
            Log.i(
                TAG,
                "CONTROLLER_STATUS_IGNORED incomingLifecycle=$lifecycle " +
                    "incomingSessionId=${metadata?.sourceSessionId} " +
                    "activeFieldSource=$activeFieldMediaSource " +
                    "activeFieldSessionId=${activeFieldSession?.sourceSessionId} " +
                    "authoritativeLifecycle=${currentStatus.lifecycle} " +
                    "authoritativeSessionId=${currentStatus.metadata?.sourceSessionId}",
            )
            return
        }
        Log.i(
            TAG,
            "CONTROLLER_STATUS_ACCEPTED incomingLifecycle=$lifecycle " +
                "incomingSessionId=${metadata?.sourceSessionId} " +
                "activeFieldSource=$activeFieldMediaSource " +
                "activeFieldSessionId=${activeFieldSession?.sourceSessionId} " +
                "previousLifecycle=${currentStatus.lifecycle} " +
                "previousSessionId=${currentStatus.metadata?.sourceSessionId}",
        )
        if (
            activeFieldMediaSource == LocalMediaSourceId.PHONE_CAMERA &&
            metadata == null &&
            (lifecycle == StreamLifecycle.IDLE || lifecycle == StreamLifecycle.ERROR)
        ) {
            stopPhoneFieldSupportAfterMediaEnd("phone media entered $lifecycle")
        }
        currentStatus = FieldGoProCaptureStateAuthority.statusForControllerUpdate(
            authoritativeStatus = currentStatus,
            operatingMode = operatingMode,
            activeSource = activeFieldMediaSource,
            activeSession = activeFieldSession,
            incomingLifecycle = lifecycle,
            incomingSession = metadata,
            incomingDetail = detail,
        )
        val text = detail ?: lifecycle.name.lowercase().replaceFirstChar(Char::titlecase)
        notificationManager().notify(NOTIFICATION_ID, buildNotification(text))
        if (lifecycle == StreamLifecycle.ERROR) {
            stopSelf()
        } else if (lifecycle == StreamLifecycle.IDLE) {
            // Keep the activity-bound controller alive for a deterministic next START. This
            // removes foreground status without retaining camera, audio, encoder, or RTSP work.
            removeForegroundIfUnused()
        }
    }

    private fun stopPhoneFieldSupportAfterMediaEnd(reason: String) {
        Log.i(TAG, "Stopping service-owned FIELD telemetry and sensors: $reason")
        fieldCoordinator.onSourceEnded(LocalMediaSourceId.PHONE_CAMERA)
        activeFieldMediaSource = null
        activeFieldSession = null
    }

    override fun onGoProIngressChanged(snapshot: GoProIngressSnapshot) {
        currentGoProStatus = snapshot
        Log.i(TAG, "GoPro ingress state=${snapshot.status}; destination=${snapshot.destination}; detail=${snapshot.detail}")
        if (snapshot.status != GoProSourceStatus.STOPPED) {
            notificationManager().notify(NOTIFICATION_ID, buildNotification(snapshot.detail ?: "GoPro RTMP ingest ${snapshot.status.name.lowercase()}"))
        }
        if (activeFieldMediaSource == LocalMediaSourceId.GOPRO_RTMP) {
            when (snapshot.status) {
                GoProSourceStatus.LIVE -> {
                    fieldCoordinator.onGoProLive()
                    FieldGoProCaptureStateAuthority.statusForIngress(
                        ingressStatus = snapshot.status,
                        activeSession = activeFieldSession,
                        eventActive = activeGoProFieldEvent != null,
                    )?.let { currentStatus = it }
                }
                GoProSourceStatus.LOST,
                GoProSourceStatus.ERROR,
                -> {
                    FieldGoProCaptureStateAuthority.statusForIngress(
                        ingressStatus = snapshot.status,
                        activeSession = activeFieldSession,
                        eventActive = activeGoProFieldEvent != null,
                    )?.let { currentStatus = it }
                }
                else -> Unit
            }
        }
    }

private fun startGoProFieldEvent(): LocalEventMapping {
    check(activeFieldMediaSource == LocalMediaSourceId.GOPRO_RTMP) {
        "GOPRO_RTMP is not the active FIELD source."
    }
    check(activeFieldSession != null) { "Start FIELD capture before starting an event." }
    check(currentGoProStatus.status == GoProSourceStatus.LIVE) {
        "GoPro ingest must be LIVE before starting an event."
    }
    check(activeGoProFieldEvent == null) { "A GoPro FIELD event is already active." }

    val diagnostics = goProIngress.startRecording()
    check(
        diagnostics.state == com.foresight.gateway.gopro.GoProRecordingState.ARMING ||
            diagnostics.state == com.foresight.gateway.gopro.GoProRecordingState.WAITING_FOR_KEYFRAME ||
            diagnostics.state == com.foresight.gateway.gopro.GoProRecordingState.RECORDING
    ) {
        "GoPro recorder failed to arm: state=${diagnostics.state}; detail=${diagnostics.detail}"
    }
    val recordingId = requireNotNull(diagnostics.recordingId) {
        "GoPro recorder armed without a recording ID."
    }

    val nowUtc = java.time.Instant.now()
    val nowMonotonic = android.os.SystemClock.elapsedRealtime()
    return LocalEventMapping(
        eventId = java.util.UUID.randomUUID().toString(),
        recordingId = recordingId,
        observedStartUtc = nowUtc,
        observedStartMonotonicMillis = nowMonotonic,
        startOffsetMillis = 0L,
        state = LocalEventMappingState.STARTED,
        authority = LocalEventAuthority.PHONE_FIELD,
    ).also {
        activeGoProFieldEvent = it
        Log.i(
            TAG,
            "GoPro FIELD event START: eventId=${it.eventId}; recordingId=${it.recordingId}; " +
                "recorderState=${diagnostics.state}",
        )
    }
}

private fun endGoProFieldEvent(
    terminationReason: LocalEventTerminationReason,
): LocalEventMapping {
    val start = requireNotNull(activeGoProFieldEvent) { "No active GoPro FIELD event." }
    val endUtc = java.time.Instant.now()
    val endMonotonic = android.os.SystemClock.elapsedRealtime()

    goProIngress.stopRecording()

    val duration = (endMonotonic - start.observedStartMonotonicMillis).coerceAtLeast(0L)
    return start.copy(
        observedEndUtc = endUtc,
        observedEndMonotonicMillis = endMonotonic,
        endOffsetMillis = duration,
        durationMillis = duration,
        state = LocalEventMappingState.READY,
        terminationReason = terminationReason,
    ).also {
        activeGoProFieldEvent = null
        Log.i(
            TAG,
            "GoPro FIELD event END: eventId=${it.eventId}; recordingId=${it.recordingId}; " +
                "durationMs=$duration reason=$terminationReason",
        )
    }
}

    private fun startAsForeground(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    locationForegroundServiceType(),
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startGoProAsForeground(text: String) {
        // Ingest does not use camera or microphone. Keep this service visible while it owns a
        // listener, without changing the validated capture foreground-service declaration.
        startForeground(NOTIFICATION_ID, buildNotification(text))
    }

    private fun removeForegroundIfUnused() {
        if (currentGoProStatus.status != GoProSourceStatus.STOPPED) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, CaptureForegroundService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    getString(R.string.notification_stop),
                    stopPendingIntent,
                ).build(),
            )
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        notificationManager().createNotificationChannel(channel)
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun locationForegroundServiceType(): Int =
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        ) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            0
        }

    private fun Intent.captureEndpoint(mode: GatewayOperatingMode): String? {
        val endpoint = getStringExtra(EXTRA_ENDPOINT)?.trim().orEmpty()
        return when {
            endpoint.isNotEmpty() -> endpoint
            mode == GatewayOperatingMode.FIELD -> null
            else -> error("An RTSP endpoint is required in Lab mode.")
        }
    }

    private fun Intent.operatingMode(): GatewayOperatingMode =
        GatewayOperatingMode.restore(getStringExtra(EXTRA_OPERATING_MODE))

    private fun Intent.telemetryEndpoint(): String = getStringExtra(EXTRA_TELEMETRY_ENDPOINT).orEmpty()

    private fun Intent.fieldMediaSource(): LocalMediaSourceId =
        runCatching { LocalMediaSourceId.valueOf(getStringExtra(EXTRA_FIELD_MEDIA_SOURCE).orEmpty()) }
            .getOrDefault(LocalMediaSourceId.PHONE_CAMERA)

    private fun Intent.goProNetworkMode(): GoProNetworkMode =
        runCatching { GoProNetworkMode.valueOf(getStringExtra(EXTRA_GOPRO_NETWORK_MODE).orEmpty()) }
            .getOrDefault(GoProNetworkMode.NORMAL_LAN)

    companion object {
        const val ACTION_START = "com.foresight.gateway.action.START_CAPTURE"
        const val ACTION_STOP = "com.foresight.gateway.action.STOP_CAPTURE"
        const val ACTION_START_GOPRO_INGRESS = "com.foresight.gateway.action.START_GOPRO_INGRESS"
        const val ACTION_STOP_GOPRO_INGRESS = "com.foresight.gateway.action.STOP_GOPRO_INGRESS"
        const val EXTRA_ENDPOINT = "com.foresight.gateway.extra.RTSP_ENDPOINT"
        const val EXTRA_TELEMETRY_ENDPOINT = "com.foresight.gateway.extra.TELEMETRY_ENDPOINT"
        const val EXTRA_OPERATING_MODE = "com.foresight.gateway.extra.OPERATING_MODE"
        const val EXTRA_FIELD_MEDIA_SOURCE = "com.foresight.gateway.extra.FIELD_MEDIA_SOURCE"
        const val EXTRA_GOPRO_NETWORK_MODE = "com.foresight.gateway.extra.GOPRO_NETWORK_MODE"

        private const val NOTIFICATION_CHANNEL_ID = "foresight_capture"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "CaptureForegroundService"
        @Volatile
        var currentStatus = CaptureStatus(StreamLifecycle.IDLE, null, null)
            private set
        @Volatile
        var currentGoProStatus = GoProIngressSnapshot()
            private set
    }
}

data class CaptureStatus(
    val lifecycle: StreamLifecycle,
    val metadata: CaptureSessionMetadata?,
    val detail: String?,
)
