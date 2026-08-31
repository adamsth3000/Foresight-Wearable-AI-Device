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
import com.foresight.gateway.R
import com.foresight.gateway.metadata.CaptureSessionMetadata
import com.foresight.gateway.mode.GatewayOperatingMode
import com.foresight.gateway.transport.StreamLifecycle

/** User-started foreground service that keeps camera and microphone capture visible. */
class CaptureForegroundService : Service(), PhoneCaptureController.Listener {
    private lateinit var controller: PhoneCaptureController
    @Volatile
    private var authoritativeEventState: String = "idle"
    @Volatile
    private var operatingMode = GatewayOperatingMode.LAB

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        controller = PhoneCaptureController(this, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startAsForeground("Preparing capture")
                val mode = intent.operatingMode()
                operatingMode = mode
                val endpoint = intent.captureEndpoint(mode)
                Log.i(
                    TAG,
                    "Capture start requested: mode=$mode rtsp=${endpoint ?: "not configured"}; ${controller.startDiagnostics()}; " +
                        "invoking PhoneCaptureController.start().",
                )
                // Controller dispatches RootEncoder preparation to its serialized capture worker.
                controller.start(endpoint, intent.telemetryEndpoint())
            }

            ACTION_STOP -> {
                if (operatingMode == GatewayOperatingMode.LAB && CaptureEventInterlock.blocksCaptureStop(authoritativeEventState)) {
                    onCaptureStateChanged(
                        currentStatus.lifecycle,
                        currentStatus.metadata,
                        "End the active event before stopping capture.",
                    )
                } else {
            controller.stop()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "Service destroyed; requesting controller shutdown if it is still active.")
        controller.stop()
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
            controller.startFieldEvent(callback)
        }

        internal fun endFieldEvent(callback: (Result<LocalEventMapping>) -> Unit) {
            controller.endFieldEvent(callback)
        }

        internal fun activeFieldEvent(callback: (LocalEventMapping?) -> Unit) {
            controller.activeFieldEvent(callback)
        }

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
    }

    override fun onCaptureStateChanged(
        lifecycle: StreamLifecycle,
        metadata: CaptureSessionMetadata?,
        detail: String?,
    ) {
        currentStatus = CaptureStatus(lifecycle, metadata, detail)
        val text = detail ?: lifecycle.name.lowercase().replaceFirstChar(Char::titlecase)
        notificationManager().notify(NOTIFICATION_ID, buildNotification(text))
        if (lifecycle == StreamLifecycle.ERROR) {
            stopSelf()
        } else if (lifecycle == StreamLifecycle.IDLE) {
            // Keep the activity-bound controller alive for a deterministic next START. This
            // removes foreground status without retaining camera, audio, encoder, or RTSP work.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
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

    companion object {
        const val ACTION_START = "com.foresight.gateway.action.START_CAPTURE"
        const val ACTION_STOP = "com.foresight.gateway.action.STOP_CAPTURE"
        const val EXTRA_ENDPOINT = "com.foresight.gateway.extra.RTSP_ENDPOINT"
        const val EXTRA_TELEMETRY_ENDPOINT = "com.foresight.gateway.extra.TELEMETRY_ENDPOINT"
        const val EXTRA_OPERATING_MODE = "com.foresight.gateway.extra.OPERATING_MODE"

        private const val NOTIFICATION_CHANNEL_ID = "foresight_capture"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "CaptureForegroundService"
        @Volatile
        var currentStatus = CaptureStatus(StreamLifecycle.IDLE, null, null)
            private set
    }
}

data class CaptureStatus(
    val lifecycle: StreamLifecycle,
    val metadata: CaptureSessionMetadata?,
    val detail: String?,
)
