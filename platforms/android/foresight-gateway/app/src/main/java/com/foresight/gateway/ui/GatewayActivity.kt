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
import android.widget.ScrollView
import android.view.SurfaceView
import android.widget.TextView
import com.foresight.gateway.capture.CaptureForegroundService
import com.foresight.gateway.capture.EventMediaSyncState
import com.foresight.gateway.capture.EventMediaSyncUiState
import com.foresight.gateway.capture.EventMediaSyncAttemptResult
import com.foresight.gateway.capture.EventMediaSyncHistoryEntry
import com.foresight.gateway.capture.EventMediaSyncSummary
import com.foresight.gateway.control.EventControlClient
import com.foresight.gateway.control.EventControlState
import com.foresight.gateway.control.EventControlUiState
import com.foresight.gateway.gopro.GoProIngressSnapshot
import com.foresight.gateway.gopro.GoProSourceStatus
import com.foresight.gateway.mode.GatewayOperatingMode
import com.foresight.gateway.mode.GatewayOperatingModePolicy

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
    private lateinit var syncEventButton: Button
    private lateinit var syncAllPendingButton: Button
    private lateinit var syncSummaryText: TextView
    private lateinit var syncHistoryContainer: LinearLayout
    private lateinit var syncReceiptText: TextView
    private lateinit var goProDestinationText: TextView
    private lateinit var goProStatusText: TextView
    private lateinit var startGoProButton: Button
    private lateinit var stopGoProButton: Button
    private lateinit var labModeButton: Button
    private lateinit var fieldModeButton: Button
    private lateinit var previewSurface: SurfaceView
    private lateinit var stoppedPreviewOverlay: View
    private val eventControl = EventControlClient()
    private var eventUiState = EventControlUiState()
    private var syncUiState = EventMediaSyncUiState()
    private var lastEventIdForSync: String? = null
    private var lastPropagatedSyncEventId: String? = null
    private var lastSyncUiDiagnostic: String? = null
    private var selectedSyncAttemptId: String? = null
    private var syncAllInFlight = false
    private var configurationState = GatewayConfigurationState()
    private var operatingMode = GatewayOperatingMode.LAB
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
            refreshSyncableEventFromService()
            refreshFieldEventFromService()
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
        lastEventIdForSync = preferences().getString(PREF_LAST_SYNC_EVENT_ID, null)
        operatingMode = GatewayOperatingMode.restore(preferences().getString(PREF_OPERATING_MODE, null))
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
        val controlsScroll = ScrollView(this@GatewayActivity).apply {
            isFillViewport = true
            addView(
                controls,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        addView(controlsScroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.5f))

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

        controls.addView(panelLabel("GOPRO INGEST (GW1-A DIAGNOSTIC)"))
        goProDestinationText = TextView(this@GatewayActivity).apply { textSize = 14f }
        goProStatusText = TextView(this@GatewayActivity).apply { textSize = 14f }
        controls.addView(goProDestinationText)
        controls.addView(goProStatusText)
        val goProRow = LinearLayout(this@GatewayActivity).apply { orientation = LinearLayout.HORIZONTAL }
        startGoProButton = Button(this@GatewayActivity).apply {
            text = "START GOPRO INGEST"
            setOnClickListener { startGoProIngress() }
        }
        stopGoProButton = Button(this@GatewayActivity).apply {
            text = "STOP GOPRO INGEST"
            setOnClickListener { stopGoProIngress() }
        }
        goProRow.addView(startGoProButton, LinearLayout.LayoutParams(0, dp(52), 1f))
        goProRow.addView(stopGoProButton, LinearLayout.LayoutParams(0, dp(52), 1f))
        controls.addView(goProRow)

        controls.addView(panelLabel("OPERATING MODE"))
        val modeRow = LinearLayout(this@GatewayActivity).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        labModeButton = Button(this@GatewayActivity).apply {
            text = "LAB"
            setOnClickListener { selectOperatingMode(GatewayOperatingMode.LAB) }
        }
        fieldModeButton = Button(this@GatewayActivity).apply {
            text = "FIELD"
            setOnClickListener { selectOperatingMode(GatewayOperatingMode.FIELD) }
        }
        modeRow.addView(labModeButton, LinearLayout.LayoutParams(0, dp(56), 1f))
        modeRow.addView(fieldModeButton, LinearLayout.LayoutParams(0, dp(56), 1f))
        controls.addView(modeRow)

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
        syncEventButton = Button(this@GatewayActivity).apply {
            text = "SYNC EVENT"
            textSize = 18f
            setOnClickListener { syncCurrentEvent() }
        }
        controls.addView(syncEventButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(60),
        ))
        syncAllPendingButton = Button(this@GatewayActivity).apply {
            text = "SYNC ALL PENDING"
            textSize = 16f
            setOnClickListener { syncAllPending() }
        }
        controls.addView(syncAllPendingButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(52),
        ))
        syncSummaryText = TextView(this@GatewayActivity).apply {
            textSize = 14f
            setPadding(0, dp(4), 0, dp(4))
        }
        controls.addView(syncSummaryText)
        controls.addView(panelLabel("SYNC HISTORY"))
        syncHistoryContainer = LinearLayout(this@GatewayActivity).apply {
            orientation = LinearLayout.VERTICAL
        }
        controls.addView(syncHistoryContainer)
        controls.addView(panelLabel("SYNC RECEIPT"))
        syncReceiptText = TextView(this@GatewayActivity).apply {
            textSize = 13f
            setPadding(0, dp(4), 0, dp(8))
        }
        controls.addView(syncReceiptText)
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
        if (!GatewayOperatingModePolicy.canStartCapture(operatingMode, endpoint)) {
            statusText.text = "Lab mode requires an RTSP destination."
            return
        }
        Log.i(TAG, "Capture start requested: mode=$operatingMode rtsp=${endpoint.ifBlank { "not configured" }}")
        val intent = Intent(this, CaptureForegroundService::class.java)
            .setAction(CaptureForegroundService.ACTION_START)
            .putExtra(CaptureForegroundService.EXTRA_ENDPOINT, endpoint)
            .putExtra(CaptureForegroundService.EXTRA_OPERATING_MODE, operatingMode.name)
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
        val syncPresentation = refreshSyncableEventFromService()
        val status = CaptureForegroundService.currentStatus
        val metadata = status.metadata
        val presentation = GatewayPresentation(operatingMode, status.lifecycle, eventUiState.event)
        statusText.text = buildString {
            append("Mode: ${operatingMode.name}")
            append("\nLocal Capture: ${presentation.captureLabel}")
            append("\nEvent: ${presentation.eventLabel}")
            eventUiState.event.eventId?.let { append(" (${it.take(8)})") }
            status.detail?.let { append("\nCapture detail: $it") }
            metadata?.captureSessionId?.let { append("\nSession: ${it.take(8)}") }
            eventUiState.detail?.let { append("\nEvent control: $it") }
            append("\nSync: ${syncPresentation.syncState?.name ?: syncUiState.state.name}")
            append(" (${syncPresentation.reason})")
        }
        overlayStatusText.text = buildString {
            append("Mode: ${operatingMode.name}")
            append("\nLocal: ${presentation.captureLabel}")
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
        labModeButton.isEnabled = !presentation.localCaptureActive
        fieldModeButton.isEnabled = !presentation.localCaptureActive
        labModeButton.alpha = if (operatingMode == GatewayOperatingMode.LAB) 1f else 0.55f
        fieldModeButton.alpha = if (operatingMode == GatewayOperatingMode.FIELD) 1f else 0.55f
        syncEventButton.visibility = if (syncPresentation.buttonVisible) View.VISIBLE else View.GONE
        syncEventButton.isEnabled = syncPresentation.buttonEnabled && !syncAllInFlight
        syncEventButton.text = if (syncPresentation.syncState == EventMediaSyncState.FAILED) "RETRY SYNC" else "SYNC EVENT"
        val history = captureBinder?.syncHistory().orEmpty()
        val summary = captureBinder?.syncSummary() ?: EventMediaSyncSummary(0, 0, 0)
        syncAllPendingButton.isEnabled = !syncAllInFlight && summary.readyLocalOnlyCount + summary.retryableCount > 0
        syncSummaryText.text = formatSyncSummary(summary)
        renderSyncHistory(history, captureBinder?.syncableEventIds().orEmpty())
        logSyncUiDecision(syncPresentation)
        captureBinder?.updateEventState(presentation.event.state)
        renderGoProIngress(captureBinder?.goProIngressSnapshot() ?: CaptureForegroundService.currentGoProStatus)
        clearStoppedPreviewIfNeeded(status.lifecycle)
    }

    private fun startGoProIngress() {
        startForegroundService(
            Intent(this, CaptureForegroundService::class.java)
                .setAction(CaptureForegroundService.ACTION_START_GOPRO_INGRESS),
        )
        renderStatus()
    }

    private fun stopGoProIngress() {
        startService(
            Intent(this, CaptureForegroundService::class.java)
                .setAction(CaptureForegroundService.ACTION_STOP_GOPRO_INGRESS),
        )
        renderStatus()
    }

    private fun renderGoProIngress(snapshot: GoProIngressSnapshot) {
        goProDestinationText.text = "Destination: ${snapshot.destination ?: "Start to discover Wi-Fi IPv4"}"
        goProStatusText.text = buildString {
            append("Status: ${snapshot.status}")
            snapshot.detail?.let { append("\nDetail: $it") }
            snapshot.metadata?.let {
                append("\nVideo: ${it.videoSummary()}")
                append("\nAudio: ${it.audioSummary()}")
            }
        }
        startGoProButton.isEnabled = snapshot.status == GoProSourceStatus.STOPPED
        stopGoProButton.isEnabled = snapshot.status != GoProSourceStatus.STOPPED
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
        if (operatingMode == GatewayOperatingMode.FIELD) return
        if (!isLocalCaptureActive(CaptureForegroundService.currentStatus.lifecycle) ||
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

    private fun isLocalCaptureActive(lifecycle: com.foresight.gateway.transport.StreamLifecycle): Boolean =
        lifecycle == com.foresight.gateway.transport.StreamLifecycle.STREAMING ||
            lifecycle == com.foresight.gateway.transport.StreamLifecycle.RECONNECTING ||
            lifecycle == com.foresight.gateway.transport.StreamLifecycle.DEGRADED ||
            lifecycle == com.foresight.gateway.transport.StreamLifecycle.OFFLINE

    private fun selectOperatingMode(mode: GatewayOperatingMode) {
        if (isLocalCaptureActive(CaptureForegroundService.currentStatus.lifecycle)) {
            Log.w(TAG, "Operating mode cannot change while local capture is active.")
            return
        }
        operatingMode = mode
        preferences().edit().putString(PREF_OPERATING_MODE, mode.name).apply()
        Log.i(TAG, "Gateway operating mode selected: $mode")
        renderStatus()
    }

    private fun sendEventControl(action: String) {
        if (operatingMode == GatewayOperatingMode.FIELD) {
            sendFieldEventControl(action)
            return
        }
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
                        "end" -> {
                            captureBinder?.onAuthoritativeEventEnded(eventId, receiptUtc, receiptMonotonic)
                            lastEventIdForSync = eventId
                            preferences().edit().putString(PREF_LAST_SYNC_EVENT_ID, eventId).apply()
                            Log.i(TAG, "Sync event candidate assigned from authoritative END: eventId=$eventId")
                            syncUiState = EventMediaSyncUiState(
                                EventMediaSyncState.LOCAL_ONLY,
                                "Waiting for local extraction",
                            )
                        }
                    }
                }
                renderStatus()
            }
        }
    }

    private fun sendFieldEventControl(action: String) {
        if (action == "quick") {
            eventUiState = eventUiState.copy(detail = "Quick event requires Lab mode and laptop control.")
            renderStatus()
            return
        }
        val binder = captureBinder
        if (binder == null) {
            eventUiState = eventUiState.copy(detail = "FIELD event unavailable: capture service is not bound.")
            renderStatus()
            return
        }
        eventUiState = eventUiState.pending(action)
        renderStatus()
        when (action) {
            "start" -> binder.startFieldEvent { result ->
                runOnUiThread {
                    eventUiState = result.fold(
                        onSuccess = { event ->
                            EventControlUiState(
                                EventControlState("recording_bounded_event", event.eventId),
                                "FIELD event recorded locally; laptop is not required.",
                            )
                        },
                        onFailure = { error -> eventUiState.copy(detail = "FIELD event start failed: ${error.message}") },
                    )
                    renderStatus()
                }
            }
            "end" -> binder.endFieldEvent { result ->
                runOnUiThread {
                    eventUiState = result.fold(
                        onSuccess = { event ->
                            lastEventIdForSync = event.eventId
                            preferences().edit().putString(PREF_LAST_SYNC_EVENT_ID, event.eventId).apply()
                            syncUiState = EventMediaSyncUiState(
                                EventMediaSyncState.LOCAL_ONLY,
                                "FIELD event is local; extraction follows recording finalization.",
                            )
                            EventControlUiState(
                                EventControlState("finalizing", event.eventId),
                                "FIELD event complete; pending local extraction.",
                            )
                        },
                        onFailure = { error -> eventUiState.copy(detail = "FIELD event end failed: ${error.message}") },
                    )
                    renderStatus()
                }
            }
        }
    }

    private fun refreshFieldEventFromService() {
        if (operatingMode != GatewayOperatingMode.FIELD) return
        captureBinder?.activeFieldEvent { active ->
            runOnUiThread {
                if (operatingMode != GatewayOperatingMode.FIELD || active == null) return@runOnUiThread
                eventUiState = EventControlUiState(
                    EventControlState("recording_bounded_event", active.eventId),
                    "Recovered active FIELD event from local metadata.",
                )
                renderStatus()
            }
        }
    }

    private fun syncCurrentEvent() {
        val syncPresentation = refreshSyncableEventFromService()
        val eventId = syncPresentation.eventId ?: return
        if (!syncPresentation.buttonEnabled) {
            syncUiState = EventMediaSyncUiState(EventMediaSyncState.LOCAL_ONLY, syncPresentation.reason)
            renderStatus()
            return
        }
        val endpoint = configurationState.controlBaseUrl
        syncUiState = EventMediaSyncUiState(EventMediaSyncState.UPLOADING, null)
        renderStatus()
        captureBinder?.syncReadyEventMedia(eventId, endpoint) { state ->
            runOnUiThread {
                syncUiState = state
                renderStatus()
            }
        } ?: run {
            syncUiState = EventMediaSyncUiState(EventMediaSyncState.FAILED, "Capture service unavailable")
            renderStatus()
        }
    }

    private fun syncAllPending() {
        val binder = captureBinder ?: run {
            syncUiState = EventMediaSyncUiState(EventMediaSyncState.FAILED, "Capture service unavailable")
            renderStatus()
            return
        }
        val endpoint = configurationState.controlBaseUrl
        syncAllInFlight = true
        syncUiState = EventMediaSyncUiState(EventMediaSyncState.UPLOADING, "Syncing pending events")
        renderStatus()
        binder.syncAllReadyEventMedia(endpoint) { eventId, state, completed, total ->
            runOnUiThread {
                syncUiState = state.copy(detail = state.detail ?: "Syncing $completed/$total: ${eventId.take(8)}")
                if ((total == 0 || completed == total) && state.state != EventMediaSyncState.UPLOADING) {
                    syncAllInFlight = false
                }
                renderStatus()
            }
        }
    }

    private fun formatSyncSummary(summary: EventMediaSyncSummary): String =
        "Pending: ${summary.readyLocalOnlyCount} / Synced: ${summary.syncedCount} / Retryable: ${summary.retryableCount}"

    private fun renderSyncHistory(
        entries: List<EventMediaSyncHistoryEntry>,
        retryableEventIds: List<String>,
    ) {
        syncHistoryContainer.removeAllViews()
        if (entries.isEmpty()) {
            syncHistoryContainer.addView(TextView(this).apply { text = "No sync attempts yet." })
            syncReceiptText.text = "Select a sync attempt to view its receipt."
            return
        }
        if (selectedSyncAttemptId !in entries.map { it.attemptId }) selectedSyncAttemptId = entries.first().attemptId
        entries.take(SYNC_HISTORY_VISIBLE_LIMIT).forEach { entry ->
            syncHistoryContainer.addView(Button(this).apply {
                text = "${historyIndicator(entry)} ${entry.eventId.take(8)} / ${entry.result ?: EventMediaSyncAttemptResult.FAILED}"
                textSize = 13f
                isAllCaps = false
                setOnClickListener {
                    selectedSyncAttemptId = entry.attemptId
                    renderStatus()
                }
            })
        }
        val selected = entries.first { it.attemptId == selectedSyncAttemptId }
        syncReceiptText.text = formatSyncReceipt(selected, selected.eventId in retryableEventIds)
    }

    private fun historyIndicator(entry: EventMediaSyncHistoryEntry): String =
        if (entry.result == EventMediaSyncAttemptResult.SYNCED) "[OK]" else "[FAIL]"

    private fun formatSyncReceipt(entry: EventMediaSyncHistoryEntry, retryAvailable: Boolean): String = buildString {
        append("Event: ${entry.eventId}\n")
        append("Origin/authority: ${entry.eventOrigin} / ${entry.authority}\n")
        append("Attempt: ${entry.startedUtc}\n")
        entry.completedUtc?.let { append("Completed: $it\n") }
        append("Bytes: ${formatByteSize(entry.byteSize)}\n")
        append("Destination: ${entry.destinationIdentity}\n")
        append("Local SHA-256: ${entry.localMediaSha256}\n")
        if (entry.result == EventMediaSyncAttemptResult.SYNCED) {
            append("Laptop SHA-256: ${entry.authoritativeMediaSha256}\n")
            append("SHA match: ${entry.localMediaSha256 == entry.authoritativeMediaSha256}\n")
            append("Laptop validated: ${entry.laptopValidated}")
        } else {
            append("Failure: ${entry.failureReason ?: "interrupted upload"}\n")
            append("Retry available: $retryAvailable")
        }
    }

    private fun formatByteSize(value: Long): String =
        if (value < 1_000_000) "${value / 1_000} KB" else "%.1f MB".format(value / 1_000_000.0)

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

    private fun refreshSyncableEventFromService(): GatewaySyncPresentation {
        val binder = captureBinder
        val recoveredEventId = binder?.latestSyncableEventId()
        if (recoveredEventId != null && recoveredEventId != lastEventIdForSync) {
            lastEventIdForSync = recoveredEventId
            preferences().edit().putString(PREF_LAST_SYNC_EVENT_ID, recoveredEventId).apply()
            Log.i(TAG, "Syncable READY event recovered from service: eventId=$recoveredEventId")
        }
        val eventId = lastEventIdForSync
        val extractionState = eventId?.let { binder?.eventMediaExtractionState(it) }
        val syncState = eventId?.let { binder?.eventMediaSyncState(it) }
        if (syncState != null && (syncState != syncUiState.state || eventId != lastPropagatedSyncEventId)) {
            syncUiState = EventMediaSyncUiState(syncState, null)
            lastPropagatedSyncEventId = eventId
            Log.i(TAG, "Sync state propagated to GatewayActivity: eventId=$eventId state=$syncState")
        }
        return GatewaySyncPresentation(eventId, extractionState, syncState, binder != null)
    }

    private fun logSyncUiDecision(presentation: GatewaySyncPresentation) {
        val diagnostic = "eventId=${presentation.eventId}; extraction=${presentation.extractionState}; " +
            "sync=${presentation.syncState}; visible=${presentation.buttonVisible}; " +
            "enabled=${presentation.buttonEnabled}; reason=${presentation.reason}"
        if (diagnostic != lastSyncUiDiagnostic) {
            lastSyncUiDiagnostic = diagnostic
            Log.i(TAG, "SYNC EVENT render: $diagnostic")
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_CAPTURE_PERMISSIONS = 1
        private const val PREFERENCES_NAME = "foresight_gateway"
        private const val PREF_LAST_ENDPOINT = "last_rtsp_endpoint"
        private const val PREF_LAST_TELEMETRY_ENDPOINT = "last_telemetry_endpoint"
        private const val PREF_LAST_CONTROL_ENDPOINT = "last_control_endpoint"
        private const val PREF_LAST_SYNC_EVENT_ID = "last_sync_event_id"
        private const val PREF_OPERATING_MODE = "operating_mode"
        private const val SYNC_HISTORY_VISIBLE_LIMIT = 8
        private const val STATUS_REFRESH_MILLIS = 500L
        private const val EVENT_STATUS_REFRESH_MILLIS = 1_500L
        private const val TAG = "GatewayActivity"
    }
}
