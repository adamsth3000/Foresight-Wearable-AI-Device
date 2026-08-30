package com.foresight.gateway.ui

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.view.SurfaceView
import android.widget.TextView
import com.foresight.gateway.capture.CaptureForegroundService
import com.foresight.gateway.control.EventControlClient
import com.foresight.gateway.control.EventControlUiState

/** Minimal visible control surface; it never owns capture after the service starts. */
class GatewayActivity : Activity() {
    private lateinit var endpointInput: EditText
    private lateinit var telemetryEndpointInput: EditText
    private lateinit var controlEndpointInput: EditText
    private lateinit var statusText: TextView
    private lateinit var overlayStatusText: TextView
    private lateinit var captureLight: TextView
    private lateinit var eventLight: TextView
    private lateinit var startCaptureButton: Button
    private lateinit var endCaptureButton: Button
    private lateinit var startEventButton: Button
    private lateinit var endEventButton: Button
    private lateinit var quickEventButton: Button
    private lateinit var previewSurface: SurfaceView
    private lateinit var stoppedPreviewOverlay: View
    private val eventControl = EventControlClient()
    private var eventUiState = EventControlUiState()
    private var configurationState = GatewayConfigurationState()
    private var captureEndpointState = GatewayCaptureEndpointState()
    private var captureBinder: CaptureForegroundService.CaptureBinder? = null
    private var previewSurfaceReady = false
    private var stoppedPreviewCleared = false
    private var isServiceBound = false
    private var eventStatusRequestInFlight = false
    private var lastEventStatusRequestMillis = 0L
    private val uiHandler = Handler(Looper.getMainLooper())
    private val statusRefresh = object : Runnable {
        override fun run() {
            renderStatus()
            refreshEventStatus()
            uiHandler.postDelayed(this, STATUS_REFRESH_MILLIS)
        }
    }

    private val captureServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            captureBinder = service as? CaptureForegroundService.CaptureBinder
            attachPreviewIfReady()
            renderStatus()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            captureBinder = null
            isServiceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        endpointInput = EditText(this).apply {
            hint = "rtsp://LAPTOP_IP:8554/foresight-phone"
            captureEndpointState = GatewayCaptureEndpointState.restore(
                preferences().getString(PREF_LAST_ENDPOINT, null),
            )
            setText(captureEndpointState.rtspEndpoint)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(value: CharSequence?, start: Int, count: Int, after: Int) = Unit

                override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) = Unit

