package com.foresight.gateway.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.foresight.gateway.R
import com.foresight.gateway.metadata.CaptureSessionMetadata
import com.foresight.gateway.transport.StreamLifecycle

/** User-started foreground service that keeps camera and microphone capture visible. */
class CaptureForegroundService : Service(), PhoneCaptureController.Listener {
    private lateinit var controller: PhoneCaptureController

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        controller = PhoneCaptureController(this, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startAsForeground("Preparing capture")
                runCatching { controller.start(intent.requireEndpoint()) }
                    .onFailure { onCaptureStateChanged(StreamLifecycle.ERROR, null, it.message) }
            }

            ACTION_STOP -> {
                controller.stop()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        controller.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

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
        }
    }

    private fun startAsForeground(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
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

    private fun Intent.requireEndpoint(): String =
        getStringExtra(EXTRA_ENDPOINT) ?: error("An RTSP endpoint is required.")

    companion object {
        const val ACTION_START = "com.foresight.gateway.action.START_CAPTURE"
        const val ACTION_STOP = "com.foresight.gateway.action.STOP_CAPTURE"
        const val EXTRA_ENDPOINT = "com.foresight.gateway.extra.RTSP_ENDPOINT"

        private const val NOTIFICATION_CHANNEL_ID = "foresight_capture"
        private const val NOTIFICATION_ID = 1001

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