                override fun afterTextChanged(value: Editable?) {
                    captureEndpointState = captureEndpointState.update(value?.toString().orEmpty())
                    preferences().edit()
                        .putString(PREF_LAST_ENDPOINT, captureEndpointState.rtspEndpoint)
                        .apply()
                }
            })
        }
        telemetryEndpointInput = EditText(this).apply {
            hint = "http://LAPTOP_IP:8766"
            setText(preferences().getString(PREF_LAST_TELEMETRY_ENDPOINT, ""))
        }
        configurationState = GatewayConfigurationState.restore(
            preferences().getString(PREF_LAST_CONTROL_ENDPOINT, null),
            telemetryEndpointInput.text.toString(),
        )
        controlEndpointInput = EditText(this).apply {
            hint = "http://LAPTOP_IP:8766"
            setText(configurationState.controlBaseUrl)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(value: CharSequence?, start: Int, count: Int, after: Int) = Unit

                override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) = Unit

                override fun afterTextChanged(value: Editable?) {
                    configurationState = configurationState.updateControlBaseUrl(value?.toString().orEmpty())
                    preferences().edit()
                        .putString(PREF_LAST_CONTROL_ENDPOINT, configurationState.controlBaseUrl)
                        .apply()
                }
            })
        }
        statusText = TextView(this).apply {
            textSize = 16f
        }
        setContentView(buildContent())
        requestCapturePermissions()
        renderStatus()
    }

    override fun onResume() {
        super.onResume()
        renderStatus()
        uiHandler.post(statusRefresh)
    }

    override fun onPause() {
        uiHandler.removeCallbacks(statusRefresh)
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        isServiceBound = bindService(
            Intent(this, CaptureForegroundService::class.java),
            captureServiceConnection,
            Context.BIND_AUTO_CREATE,
        )
    }

    override fun onStop() {
        captureBinder?.detachPreview(previewSurface)
        captureBinder = null
        if (isServiceBound) {
            unbindService(captureServiceConnection)
            isServiceBound = false
        }
        super.onStop()
    }

    private fun buildContent(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(24, 24, 24, 24)

        val previewContainer = FrameLayout(this@GatewayActivity).apply {
            val surfaceView = SurfaceView(this@GatewayActivity)
            surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    previewSurfaceReady = true
                    logPreviewSurface("created")
                    attachPreviewIfReady()
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    previewSurfaceReady = width > 0 && height > 0
                    logPreviewSurface("changed format=$format")
                    attachPreviewIfReady()
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    previewSurfaceReady = false
                    Log.i(TAG, "Preview surface destroyed; detaching activity preview request.")
                    captureBinder?.detachPreview(surfaceView)
                }
            })
            previewSurface = surfaceView
            /*
             * RootEncoder renders into this activity-owned display Surface while the foreground
             * service remains the sole owner of Camera2, microphone, encoder, and RTSP transport.
             */
            addView(
                previewSurface,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            stoppedPreviewOverlay = View(this@GatewayActivity).apply {
                setBackgroundColor(Color.BLACK)
                contentDescription = "Stopped capture preview cover"
            }
            addView(
                stoppedPreviewOverlay,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            val overlay = LinearLayout(this@GatewayActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setBackgroundColor(Color.argb(130, 0, 0, 0))
            }
            captureLight = statusLight("Capture")
            eventLight = statusLight("Event")
            overlay.addView(captureLight, LinearLayout.LayoutParams(dp(44), dp(44)))
            overlay.addView(eventLight, LinearLayout.LayoutParams(dp(44), dp(44)))
            overlayStatusText = TextView(this@GatewayActivity).apply {
                textSize = 12f
                setTextColor(Color.WHITE)
                setPadding(0, dp(8), 0, 0)
            }
            overlay.addView(overlayStatusText)
            addView(overlay, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START,
            ))
        }
        addView(previewContainer, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.5f))

        val controls = LinearLayout(this@GatewayActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, 0, 0)
        }
        addView(controls, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.5f))

        controls.addView(panelLabel("RTSP DESTINATION"))
        controls.addView(endpointInput, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(48),
        ))
        controls.addView(panelLabel("TELEMETRY ENDPOINT"))
        controls.addView(telemetryEndpointInput, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(48),
        ))
        controls.addView(panelLabel("CONTROL ENDPOINT"))
        controls.addView(controlEndpointInput, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(48),
        ))

        controls.addView(panelLabel("CAPTURE"))
        val captureRow = LinearLayout(this@GatewayActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        startCaptureButton = Button(this@GatewayActivity).apply {
            text = "START CAPTURE"
            textSize = 18f
            setOnClickListener { startCapture() }
        }
        captureRow.addView(startCaptureButton, LinearLayout.LayoutParams(0, dp(72), 1f))
        endCaptureButton = Button(this@GatewayActivity).apply {
            text = "END CAPTURE"
            textSize = 18f
            setOnClickListener { stopCapture() }
        }
        captureRow.addView(endCaptureButton, LinearLayout.LayoutParams(0, dp(72), 1f))
        controls.addView(captureRow)

        controls.addView(panelLabel("EVENT"))
        val eventRow = LinearLayout(this@GatewayActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        startEventButton = Button(this@GatewayActivity).apply {
            text = "START EVENT"
            textSize = 22f
            setOnClickListener { sendEventControl("start") }
        }
        eventRow.addView(startEventButton, LinearLayout.LayoutParams(0, dp(82), 1f))
        endEventButton = Button(this@GatewayActivity).apply {
            text = "END EVENT"
            textSize = 22f
            setOnClickListener { sendEventControl("end") }
        }
        eventRow.addView(endEventButton, LinearLayout.LayoutParams(0, dp(82), 1f))
        quickEventButton = Button(this@GatewayActivity).apply {
            text = "QUICK EVENT"
            textSize = 20f
            setOnClickListener { sendEventControl("quick") }
        }
        eventRow.addView(quickEventButton, LinearLayout.LayoutParams(0, dp(82), 1f))
        controls.addView(eventRow)
        controls.addView(statusText)
    }

    private fun attachPreviewIfReady() {
        Log.i(
            TAG,
            "Preview attach requested: ready=$previewSurfaceReady, bound=${captureBinder != null}, " +
                "valid=${previewSurface.holder.surface.isValid}, visible=${previewSurface.visibility == View.VISIBLE}, " +
                "alpha=${previewSurface.alpha}, dimensions=${previewSurface.width}x${previewSurface.height}.",
        )
        if (previewSurfaceReady) captureBinder?.attachPreview(previewSurface)
    }

    private fun startCapture() {
        if (!hasRequiredCapturePermissions()) {
            requestCapturePermissions()
            return
        }
        val endpoint = captureEndpointState.rtspEndpoint
        Log.i(TAG, "Capture start requested: rtsp=$endpoint")
        val intent = Intent(this, CaptureForegroundService::class.java)
            .setAction(CaptureForegroundService.ACTION_START)
            .putExtra(CaptureForegroundService.EXTRA_ENDPOINT, endpoint)
            .putExtra(
                CaptureForegroundService.EXTRA_TELEMETRY_ENDPOINT,
                telemetryEndpointInput.text.toString().trim(),
            )
        preferences().edit().putString(
            PREF_LAST_TELEMETRY_ENDPOINT,
            telemetryEndpointInput.text.toString().trim(),
        ).apply()
        preferences().edit().putString(PREF_LAST_CONTROL_ENDPOINT, configurationState.controlBaseUrl).apply()
        startForegroundService(intent)
        renderStatus()
    }

    private fun stopCapture() {
        startService(
            Intent(this, CaptureForegroundService::class.java)
                .setAction(CaptureForegroundService.ACTION_STOP),
        )
        renderStatus()
    }

    private fun renderStatus() {
        val status = CaptureForegroundService.currentStatus
        val metadata = status.metadata
        val presentation = GatewayPresentation(status.lifecycle, eventUiState.event)
        statusText.text = buildString {
            append("Capture: ${presentation.captureLabel}")
            append("\nEvent: ${presentation.eventLabel}")
            eventUiState.event.eventId?.let { append(" (${it.take(8)})") }
            status.detail?.let { append("\nCapture detail: $it") }
            metadata?.captureSessionId?.let { append("\nSession: ${it.take(8)}") }
            eventUiState.detail?.let { append("\nEvent control: $it") }
        }
        overlayStatusText.text = buildString {
            append("Capture: ${presentation.captureLabel}")
            append("\nEvent: ${presentation.eventLabel}")
            eventUiState.event.eventId?.let { append("\nID: ${it.take(8)}") }
        }
        setLight(captureLight, presentation.captureLightOn, Color.RED)
        setLight(eventLight, presentation.eventLightOn, Color.rgb(0, 180, 0))
        startCaptureButton.isEnabled = presentation.startCaptureEnabled
        endCaptureButton.isEnabled = presentation.endCaptureEnabled
        startEventButton.isEnabled = presentation.startEventEnabled
        endEventButton.isEnabled = presentation.endEventEnabled
        quickEventButton.isEnabled = presentation.quickEventEnabled
        captureBinder?.updateEventState(presentation.event.state)
        clearStoppedPreviewIfNeeded(status.lifecycle)
    }

    private fun clearStoppedPreviewIfNeeded(lifecycle: com.foresight.gateway.transport.StreamLifecycle) {
        val stopped = lifecycle == com.foresight.gateway.transport.StreamLifecycle.IDLE ||
            lifecycle == com.foresight.gateway.transport.StreamLifecycle.ERROR
        if (!stopped) {
            stoppedPreviewCleared = false
            stoppedPreviewOverlay.visibility = View.GONE
            return
        }
        if (!stoppedPreviewCleared) {
            // Do not draw into the SurfaceView with Canvas: RootEncoder owns that buffer queue
            // while streaming. A regular overlay leaves the EGL preview producer untouched.
            stoppedPreviewOverlay.visibility = View.VISIBLE
            stoppedPreviewCleared = true
            Log.i(TAG, "Stopped-state preview cover shown without writing to the SurfaceView.")
        }
    }

    private fun logPreviewSurface(stage: String) {
        Log.i(
            TAG,
            "Preview surface $stage: valid=${previewSurface.holder.surface.isValid}, " +
                "visible=${previewSurface.visibility == View.VISIBLE}, alpha=${previewSurface.alpha}, " +
                "dimensions=${previewSurface.width}x${previewSurface.height}.",
        )
    }

    private fun refreshEventStatus() {
        if (CaptureForegroundService.currentStatus.lifecycle != com.foresight.gateway.transport.StreamLifecycle.STREAMING ||
            configurationState.controlBaseUrl.isBlank() || eventStatusRequestInFlight
        ) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastEventStatusRequestMillis < EVENT_STATUS_REFRESH_MILLIS) return
        lastEventStatusRequestMillis = now
        eventStatusRequestInFlight = true
        eventControl.status(configurationState.controlBaseUrl) { result ->
            runOnUiThread {
                eventStatusRequestInFlight = false
                eventUiState = eventUiState.applyStatus(result)
                renderStatus()
            }
        }
    }

    private fun sendEventControl(action: String) {
        val endpoint = configurationState.controlBaseUrl
        eventUiState = eventUiState.pending(action)
        renderStatus()
        eventControl.post(endpoint, action) { result ->
            runOnUiThread {
                eventUiState = eventUiState.apply(result)
                result.getOrNull()?.eventId?.let { eventId ->
                    val receiptUtc = java.time.Instant.now()
                    val receiptMonotonic = android.os.SystemClock.elapsedRealtime()
                    when (action) {
                        "start" -> captureBinder?.onAuthoritativeEventStarted(eventId, receiptUtc, receiptMonotonic)
                        "end" -> captureBinder?.onAuthoritativeEventEnded(eventId, receiptUtc, receiptMonotonic)
                    }
                }
                renderStatus()
            }
        }
    }

    private fun statusLight(label: String): TextView = TextView(this).apply {
        gravity = Gravity.CENTER
        text = "\u25CF"
        textSize = 34f
        contentDescription = "$label status light"
        setTextColor(Color.WHITE)
        setLight(this, false, Color.DKGRAY)
    }

    private fun setLight(light: TextView, isOn: Boolean, onColor: Int) {
        light.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (isOn) onColor else Color.DKGRAY)
            setStroke(dp(2), Color.LTGRAY)
        }
    }

    private fun panelLabel(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 12f
        setTextColor(Color.LTGRAY)
        setPadding(0, dp(6), 0, 0)
    }

    private fun requestCapturePermissions() {
        requestPermissions(requiredPermissions(), REQUEST_CAPTURE_PERMISSIONS)
    }

    private fun hasRequiredCapturePermissions(): Boolean =
        requiredCapturePermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun requiredPermissions(): Array<String> = requiredCapturePermissions().toMutableList().apply {
        // Location is optional in Phase 1C; denial must not prevent RTSP capture.
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
    }.toTypedArray()

    private fun requiredCapturePermissions(): Array<String> = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private fun preferences() = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_CAPTURE_PERMISSIONS = 1
        private const val PREFERENCES_NAME = "foresight_gateway"
        private const val PREF_LAST_ENDPOINT = "last_rtsp_endpoint"
        private const val PREF_LAST_TELEMETRY_ENDPOINT = "last_telemetry_endpoint"
        private const val PREF_LAST_CONTROL_ENDPOINT = "last_control_endpoint"
        private const val STATUS_REFRESH_MILLIS = 500L
        private const val EVENT_STATUS_REFRESH_MILLIS = 1_500L
        private const val TAG = "GatewayActivity"
    }
}
