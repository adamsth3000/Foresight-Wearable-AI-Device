package com.foresight.gateway.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
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
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.Surface
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
import com.foresight.gateway.capture.LocalMediaSourceId
import com.foresight.gateway.control.EventControlClient
import com.foresight.gateway.control.EventControlState
import com.foresight.gateway.control.EventControlUiState
import com.foresight.gateway.gopro.GoProIngressSnapshot
import com.foresight.gateway.gopro.GoProNetworkMode
import com.foresight.gateway.gopro.GoProPreviewState
import com.foresight.gateway.gopro.GoProSourceStatus
import com.foresight.gateway.mode.GatewayOperatingMode
import com.foresight.gateway.mode.GatewayOperatingModePolicy
import com.foresight.gateway.sensors.PhoneHeadingProvider
import com.foresight.gateway.sensors.HeadingState
import com.foresight.gateway.sensors.ForegroundLocationProvider
import com.foresight.gateway.sensors.ForegroundLocationPermission
import com.foresight.gateway.sensors.ForegroundLocationPermissionPolicy
import com.foresight.gateway.vision.LatestOcrState
import com.foresight.gateway.vision.MlKitOcrRecognizer
import com.foresight.gateway.vision.OnDeviceOcrRecognizer
import com.foresight.gateway.vision.SceneEvidenceAssembler
import com.foresight.gateway.vision.LatestSceneEvidenceState
import com.foresight.gateway.vision.LatestSceneLocationState
import com.foresight.gateway.vision.SceneLocation
import com.foresight.gateway.vision.SceneLocationFreshnessPolicy
import com.foresight.gateway.vision.LiveVisionRuntime
import com.foresight.gateway.vision.LiveVisionRuntimePolicy
import com.foresight.gateway.vision.LiveVisionRuntimeTransition
import com.foresight.gateway.vision.MediaPipeObjectDetector
import com.foresight.gateway.vision.OnDeviceVisionDetector
import com.foresight.gateway.vision.SurfaceViewFrameSampler
import com.foresight.gateway.vision.LatestDetectionState
import com.foresight.gateway.vision.VisionFailurePresentationPolicy
import com.foresight.gateway.voice.AndroidAudioCueOutput
import com.foresight.gateway.voice.AndroidOfflineSpeechOutput
import com.foresight.gateway.voice.InteractionContextAssembler
import com.foresight.gateway.voice.MicrophoneArbiter
import com.foresight.gateway.voice.HostedTalkEligibilityPolicy
import com.foresight.gateway.voice.VoskAudioInputAdapter
import com.foresight.gateway.voice.VoskWakePhraseListener
import com.foresight.gateway.voice.WakePreferenceStore
import com.foresight.gateway.voice.WakeRuntimeController
import com.foresight.gateway.voice.WakeRuntimeInputs
import com.foresight.gateway.voice.WakeRuntimeState
import com.foresight.gateway.voice.RoundRobinWakeAcknowledgementSelector
import com.foresight.gateway.voice.VoiceRuntimeState
import com.foresight.gateway.voice.VoiceTurnController
import com.foresight.gateway.voice.conversation.ConversationHistory
import com.foresight.gateway.voice.conversation.ConversationBackendMode
import com.foresight.gateway.voice.conversation.ConversationReadiness
import com.foresight.gateway.voice.conversation.DefaultVoiceRequestRouter
import com.foresight.gateway.voice.conversation.LocationRefreshRequester
import com.foresight.gateway.voice.conversation.DeviceActionParser
import com.foresight.gateway.voice.conversation.ForesightContextSnapshotAssembler
import com.foresight.gateway.voice.conversation.GemmaModelInstaller
import com.foresight.gateway.voice.conversation.GemmaModelSpec
import com.foresight.gateway.voice.conversation.GemmaInstallState
import com.foresight.gateway.voice.conversation.LiteRtLmConversationEngine
import com.foresight.gateway.voice.conversation.GoogleApiKeyStore
import com.foresight.gateway.voice.conversation.GoogleMapsPlatformKeyStore
import com.foresight.gateway.vision.geolocation.DefaultVisualGeolocationResolver
import com.foresight.gateway.vision.geolocation.GoogleMapsPlatformGeolocationClient
import com.foresight.gateway.vision.geolocation.GoogleStreetViewReferenceProvider
import com.foresight.gateway.vision.geolocation.PackageAndroidApiIdentityProvider
import com.foresight.gateway.vision.geolocation.DirectAndroidMvpStreetViewAuthorizer
import com.foresight.gateway.vision.geolocation.StreetViewAcquisitionCoordinator
import com.foresight.gateway.voice.conversation.GoogleHostedConversationEngine
import com.foresight.gateway.voice.conversation.comparison.GoogleStreetViewComparisonClient
import com.foresight.gateway.voice.conversation.comparison.StreetViewVisualComparisonConsumer
import com.foresight.gateway.voice.conversation.SelectableConversationEngine
import com.foresight.gateway.voice.conversation.SurfaceViewConversationVisualFrameProvider
import com.foresight.gateway.voice.conversation.connectionMessage
import com.foresight.gateway.voice.conversation.userMessage

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
    private lateinit var goProDiagnosticsText: TextView
    private lateinit var startGoProButton: Button
    private lateinit var stopGoProButton: Button
    private lateinit var startGoProRecordingButton: Button
    private lateinit var stopGoProRecordingButton: Button
    private lateinit var labModeButton: Button
    private lateinit var fieldModeButton: Button
    private lateinit var phoneFieldSourceButton: Button
    private lateinit var goProFieldSourceButton: Button
    private lateinit var normalLanGoProNetworkButton: Button
    private lateinit var hotspotGoProNetworkButton: Button
    private lateinit var previewSurface: SurfaceView
    private lateinit var stoppedPreviewOverlay: View
    private lateinit var compassRibbon: CompassRibbonView
    private lateinit var visionDetectionOverlay: VisionDetectionOverlayView
    private lateinit var ocrOverlay: OcrOverlayView
    private lateinit var gestureTargetOverlay: GestureTargetOverlayView
    private lateinit var headingProvider: PhoneHeadingProvider
    private lateinit var standardVisualizationButton: Button
    private lateinit var visionVisualizationButton: Button
    private lateinit var augmentedRealityVisualizationButton: Button
    private lateinit var talkToForesightButton: Button
    private lateinit var voiceStatusText: TextView
    private lateinit var locationStatusText: TextView
    private lateinit var wakeOnButton: Button
    private lateinit var wakeOffButton: Button
    private lateinit var wakeStatusText: TextView
    private lateinit var hostedAiButton: Button
    private lateinit var localAiButton: Button
    private lateinit var configureHostedAiButton: Button
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
    private var selectedFieldMediaSource = LocalMediaSourceId.PHONE_CAMERA
    private var selectedGoProNetworkMode = GoProNetworkMode.NORMAL_LAN
    private var visualizationMode = GatewayVisualizationMode.STANDARD
    private var conversationBackendMode = ConversationBackendMode.HOSTED
    private var captureEndpointState = GatewayCaptureEndpointState()
    private var captureBinder: CaptureForegroundService.CaptureBinder? = null
    private var previewSurfaceReady = false
    private var previewOwner = PreviewOwner.NONE
    private var stoppedPreviewCleared = false
    private var isServiceBound = false
    private var activityLifecycleState = "CREATED"
    private var activityResumed = false
    private var eventStatusRequestInFlight = false
    private var lastEventStatusRequestMillis = 0L
    private var lastRenderedVoiceState = VoiceRuntimeState.IDLE
    private var voiceStatusClear: Runnable? = null
    private var lastHostedTalkEligibilityDiagnostic: String? = null
    private val captureLightRenderCache = GatewayUiRenderCache<StatusLightRenderState>()
    private val eventLightRenderCache = GatewayUiRenderCache<StatusLightRenderState>()
    private val syncHistoryRenderCache = GatewayUiRenderCache<SyncHistoryRenderState>()
    private val liveVisionRuntime = LiveVisionRuntime()
    private val latestDetectionState = LatestDetectionState(SystemClock::elapsedRealtimeNanos)
    private val latestOcrState = LatestOcrState(SystemClock::elapsedRealtimeNanos)
    private val latestSceneEvidenceState = LatestSceneEvidenceState()
    private val latestSceneLocationState = LatestSceneLocationState(SystemClock::elapsedRealtime)
    private var latestHeadingState = HeadingState.unavailable()
    private var locationStaleLogged = false
    private lateinit var sceneEvidenceAssembler: SceneEvidenceAssembler
    private lateinit var foregroundLocationProvider: ForegroundLocationProvider
    private lateinit var surfaceViewFrameSampler: SurfaceViewFrameSampler
    private lateinit var onDeviceVisionDetector: OnDeviceVisionDetector
    private lateinit var onDeviceOcrRecognizer: OnDeviceOcrRecognizer
    private lateinit var voiceTurnController: VoiceTurnController
    private lateinit var wakeRuntimeController: WakeRuntimeController
    private lateinit var wakePreferenceStore: WakePreferenceStore
    private val wakeAcknowledgementSelector = RoundRobinWakeAcknowledgementSelector()
    private lateinit var gemmaModelInstaller: GemmaModelInstaller
    private lateinit var liteRtLmConversationEngine: LiteRtLmConversationEngine
    private lateinit var googleApiKeyStore: GoogleApiKeyStore
    private lateinit var googleMapsPlatformKeyStore: GoogleMapsPlatformKeyStore
    private lateinit var streetViewAcquisitionCoordinator: StreetViewAcquisitionCoordinator
    private lateinit var googleHostedConversationEngine: GoogleHostedConversationEngine
    private lateinit var selectableConversationEngine: SelectableConversationEngine
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
            previewOwner = PreviewOwner.NONE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        lastEventIdForSync = preferences().getString(PREF_LAST_SYNC_EVENT_ID, null)
        operatingMode = GatewayOperatingMode.restore(preferences().getString(PREF_OPERATING_MODE, null))
        selectedFieldMediaSource = runCatching {
            LocalMediaSourceId.valueOf(preferences().getString(PREF_FIELD_MEDIA_SOURCE, null).orEmpty())
        }.getOrDefault(LocalMediaSourceId.PHONE_CAMERA)
        selectedGoProNetworkMode = runCatching {
            GoProNetworkMode.valueOf(preferences().getString(PREF_GOPRO_NETWORK_MODE, null).orEmpty())
        }.getOrDefault(GoProNetworkMode.NORMAL_LAN)
        visualizationMode = GatewayVisualizationMode.restore(
            preferences().getString(PREF_VISUALIZATION_MODE, null),
        )
        conversationBackendMode = ConversationBackendMode.restore(
            preferences().getString(PREF_CONVERSATION_BACKEND_MODE, null),
        )
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
        surfaceViewFrameSampler = SurfaceViewFrameSampler(VISION_SAMPLE_INTERVAL_MILLIS)
        onDeviceVisionDetector = MediaPipeObjectDetector(applicationContext)
        onDeviceOcrRecognizer = MlKitOcrRecognizer(applicationContext)
        headingProvider = PhoneHeadingProvider(
            context = this,
            displayRotation = { window.decorView.display?.rotation ?: Surface.ROTATION_0 },
            onHeadingChanged = { state -> latestHeadingState = state; compassRibbon.setHeadingState(state) },
        )
        sceneEvidenceAssembler = SceneEvidenceAssembler(
            latestDetections = latestDetectionState,
            latestOcr = latestOcrState,
            elapsedRealtimeNanos = SystemClock::elapsedRealtimeNanos,
            heading = { latestHeadingState.takeIf(HeadingState::isAvailable) },
            location = ::freshSceneLocation,
        )
        foregroundLocationProvider = ForegroundLocationProvider(
            context = applicationContext,
            elapsedRealtimeMillis = SystemClock::elapsedRealtime,
            onLocation = { location -> runOnUiThread {
                latestSceneLocationState.publish(location)
                locationStaleLogged = false
                renderLocationStatus()
            } },
            onPermissionDenied = { runOnUiThread {
                latestSceneLocationState.clear()
                renderLocationStatus()
            } },
        )
        val interactionContextAssembler = InteractionContextAssembler(
            captureActive = { isLocalCaptureActive(CaptureForegroundService.currentStatus.lifecycle) },
            visualizationMode = { visualizationMode },
            latestDetectionState = latestDetectionState,
            elapsedRealtimeMillis = SystemClock::elapsedRealtime,
            maxDetectionAgeNanos = VOICE_DETECTION_FRESHNESS_NANOS,
        )
        gemmaModelInstaller = GemmaModelInstaller(applicationContext)
        liteRtLmConversationEngine = LiteRtLmConversationEngine(
            applicationContext,
            installer = gemmaModelInstaller,
        )
        googleMapsPlatformKeyStore = GoogleMapsPlatformKeyStore(applicationContext)
        googleApiKeyStore = GoogleApiKeyStore(applicationContext)
        streetViewAcquisitionCoordinator = StreetViewAcquisitionCoordinator(
            GoogleStreetViewReferenceProvider(DirectAndroidMvpStreetViewAuthorizer(googleMapsPlatformKeyStore::read), PackageAndroidApiIdentityProvider(applicationContext)),
            { !googleMapsPlatformKeyStore.read().isNullOrBlank() },
            StreetViewVisualComparisonConsumer(GoogleStreetViewComparisonClient(googleApiKeyStore::read)),
        )
        googleHostedConversationEngine = GoogleHostedConversationEngine(googleApiKeyStore::read)
        if (conversationBackendMode == ConversationBackendMode.HOSTED) {
            Log.i(GOOGLE_AI_TAG, "GOOGLE_AI_BACKEND selected=HOSTED")
        }
        selectableConversationEngine = SelectableConversationEngine(
            mode = { conversationBackendMode },
            hosted = googleHostedConversationEngine,
            local = liteRtLmConversationEngine,
        )
        voiceTurnController = VoiceTurnController(
            microphoneAvailability = ::voiceMicrophoneAvailability,
            audioInput = VoskAudioInputAdapter(applicationContext, SystemClock::elapsedRealtime),
            audioCueOutput = AndroidAudioCueOutput(),
            speechOutput = AndroidOfflineSpeechOutput(applicationContext),
            requestRouter = DefaultVoiceRequestRouter(
                actionParser = DeviceActionParser(),
                conversationEngine = selectableConversationEngine,
                contextAssembler = ForesightContextSnapshotAssembler(
                    interactionContextAssembler::assemble,
                    sceneEvidence = ::sceneEvidenceForConversation,
                    mapsLocation = ::mapsLocationForConversation,
                ),
                history = ConversationHistory(),
                visualFrameProvider = SurfaceViewConversationVisualFrameProvider(
                    surfaceView = { previewSurface },
                    source = {
                        if ((captureBinder?.activeFieldMediaSource() ?: selectedFieldMediaSource) == LocalMediaSourceId.GOPRO_RTMP) "GOPRO_PREVIEW" else "PHONE_PREVIEW"
                    },
                ),
                visualContextEnabled = { conversationBackendMode == ConversationBackendMode.HOSTED },
                locationRefreshRequester = object : LocationRefreshRequester {
                    override fun refreshIfNeeded(
                        utterance: String,
                        location: SceneLocation?,
                        nowElapsedMillis: Long,
                        onComplete: () -> Unit,
                    ) {
                        foregroundLocationProvider.requestFreshFix(
                            maxAgeMillis = SceneLocationFreshnessPolicy.SCENE_FRESH_MAX_AGE_MILLIS,
                            onComplete = { onComplete() },
                        )
                    }
                },
                geolocationResolver = DefaultVisualGeolocationResolver(
                    GoogleMapsPlatformGeolocationClient(googleMapsPlatformKeyStore::read),
                    streetViewAcquisitionCoordinator,
                ),
            ),
            onStateChanged = { state -> runOnUiThread {
                renderVoiceState(state)
                reconcileWakeRuntime()
            } },
            onResponse = { response -> runOnUiThread { renderVoiceResponse(response.text, response.requestSpeech) } },
        )
        wakePreferenceStore = SharedPreferencesWakePreferenceStore()
        wakeRuntimeController = WakeRuntimeController(
            listener = VoskWakePhraseListener(applicationContext),
            onStateChanged = ::renderWakeRuntimeState,
            startCommandTurn = ::startVoiceTurnFromWake,
        )
        wakeRuntimeController.setEnabled(wakePreferenceStore.isEnabled(), wakeRuntimeInputs())
        renderWakeRuntimeState(wakeRuntimeController.state)
        updateVisualizationRuntime()
        requestCapturePermissions()
        renderStatus()
    }

    override fun onDestroy() {
        if (::streetViewAcquisitionCoordinator.isInitialized) streetViewAcquisitionCoordinator.close()
        if (::foregroundLocationProvider.isInitialized) foregroundLocationProvider.stop()
        if (::wakeRuntimeController.isInitialized) wakeRuntimeController.close()
        if (::voiceTurnController.isInitialized) voiceTurnController.close()
        if (::surfaceViewFrameSampler.isInitialized) surfaceViewFrameSampler.close()
        if (::onDeviceVisionDetector.isInitialized) onDeviceVisionDetector.close()
        if (::onDeviceOcrRecognizer.isInitialized) onDeviceOcrRecognizer.close()
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (::voiceTurnController.isInitialized) voiceTurnController.onTrimMemory(level)
    }

    override fun onResume() {
        super.onResume()
        activityLifecycleState = "RESUMED"
        activityResumed = true
        updateLocationRuntime()
        updateVisualizationRuntime()
        reconcileWakeRuntime()
        renderStatus()
        uiHandler.post(statusRefresh)
    }

    override fun onPause() {
        if (::streetViewAcquisitionCoordinator.isInitialized) streetViewAcquisitionCoordinator.cancelActiveRequest()
        activityLifecycleState = "PAUSED"
        activityResumed = false
        updateLocationRuntime()
        updateVisualizationRuntime()
        reconcileWakeRuntime()
        uiHandler.removeCallbacks(statusRefresh)
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        activityLifecycleState = "STARTED"
        isServiceBound = bindService(
            Intent(this, CaptureForegroundService::class.java),
            captureServiceConnection,
            Context.BIND_AUTO_CREATE,
        )
    }

    override fun onStop() {
        activityLifecycleState = "STOPPED"
        updateVisualizationRuntime()
        detachPreviewOwner()
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
                    detachPreviewOwner()
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
            visionDetectionOverlay = VisionDetectionOverlayView(this@GatewayActivity)
            addView(
                visionDetectionOverlay,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            ocrOverlay = OcrOverlayView(this@GatewayActivity)
            addView(ocrOverlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            gestureTargetOverlay = GestureTargetOverlayView(this@GatewayActivity)
            addView(
                gestureTargetOverlay,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            compassRibbon = CompassRibbonView(this@GatewayActivity)
            addView(
                compassRibbon,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(84),
                    Gravity.TOP or Gravity.CENTER_HORIZONTAL,
                ).apply {
                    // The capture/event overlay is bottom anchored, leaving the top unobstructed.
                    marginEnd = dp(8)
                    topMargin = dp(8)
                },
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
            addView(
                overlay,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.START,
                ).apply {
                    marginStart = dp(12)
                    bottomMargin = dp(12)
                },
            )
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
        controls.addView(panelLabel("FIELD MEDIA SOURCE"))
        val fieldSourceRow = LinearLayout(this@GatewayActivity).apply { orientation = LinearLayout.HORIZONTAL }
        phoneFieldSourceButton = Button(this@GatewayActivity).apply {
            text = "PHONE CAMERA"
            setOnClickListener { selectFieldMediaSource(LocalMediaSourceId.PHONE_CAMERA) }
        }
        goProFieldSourceButton = Button(this@GatewayActivity).apply {
            text = "GOPRO"
            setOnClickListener { selectFieldMediaSource(LocalMediaSourceId.GOPRO_RTMP) }
        }
        fieldSourceRow.addView(phoneFieldSourceButton, LinearLayout.LayoutParams(0, dp(52), 1f))
        fieldSourceRow.addView(goProFieldSourceButton, LinearLayout.LayoutParams(0, dp(52), 1f))
        controls.addView(fieldSourceRow)

        controls.addView(panelLabel("GOPRO NETWORK"))
        val goProNetworkRow = LinearLayout(this@GatewayActivity).apply { orientation = LinearLayout.HORIZONTAL }
        normalLanGoProNetworkButton = Button(this@GatewayActivity).apply {
            text = "PRIVATE WI-FI"
            setOnClickListener { selectGoProNetworkMode(GoProNetworkMode.NORMAL_LAN) }
        }
        hotspotGoProNetworkButton = Button(this@GatewayActivity).apply {
            text = "PHONE HOTSPOT"
            setOnClickListener { selectGoProNetworkMode(GoProNetworkMode.PHONE_HOTSPOT) }
        }
        goProNetworkRow.addView(normalLanGoProNetworkButton, LinearLayout.LayoutParams(0, dp(52), 1f))
        goProNetworkRow.addView(hotspotGoProNetworkButton, LinearLayout.LayoutParams(0, dp(52), 1f))
        controls.addView(goProNetworkRow)

        controls.addView(panelLabel("GOPRO INGEST (GW1-A DIAGNOSTIC)"))
        goProDestinationText = TextView(this@GatewayActivity).apply { textSize = 14f }
        goProStatusText = TextView(this@GatewayActivity).apply { textSize = 14f }
        goProDiagnosticsText = TextView(this@GatewayActivity).apply { textSize = 14f }
        controls.addView(goProDestinationText)
        controls.addView(goProStatusText)
        controls.addView(goProDiagnosticsText)
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
        val goProRecordingRow = LinearLayout(this@GatewayActivity).apply { orientation = LinearLayout.HORIZONTAL }
        startGoProRecordingButton = Button(this@GatewayActivity).apply {
            text = "START GOPRO RECORDING"
            setOnClickListener { startGoProRecording() }
        }
        stopGoProRecordingButton = Button(this@GatewayActivity).apply {
            text = "STOP GOPRO RECORDING"
            setOnClickListener { stopGoProRecording() }
        }
        goProRecordingRow.addView(startGoProRecordingButton, LinearLayout.LayoutParams(0, dp(52), 1f))
        goProRecordingRow.addView(stopGoProRecordingButton, LinearLayout.LayoutParams(0, dp(52), 1f))
        controls.addView(goProRecordingRow)

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

        controls.addView(panelLabel("VISUALIZATION MODE"))
        val visualizationModeRow = LinearLayout(this@GatewayActivity).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        standardVisualizationButton = Button(this@GatewayActivity).apply {
            text = "STANDARD"
            setOnClickListener { selectVisualizationMode(GatewayVisualizationMode.STANDARD) }
        }
        visionVisualizationButton = Button(this@GatewayActivity).apply {
            text = "VISION"
            setOnClickListener { selectVisualizationMode(GatewayVisualizationMode.VISION) }
        }
        augmentedRealityVisualizationButton = Button(this@GatewayActivity).apply {
            text = "AUGMENTED REALITY"
            setOnClickListener { selectVisualizationMode(GatewayVisualizationMode.AUGMENTED_REALITY) }
        }
        visualizationModeRow.addView(standardVisualizationButton, LinearLayout.LayoutParams(0, dp(52), 1f))
        visualizationModeRow.addView(visionVisualizationButton, LinearLayout.LayoutParams(0, dp(52), 1f))
        visualizationModeRow.addView(augmentedRealityVisualizationButton, LinearLayout.LayoutParams(0, dp(52), 1.4f))
        controls.addView(visualizationModeRow)

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

        controls.addView(panelLabel("VOICE"))
        val backendRow = LinearLayout(this@GatewayActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        hostedAiButton = Button(this@GatewayActivity).apply {
            text = "HOSTED"
            setOnClickListener { selectConversationBackend(ConversationBackendMode.HOSTED) }
        }
        localAiButton = Button(this@GatewayActivity).apply {
            text = "LOCAL"
            setOnClickListener { selectConversationBackend(ConversationBackendMode.LOCAL) }
        }
        backendRow.addView(hostedAiButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        backendRow.addView(localAiButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        controls.addView(backendRow)
        configureHostedAiButton = Button(this@GatewayActivity).apply {
            text = "SET UP HOSTED AI"
            setOnClickListener { showHostedAiSetup() }
        }
        controls.addView(configureHostedAiButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(48),
        ))
        talkToForesightButton = Button(this@GatewayActivity).apply {
            text = "TALK TO FORESIGHT"
            textSize = 18f
            setOnClickListener {
                if (conversationBackendMode == ConversationBackendMode.LOCAL &&
                    (gemmaModelInstaller.installState() is GemmaInstallState.NotInstalled || gemmaModelInstaller.installState() is GemmaInstallState.Failed)
                ) {
                    showLocalAiSetup()
                } else {
                    startVoiceTurn()
                }
            }
        }
        controls.addView(talkToForesightButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(64),
        ))
        val wakeRow = LinearLayout(this@GatewayActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        wakeOnButton = Button(this@GatewayActivity).apply {
            text = "VOICE WAKE ON"
            textSize = 14f
            setOnClickListener { setWakeEnabled(true) }
        }
        wakeOffButton = Button(this@GatewayActivity).apply {
            text = "OFF"
            textSize = 14f
            setOnClickListener { setWakeEnabled(false) }
        }
        wakeRow.addView(wakeOnButton, LinearLayout.LayoutParams(0, dp(44), 2f))
        wakeRow.addView(wakeOffButton, LinearLayout.LayoutParams(0, dp(44), 1f))
        controls.addView(wakeRow)
        wakeStatusText = TextView(this@GatewayActivity).apply {
            textSize = 13f
            setPadding(0, dp(2), 0, dp(4))
        }
        controls.addView(wakeStatusText)
        voiceStatusText = TextView(this@GatewayActivity).apply {
            textSize = 14f
            setPadding(0, dp(4), 0, dp(8))
        }
        controls.addView(voiceStatusText)
        locationStatusText = TextView(this@GatewayActivity).apply {
            textSize = 14f
            setPadding(0, dp(2), 0, dp(8))
        }
        controls.addView(locationStatusText)

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
        val binder = captureBinder
        if (!previewSurfaceReady || binder == null) return

        val goProActive = binder.goProIngressSnapshot().status != GoProSourceStatus.STOPPED
        val goProOwnsFieldCapture =
            operatingMode == GatewayOperatingMode.FIELD &&
                binder.activeFieldMediaSource() == LocalMediaSourceId.GOPRO_RTMP

        if (goProActive && (goProOwnsFieldCapture || !isLocalCaptureActive(CaptureForegroundService.currentStatus.lifecycle))) {
            attachGoProPreview()
        } else {
            attachPhonePreview()
        }
        updateVisualizationRuntime()
    }

    private fun attachPhonePreview() {
        if (previewOwner == PreviewOwner.PHONE) return
        if (previewOwner == PreviewOwner.GOPRO) {
            captureBinder?.detachGoProPreviewSurface(previewSurface.holder.surface)
            Log.i(TAG, "Preview ownership changed GOPRO -> PHONE; RTMP ingress remains active.")
        }
        captureBinder?.attachPreview(previewSurface)
        previewOwner = PreviewOwner.PHONE
    }

    private fun attachGoProPreview() {
        if (previewOwner == PreviewOwner.GOPRO) return
        if (previewOwner == PreviewOwner.PHONE) {
            captureBinder?.detachPreview(previewSurface)
            Log.i(TAG, "Preview ownership changed PHONE -> GOPRO while phone capture is inactive.")
        }
        captureBinder?.attachGoProPreviewSurface(previewSurface.holder.surface)
        previewOwner = PreviewOwner.GOPRO
    }

    private fun detachPreviewOwner() {
        when (previewOwner) {
            PreviewOwner.PHONE -> captureBinder?.detachPreview(previewSurface)
            PreviewOwner.GOPRO -> captureBinder?.detachGoProPreviewSurface(previewSurface.holder.surface)
            PreviewOwner.NONE -> Unit
        }
        previewOwner = PreviewOwner.NONE
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
            .putExtra(CaptureForegroundService.EXTRA_FIELD_MEDIA_SOURCE, selectedFieldMediaSource.name)
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

    private fun selectFieldMediaSource(source: LocalMediaSourceId) {
        if (isLocalCaptureActive(CaptureForegroundService.currentStatus.lifecycle)) return
        selectedFieldMediaSource = source
        preferences().edit().putString(PREF_FIELD_MEDIA_SOURCE, source.name).apply()
        Log.i(TAG, "FIELD media source selected: $source")
        renderStatus()
    }

    private fun selectVisualizationMode(mode: GatewayVisualizationMode) {
        if (visualizationMode == mode) return
        visualizationMode = mode
        preferences().edit().putString(PREF_VISUALIZATION_MODE, mode.name).apply()
        updateVisualizationRuntime()
        Log.i(TAG, "Visualization mode selected: ${mode.name}")
    }

    private fun selectConversationBackend(mode: ConversationBackendMode) {
        if (conversationBackendMode == mode) return
        conversationBackendMode = mode
        preferences().edit().putString(PREF_CONVERSATION_BACKEND_MODE, mode.name).apply()
        Log.i(TAG, "Conversation backend selected: $mode")
        if (mode == ConversationBackendMode.HOSTED) Log.i(GOOGLE_AI_TAG, "GOOGLE_AI_BACKEND selected=HOSTED")
        renderVoiceState(voiceTurnController.state)
    }

    /**
     * Reconciles presentation only. CaptureForegroundService.currentStatus remains the single
     * source of capture/session authority; this method never starts or stops capture work.
     */
    private fun updateVisualizationRuntime() {
        val captureActive = isLocalCaptureActive(CaptureForegroundService.currentStatus.lifecycle)
        val compassActive = GatewayVisualizationRuntimePolicy.compassRuntimeActive(
            visualizationMode = visualizationMode,
            captureActive = captureActive,
            activityResumed = activityResumed,
        )
        compassRibbon.setVisibilityIfChanged(
            if (compassActive) View.VISIBLE else View.GONE,
        )
        standardVisualizationButton.setAlphaIfChanged(
            if (visualizationMode == GatewayVisualizationMode.STANDARD) 1f else 0.55f,
        )
        visionVisualizationButton.setAlphaIfChanged(
            if (visualizationMode == GatewayVisualizationMode.VISION) 1f else 0.55f,
        )
        augmentedRealityVisualizationButton.setAlphaIfChanged(
            if (visualizationMode == GatewayVisualizationMode.AUGMENTED_REALITY) 1f else 0.55f,
        )

        if (compassActive) {
            headingProvider.start()
        } else {
            headingProvider.stop()
        }
        updateLiveVisionRuntime(captureActive)
    }

    /** Reconciles only Vision presentation work against the existing capture lifecycle authority. */
    private fun updateLiveVisionRuntime(captureActive: Boolean) {
        if (!::visionDetectionOverlay.isInitialized || !::ocrOverlay.isInitialized || !::gestureTargetOverlay.isInitialized ||
            !::surfaceViewFrameSampler.isInitialized
        ) return
        val shouldRun = LiveVisionRuntimePolicy.runtimeActive(
            visualizationMode = visualizationMode,
            captureActive = captureActive,
            activityResumed = activityResumed,
        )
        when (val transition = liveVisionRuntime.reconcile(shouldRun)) {
            is LiveVisionRuntimeTransition.Started -> {
                latestDetectionState.clear()
                visionDetectionOverlay.clear()
                visionDetectionOverlay.setVisibilityIfChanged(View.VISIBLE)
                ocrOverlay.clear()
                ocrOverlay.setVisibilityIfChanged(View.VISIBLE)
                gestureTargetOverlay.clear()
                gestureTargetOverlay.setVisibilityIfChanged(View.VISIBLE)
                startLiveVisionSampling(transition.generation)
            }

            LiveVisionRuntimeTransition.Stopped -> {
                latestDetectionState.clear()
                latestOcrState.clear()
                latestSceneEvidenceState.clear()
                surfaceViewFrameSampler.stop()
                visionDetectionOverlay.clear()
                visionDetectionOverlay.setVisibilityIfChanged(View.GONE)
                ocrOverlay.clear()
                ocrOverlay.setVisibilityIfChanged(View.GONE)
                gestureTargetOverlay.clear()
                gestureTargetOverlay.setVisibilityIfChanged(View.GONE)
            }

            LiveVisionRuntimeTransition.Unchanged -> {
                if (!shouldRun) {
                    visionDetectionOverlay.clear()
                    visionDetectionOverlay.setVisibilityIfChanged(View.GONE)
                    ocrOverlay.clear()
                    ocrOverlay.setVisibilityIfChanged(View.GONE)
                    gestureTargetOverlay.clear()
                    gestureTargetOverlay.setVisibilityIfChanged(View.GONE)
                } else {
                    visionDetectionOverlay.setVisibilityIfChanged(View.VISIBLE)
                    ocrOverlay.setVisibilityIfChanged(View.VISIBLE)
                    gestureTargetOverlay.setVisibilityIfChanged(View.VISIBLE)
                    liveVisionRuntime.activeGeneration()?.let(::startLiveVisionSampling)
                }
            }
        }
    }

    private fun startLiveVisionSampling(runtimeGeneration: Long) {
        if (!previewSurfaceReady || !previewSurface.holder.surface.isValid) return
        surfaceViewFrameSampler.start(
            surfaceView = previewSurface,
            runtimeGeneration = runtimeGeneration,
            canRequestFrame = onDeviceVisionDetector::isReadyForFrame,
        ) { frame ->
            val ocrFrame = frame.copy(bitmap = frame.bitmap.copy(frame.bitmap.config ?: android.graphics.Bitmap.Config.ARGB_8888, false))
            onDeviceVisionDetector.submit(
                frame = frame,
                onSnapshot = { snapshot ->
                    runOnUiThread {
                        if (liveVisionRuntime.accepts(snapshot)) {
                            latestDetectionState.publish(snapshot)
                            visionDetectionOverlay.setSnapshot(snapshot)
                            publishSceneEvidence(runtimeGeneration)
                        }
                    }
                },
                onFailure = { error ->
                    runOnUiThread {
                        if (VisionFailurePresentationPolicy.shouldClearOverlay(
                                activeGeneration = liveVisionRuntime.activeGeneration(),
                                failedGeneration = runtimeGeneration,
                            )
                        ) {
                            latestDetectionState.clear()
                            visionDetectionOverlay.clear()
                            Log.w(TAG, "On-device Vision result failed; capture continues.", error)
                        }
                    }
                },
            )
            onDeviceOcrRecognizer.submit(
                frame = ocrFrame,
                onObservation = { observation -> runOnUiThread {
                    if (liveVisionRuntime.activeGeneration() == observation.runtimeGeneration) {
                        latestOcrState.publish(observation)
                        ocrOverlay.setObservation(observation)
                        publishSceneEvidence(runtimeGeneration)
                    }
                } },
                onFailure = { _ -> Unit },
            )
        }
    }

    private fun publishSceneEvidence(runtimeGeneration: Long) {
        val scene = sceneEvidenceAssembler.assemble(runtimeGeneration)
        latestSceneEvidenceState.publish(scene)
        scene.location?.let { location ->
            Log.i(
                TAG,
                "FORESIGHT_SCENE_LOCATION_INCLUDED accuracyMeters=${location.horizontalAccuracyMeters ?: "unknown"} " +
                    "ageMs=${location.ageMillis(SystemClock.elapsedRealtime())}",
            )
        }
        Log.i(TAG, "FORESIGHT_SCENE_EVIDENCE_READY detections=${scene.detections?.detections?.size ?: 0} ocrRegions=${scene.ocrObservation?.regions?.size ?: 0} hasTarget=${scene.selectedTarget != null} hasHeading=${scene.heading != null} hasLocation=${scene.location != null} conceptMatches=${scene.visualConceptMatches.size}")
    }

    private fun renderStatus() {
        updateLocationRuntime()
        renderLocationStatus()
        val syncPresentation = refreshSyncableEventFromService()
        val status = CaptureForegroundService.currentStatus
        updateVisualizationRuntime()
        val metadata = status.metadata
        val fieldReadiness = captureBinder?.fieldEventReadiness() ?: com.foresight.gateway.capture.FieldEventReadiness.NOT_CAPTURING
        val presentation = GatewayPresentation(operatingMode, status.lifecycle, eventUiState.event, fieldReadiness)
        statusText.setTextIfChanged(buildString {
            append("Mode: ${operatingMode.name}")
            if (operatingMode == GatewayOperatingMode.FIELD) append("\nField source: ${selectedFieldMediaSource.name}")
            append("\nLocal Capture: ${presentation.captureLabel}")
            append("\nEvent: ${presentation.eventLabel}")
            eventUiState.event.eventId?.let { append(" (${it.take(8)})") }
            status.detail?.let { append("\nCapture detail: $it") }
            metadata?.captureSessionId?.let { append("\nSession: ${it.take(8)}") }
            eventUiState.detail?.let { append("\nEvent control: $it") }
            if (operatingMode == GatewayOperatingMode.FIELD) append("\nEvent readiness: ${fieldReadiness.reason}")
            append("\nSync: ${syncPresentation.syncState?.name ?: syncUiState.state.name}")
            append(" (${syncPresentation.reason})")
        })
        overlayStatusText.setTextIfChanged(buildString {
            append("Mode: ${operatingMode.name}")
            append("\nLocal: ${presentation.captureLabel}")
            append("\nEvent: ${presentation.eventLabel}")
            eventUiState.event.eventId?.let { append("\nID: ${it.take(8)}") }
        })
        setLightIfChanged(captureLight, presentation.captureLightOn, Color.RED, captureLightRenderCache)
        setLightIfChanged(eventLight, presentation.eventLightOn, Color.rgb(0, 180, 0), eventLightRenderCache)
        startCaptureButton.setEnabledIfChanged(presentation.startCaptureEnabled)
        endCaptureButton.setEnabledIfChanged(presentation.endCaptureEnabled)
        startEventButton.setEnabledIfChanged(presentation.startEventEnabled)
        endEventButton.setEnabledIfChanged(presentation.endEventEnabled)
        quickEventButton.setEnabledIfChanged(presentation.quickEventEnabled)
        if (::voiceTurnController.isInitialized) renderVoiceState(voiceTurnController.state)
        labModeButton.setEnabledIfChanged(!presentation.localCaptureActive)
        fieldModeButton.setEnabledIfChanged(!presentation.localCaptureActive)
        labModeButton.setAlphaIfChanged(if (operatingMode == GatewayOperatingMode.LAB) 1f else 0.55f)
        fieldModeButton.setAlphaIfChanged(if (operatingMode == GatewayOperatingMode.FIELD) 1f else 0.55f)
        val sourceLocked = presentation.localCaptureActive
        phoneFieldSourceButton.setEnabledIfChanged(!sourceLocked)
        goProFieldSourceButton.setEnabledIfChanged(!sourceLocked)
        phoneFieldSourceButton.setAlphaIfChanged(if (selectedFieldMediaSource == LocalMediaSourceId.PHONE_CAMERA) 1f else 0.55f)
        goProFieldSourceButton.setAlphaIfChanged(if (selectedFieldMediaSource == LocalMediaSourceId.GOPRO_RTMP) 1f else 0.55f)
        val goProIngressStopped = (captureBinder?.goProIngressSnapshot() ?: CaptureForegroundService.currentGoProStatus).status == GoProSourceStatus.STOPPED
        normalLanGoProNetworkButton.setEnabledIfChanged(goProIngressStopped)
        hotspotGoProNetworkButton.setEnabledIfChanged(goProIngressStopped)
        normalLanGoProNetworkButton.setAlphaIfChanged(if (selectedGoProNetworkMode == GoProNetworkMode.NORMAL_LAN) 1f else 0.55f)
        hotspotGoProNetworkButton.setAlphaIfChanged(if (selectedGoProNetworkMode == GoProNetworkMode.PHONE_HOTSPOT) 1f else 0.55f)
        syncEventButton.setVisibilityIfChanged(if (syncPresentation.buttonVisible) View.VISIBLE else View.GONE)
        syncEventButton.setEnabledIfChanged(syncPresentation.buttonEnabled && !syncAllInFlight)
        syncEventButton.setTextIfChanged(
            if (syncPresentation.syncState == EventMediaSyncState.FAILED) "RETRY SYNC" else "SYNC EVENT",
        )
        val history = captureBinder?.syncHistory().orEmpty()
        val summary = captureBinder?.syncSummary() ?: EventMediaSyncSummary(0, 0, 0)
        syncAllPendingButton.setEnabledIfChanged(
            !syncAllInFlight && summary.readyLocalOnlyCount + summary.retryableCount > 0,
        )
        syncSummaryText.setTextIfChanged(formatSyncSummary(summary))
        renderSyncHistory(history, captureBinder?.syncableEventIds().orEmpty())
        logSyncUiDecision(syncPresentation)
        captureBinder?.updateEventState(presentation.event.state)
        renderGoProIngress(captureBinder?.goProIngressSnapshot() ?: CaptureForegroundService.currentGoProStatus)
        attachPreviewIfReady()
        clearStoppedPreviewIfNeeded(status.lifecycle, captureBinder?.goProIngressSnapshot() ?: CaptureForegroundService.currentGoProStatus)
    }

    private fun updateLocationRuntime() {
        if (!::foregroundLocationProvider.isInitialized) return
        if (activityResumed) foregroundLocationProvider.start() else foregroundLocationProvider.stop()
    }

    private fun freshSceneLocation(): SceneLocation? {
        val location = latestSceneLocationState.latestLocation() ?: return null
        val ageMillis = location.ageMillis(SystemClock.elapsedRealtime())
        if (ageMillis <= SceneLocationFreshnessPolicy.SCENE_FRESH_MAX_AGE_MILLIS) {
            locationStaleLogged = false
            return location
        }
        if (!locationStaleLogged) {
            Log.i(TAG, "FORESIGHT_LOCATION_STALE ageMs=$ageMillis")
            locationStaleLogged = true
        }
        return null
    }

    private fun sceneEvidenceForConversation() =
        latestSceneEvidenceState.freshSnapshot(SystemClock.elapsedRealtimeNanos(), SCENE_EVIDENCE_FRESHNESS_NANOS)
            ?: freshSceneLocation()?.let { location ->
                com.foresight.gateway.vision.SceneEvidenceSnapshot(
                    generation = 0L,
                    capturedAtNanos = SystemClock.elapsedRealtimeNanos(),
                    heading = latestHeadingState.takeIf(HeadingState::isAvailable),
                    location = location,
                )
            }

    private fun mapsLocationForConversation(): SceneLocation? =
        latestSceneLocationState.freshLocation(SceneLocationFreshnessPolicy.MAPS_FRESH_MAX_AGE_MILLIS)

    private fun renderLocationStatus() {
        if (!::locationStatusText.isInitialized) return
        val permission = ForegroundLocationPermissionPolicy.resolve(
            fineGranted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED,
            coarseGranted = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED,
        )
        val label = when {
            freshSceneLocation() == null -> "Location: Unavailable"
            permission == ForegroundLocationPermission.PRECISE -> freshSceneLocation()?.let { "Location: +/-${it.horizontalAccuracyMeters?.toInt() ?: "?"} m, ${com.foresight.gateway.vision.SceneLocationQualityPolicy.classify(it.horizontalAccuracyMeters)}, ${it.provider ?: "unknown"}" } ?: "Location: Unavailable"
            permission == ForegroundLocationPermission.APPROXIMATE -> freshSceneLocation()?.let { "Location: Approximate, +/-${it.horizontalAccuracyMeters?.toInt() ?: "?"} m" } ?: "Location: Approximate"
            else -> "Location: Unavailable"
        }
        locationStatusText.setTextIfChanged(label)
    }

    private fun startGoProIngress() {
        startForegroundService(
            Intent(this, CaptureForegroundService::class.java)
                .setAction(CaptureForegroundService.ACTION_START_GOPRO_INGRESS)
                .putExtra(CaptureForegroundService.EXTRA_GOPRO_NETWORK_MODE, selectedGoProNetworkMode.name),
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

    private fun startGoProRecording() {
        runCatching { captureBinder?.startGoProRecording() ?: error("Gateway service is not connected.") }
            .onFailure { error -> statusText.text = "GoPro recording unavailable: ${error.message}" }
        renderStatus()
    }

    private fun stopGoProRecording() {
        runCatching { captureBinder?.stopGoProRecording() ?: error("Gateway service is not connected.") }
            .onFailure { error -> statusText.text = "GoPro recording stop failed: ${error.message}" }
        renderStatus()
    }

    private fun selectGoProNetworkMode(mode: GoProNetworkMode) {
        selectedGoProNetworkMode = mode
        preferences().edit().putString(PREF_GOPRO_NETWORK_MODE, mode.name).apply()
        renderStatus()
    }

    private fun renderGoProIngress(snapshot: GoProIngressSnapshot) {
        goProDestinationText.setTextIfChanged(
            "${selectedGoProNetworkMode.displayName}: ${snapshot.destination ?: "Start to discover target IPv4"}",
        )
        goProStatusText.setTextIfChanged(buildString {
            append("Status: ${snapshot.status}")
            snapshot.detail?.let { append("\nDetail: $it") }
            snapshot.metadata?.let {
                append("\nVideo: ${it.videoSummary()}")
                append("\nAudio: ${it.audioSummary()}")
            }
            snapshot.mediaDiagnostics?.let { diagnostics ->
                append("\nGeneration: ${diagnostics.generationId}")
                append("\nVideo config: ${formatConfig(diagnostics.videoConfigReady, diagnostics.videoExtradataBytes)}")
                append("\nAudio config: ${formatConfig(diagnostics.audioConfigReady, diagnostics.audioExtradataBytes)}")
            }
            snapshot.encodedTransportDiagnostics?.let { transport ->
                append("\nEncoded transport:")
                append("\nVideo representation: ${transport.videoRepresentation}")
                append("\nAudio representation: ${transport.audioRepresentation}")
            }
            snapshot.previewDiagnostics?.let { preview ->
                append("\nGoPro preview: ${preview.state}")
                preview.decoderName?.let { append("\nDecoder: $it") }
                append("\nPreview generation: ${preview.generationId ?: "Unavailable"}")
                preview.detail?.let { append("\nPreview detail: $it") }
            }
            snapshot.recordingDiagnostics?.let { recording ->
                append("\nGoPro recording: ${recording.state}")
                append("\nRecording generation: ${recording.generationId ?: "Unavailable"}")
                recording.outputFileName?.let { append("\nRecording output: $it (${recording.fileSizeBytes} bytes)") }
                recording.detail?.let { append("\nRecording detail: $it") }
            }
        })
        goProDiagnosticsText.setTextIfChanged(buildString {
            snapshot.mediaDiagnostics?.let { diagnostics ->
                append("Video packets: ${diagnostics.videoPacketCount}")
                append("\nVideo keyframes: ${diagnostics.videoKeyframeCount}")
                append("\nLast video PTS: ${formatTimestamp(diagnostics.lastVideoPtsUs)}")
                append("\nLast video packet: ${diagnostics.lastVideoPacketBytes} bytes")
                append("\nAudio packets: ${diagnostics.audioPacketCount}")
                append("\nLast audio PTS: ${formatTimestamp(diagnostics.lastAudioPtsUs)}")
                append("\nLast audio packet: ${diagnostics.lastAudioPacketBytes} bytes")
            }
            snapshot.encodedTransportDiagnostics?.let { transport ->
                if (isNotEmpty()) append('\n')
                append("Queue: ${transport.queueDepth} / ${transport.queueCapacity}")
                append("\nPeak: ${transport.peakQueueDepth}")
                append("\nVideo samples/bytes: ${transport.videoSamplesReceived} / ${transport.videoBytesReceived}")
                append("\nAudio samples/bytes: ${transport.audioSamplesReceived} / ${transport.audioBytesReceived}")
                append("\nDropped samples: ${transport.samplesDropped}")
            }
            snapshot.previewDiagnostics?.let { preview ->
                if (isNotEmpty()) append('\n')
                append("Preview frames queued/rendered/dropped: ${preview.framesQueued} / ${preview.framesRendered} / ${preview.framesDropped}")
                append("\nPreview queue: ${preview.queueDepth} / ${preview.queueCapacity}")
            }
            snapshot.recordingDiagnostics?.let { recording ->
                if (isNotEmpty()) append('\n')
                append("Recording duration: ${recording.durationUs / 1_000_000.0}s")
                append("\nRecording video/audio samples: ${recording.videoSamplesWritten} / ${recording.audioSamplesWritten}")
                append("\nRecording queue: ${recording.queueDepth} / ${recording.queueCapacity}; peak ${recording.peakQueueDepth}")
            }
        })
        startGoProButton.setEnabledIfChanged(snapshot.status == GoProSourceStatus.STOPPED)
        stopGoProButton.setEnabledIfChanged(snapshot.status != GoProSourceStatus.STOPPED)
        val recordingActive = snapshot.recordingDiagnostics?.state in setOf(
            com.foresight.gateway.gopro.GoProRecordingState.ARMING,
            com.foresight.gateway.gopro.GoProRecordingState.WAITING_FOR_KEYFRAME,
            com.foresight.gateway.gopro.GoProRecordingState.RECORDING,
            com.foresight.gateway.gopro.GoProRecordingState.FINALIZING,
        )
        val fieldOwnsGoProRecording = captureBinder?.activeFieldMediaSource() == LocalMediaSourceId.GOPRO_RTMP
        startGoProRecordingButton.setEnabledIfChanged(
            snapshot.status == GoProSourceStatus.LIVE && !recordingActive && !fieldOwnsGoProRecording,
        )
        stopGoProRecordingButton.setEnabledIfChanged(recordingActive && !fieldOwnsGoProRecording)
    }

    private fun formatConfig(ready: Boolean, extradataBytes: Int): String =
        "${if (ready) "READY" else "NOT READY"} ($extradataBytes bytes)"

    private fun formatTimestamp(timestampUs: Long?): String = timestampUs?.let { "$it us" } ?: "Unavailable"

    private fun clearStoppedPreviewIfNeeded(
        lifecycle: com.foresight.gateway.transport.StreamLifecycle,
        goPro: GoProIngressSnapshot,
    ) {
        val stopped = lifecycle == com.foresight.gateway.transport.StreamLifecycle.IDLE ||
            lifecycle == com.foresight.gateway.transport.StreamLifecycle.ERROR
        val goProPreviewActive = previewOwner == PreviewOwner.GOPRO &&
            goPro.previewDiagnostics?.state in setOf(
                GoProPreviewState.WAITING_FOR_STREAM,
                GoProPreviewState.WAITING_FOR_CONFIG,
                GoProPreviewState.WAITING_FOR_KEYFRAME,
                GoProPreviewState.CONFIGURING,
                GoProPreviewState.DECODING,
            )
        if (!stopped || goProPreviewActive) {
            stoppedPreviewCleared = false
            updateStoppedPreviewOverlayVisibility(View.GONE, goPro)
            return
        }
        if (!stoppedPreviewCleared) {
            // Do not draw into the SurfaceView with Canvas: RootEncoder owns that buffer queue
            // while streaming. A regular overlay leaves the EGL preview producer untouched.
            updateStoppedPreviewOverlayVisibility(View.VISIBLE, goPro)
            stoppedPreviewCleared = true
            Log.i(TAG, "Stopped-state preview cover shown without writing to the SurfaceView.")
        }
    }

    private fun updateStoppedPreviewOverlayVisibility(visibility: Int, goPro: GoProIngressSnapshot) {
        val previous = stoppedPreviewOverlay.visibility
        if (previous == visibility) return
        stoppedPreviewOverlay.visibility = visibility

        val status = CaptureForegroundService.currentStatus
        Log.i(
            TAG,
            "PREVIEW_COVER_VISIBILITY old=${visibilityName(previous)} new=${visibilityName(visibility)} " +
                "status=${status.lifecycle} statusDetail=${status.detail} previewOwner=$previewOwner " +
                "ingress=${goPro.status} surfaceValid=${previewSurface.holder.surface.isValid} " +
                "activityLifecycle=$activityLifecycleState " +
                "elapsedMs=${android.os.SystemClock.elapsedRealtime()}",
        )
    }

    private fun visibilityName(visibility: Int): String = when (visibility) {
        View.VISIBLE -> "VISIBLE"
        View.INVISIBLE -> "INVISIBLE"
        View.GONE -> "GONE"
        else -> visibility.toString()
    }

    private fun logPreviewSurface(stage: String) {
        Log.i(
            TAG,
            "Preview surface $stage: valid=${previewSurface.holder.surface.isValid}, " +
                "visible=${previewSurface.visibility == View.VISIBLE}, alpha=${previewSurface.alpha}, " +
                "dimensions=${previewSurface.width}x${previewSurface.height}.",
        )
    }

    private enum class PreviewOwner {
        NONE,
        PHONE,
        GOPRO,
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

    private fun startVoiceTurn() {
        Log.i(TAG, "VOICE_BEGIN_TURN elapsedMs=${SystemClock.elapsedRealtime()}")
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "VOICE_PERMISSION_DENIED")
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_VOICE_PERMISSION)
            voiceStatusText.setTextIfChanged("Microphone permission is required for local voice commands.")
            return
        }
        voiceTurnController.startPushToTalk()
    }

    private fun startVoiceTurnFromWake(): Boolean {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        return voiceTurnController.startWakeAcknowledgedTurn(
            acknowledgement = wakeAcknowledgementSelector.next(),
            onAcknowledgementStarted = { Log.i("ForesightWake", "FORESIGHT_WAKE_ACK_START") },
            onAcknowledgementCompleted = { Log.i("ForesightWake", "FORESIGHT_WAKE_ACK_COMPLETE") },
            onAcknowledgementFailed = { Log.w("ForesightWake", "FORESIGHT_WAKE_ACK_FAILURE") },
        )
    }

    private fun setWakeEnabled(enabled: Boolean) {
        wakePreferenceStore.setEnabled(enabled)
        wakeRuntimeController.setEnabled(enabled, wakeRuntimeInputs())
        if (enabled && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_VOICE_PERMISSION)
        }
    }

    private fun wakeRuntimeInputs(): WakeRuntimeInputs = WakeRuntimeInputs(
        activityResumed = activityResumed,
        recordAudioPermissionGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
        microphoneAvailability = voiceMicrophoneAvailability(logDecision = false),
        voiceState = voiceTurnController.state,
    )

    private fun reconcileWakeRuntime() {
        if (::wakeRuntimeController.isInitialized) wakeRuntimeController.reconcile(wakeRuntimeInputs())
    }

    private fun renderWakeRuntimeState(state: WakeRuntimeState) {
        if (!::wakeStatusText.isInitialized) return
        wakeStatusText.setTextIfChanged(
            when {
                !wakeRuntimeController.enabled -> "Wake: Off"
                state == WakeRuntimeState.LISTENING -> "Wake: Listening"
                state == WakeRuntimeState.FAILED -> "Wake: Unavailable"
                else -> "Wake: Paused"
            },
        )
        wakeOnButton.setAlphaIfChanged(if (wakeRuntimeController.enabled) 1f else 0.55f)
        wakeOffButton.setAlphaIfChanged(if (wakeRuntimeController.enabled) 0.55f else 1f)
    }

    private fun showHostedAiSetup() {
        Log.i(GOOGLE_AI_TAG, "GOOGLE_AI_SETUP_OPENED")
        val apiKey = EditText(this).apply {
            hint = "Google API key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val mapsApiKey = EditText(this).apply {
            hint = "Google Maps Platform API key (optional)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val setupStatus = TextView(this).apply { text = connectionMessage(googleHostedConversationEngine.connectionState()) }
        val setupContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
            addView(apiKey)
            addView(mapsApiKey)
            addView(setupStatus)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Hosted AI Setup")
            .setMessage(
                "Private developer MVP only. The key is encrypted with Android Keystore storage and is sent only " +
                    "to Google over HTTPS. It is never shown again, logged, or included in the app package.",
            )
            .setView(setupContent)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save and Test", null)
            .create()
        dialog.setOnShowListener {
            val saveAndTest = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            saveAndTest.setOnClickListener {
                val enteredKey = apiKey.text.toString().trim()
                Log.i(GOOGLE_AI_TAG, "GOOGLE_AI_SAVE_TEST_CLICKED keyPresent=${enteredKey.isNotBlank()}")
                if (enteredKey.isBlank()) {
                    setupStatus.text = "Enter a Google API key."
                    renderVoiceStatus("Enter a Google API key.")
                    renderVoiceState(voiceTurnController.state)
                    return@setOnClickListener
                }
                Log.i(GOOGLE_AI_TAG, "GOOGLE_AI_KEY_SAVE_START")
                runCatching { googleApiKeyStore.write(enteredKey) }
                    .onSuccess { Log.i(GOOGLE_AI_TAG, "GOOGLE_AI_KEY_SAVE_SUCCESS") }
                    .onFailure {
                        Log.e(GOOGLE_AI_TAG, "GOOGLE_AI_KEY_SAVE_FAILURE type=${it.javaClass.simpleName}")
                        setupStatus.text = "Could not securely save the Google API key."
                        renderVoiceStatus("Foresight AI could not securely save the key.")
                        renderVoiceState(voiceTurnController.state)
                        return@setOnClickListener
                    }
                mapsApiKey.text.toString().trim().takeIf(String::isNotBlank)?.let { enteredMapsKey ->
                    runCatching { googleMapsPlatformKeyStore.write(enteredMapsKey) }
                        .onFailure {
                            setupStatus.text = "Could not securely save the Google Maps Platform key."
                            return@setOnClickListener
                        }
                }
                apiKey.isEnabled = false
                mapsApiKey.isEnabled = false
                saveAndTest.isEnabled = false
                setupStatus.text = "Hosted AI: Checking connection..."
                renderVoiceStatus("Hosted AI: Checking connection...")
                renderVoiceState(voiceTurnController.state)
                googleHostedConversationEngine.testConnection { connection ->
                    runOnUiThread {
                        apiKey.isEnabled = true
                        mapsApiKey.isEnabled = true
                        saveAndTest.isEnabled = true
                        setupStatus.text = connectionMessage(connection)
                        renderVoiceStatus(connectionMessage(connection))
                        renderVoiceState(voiceTurnController.state)
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showLocalAiSetup() {
        val accessToken = EditText(this).apply {
            hint = "Hugging Face read token (not stored)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("Install Local Foresight AI")
            .setMessage(
                "This downloads the official ${GemmaModelSpec.artifactFileName} model (about 3.66 GB) " +
                    "into app-private storage. Review and accept the Gemma Terms at ${GemmaModelSpec.termsUrl}, " +
                    "then provide a Hugging Face read token for the gated download. The token is used once and is not stored.",
            )
            .setView(accessToken)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Accept Terms and Install") { _, _ ->
                gemmaModelInstaller.acceptTerms()
                renderVoiceStatus("Checking Hugging Face access...")
                gemmaModelInstaller.install(accessToken.text.toString()) { result ->
                    runOnUiThread {
                        liteRtLmConversationEngine.refreshReadiness()
                        renderVoiceState(voiceTurnController.state)
                    }
                }
            }
            .show()
    }

    private fun voiceMicrophoneAvailability(logDecision: Boolean = true): com.foresight.gateway.voice.MicrophoneAvailability {
        val availability = MicrophoneArbiter.availability(
            captureActive = isLocalCaptureActive(CaptureForegroundService.currentStatus.lifecycle),
            activeSource = captureBinder?.activeFieldMediaSource()
                ?: if (isLocalCaptureActive(CaptureForegroundService.currentStatus.lifecycle)) {
                    LocalMediaSourceId.PHONE_CAMERA
                } else {
                    null
                },
        )
        if (logDecision) Log.i(TAG, "VOICE_MIC_ARBITER_${if (availability is com.foresight.gateway.voice.MicrophoneAvailability.Available) "ALLOWED" else "DENIED"}")
        return availability
    }

    private fun renderVoiceState(state: VoiceRuntimeState) {
        if (!::talkToForesightButton.isInitialized) return
        if (lastRenderedVoiceState != state) {
            Log.i(TAG, "VOICE_STATE old=$lastRenderedVoiceState new=$state elapsedMs=${SystemClock.elapsedRealtime()}")
            when (state) {
                VoiceRuntimeState.PROCESSING -> Log.i(TAG, "VOICE_PROCESSING elapsedMs=${SystemClock.elapsedRealtime()}")
                VoiceRuntimeState.SPEAKING -> Log.i(TAG, "VOICE_SPEAKING elapsedMs=${SystemClock.elapsedRealtime()}")
                VoiceRuntimeState.IDLE -> Log.i(TAG, "VOICE_IDLE elapsedMs=${SystemClock.elapsedRealtime()}")
                VoiceRuntimeState.LISTENING_FOR_COMMAND -> Unit
            }
            lastRenderedVoiceState = state
        }
        val readiness = voiceTurnController.conversationReadiness()
        val installState = gemmaModelInstaller.installState()
        val localInstalling = installState !is GemmaInstallState.NotInstalled && installState !is GemmaInstallState.Installed && installState !is GemmaInstallState.Failed
        val localSelected = conversationBackendMode == ConversationBackendMode.LOCAL
        val talkEligibility = if (localSelected) {
            // Preserve LOCAL's established presentation rule; hosted has independent readiness.
            val eligible = state == VoiceRuntimeState.IDLE &&
                readiness in setOf(ConversationReadiness.READY, ConversationReadiness.READY_NOT_LOADED) &&
                !localInstalling
            com.foresight.gateway.voice.HostedTalkEligibility(eligible, if (eligible) "ready" else "local_ai_or_voice_unavailable")
        } else {
            HostedTalkEligibilityPolicy.evaluate(
                activityResumed = activityResumed,
                runtimeState = state,
                inputReadiness = voiceTurnController.inputReadiness(),
                hostedReadiness = googleHostedConversationEngine.connectionState(),
                microphoneAvailability = voiceMicrophoneAvailability(logDecision = false),
            )
        }
        talkToForesightButton.setEnabledIfChanged(talkEligibility.eligible)
        if (!localSelected) {
            val diagnostic = "eligible=${talkEligibility.eligible} reason=${talkEligibility.reason}"
            if (diagnostic != lastHostedTalkEligibilityDiagnostic) {
                Log.i(GOOGLE_AI_TAG, "GOOGLE_AI_TALK_ELIGIBILITY $diagnostic")
                lastHostedTalkEligibilityDiagnostic = diagnostic
            }
        }
        talkToForesightButton.setTextIfChanged(
            when (state) {
                VoiceRuntimeState.IDLE -> if (localSelected) installButtonLabel(installState) else "TALK TO FORESIGHT"
                VoiceRuntimeState.LISTENING_FOR_COMMAND -> "LISTENING..."
                VoiceRuntimeState.PROCESSING -> "THINKING..."
                VoiceRuntimeState.SPEAKING -> "SPEAKING..."
            },
        )
        when (state) {
            VoiceRuntimeState.LISTENING_FOR_COMMAND -> renderVoiceStatus("Listening...")
            VoiceRuntimeState.PROCESSING -> renderVoiceStatus(
                if (readiness == ConversationReadiness.LOADING) "Preparing AI..." else "Thinking...",
            )
            VoiceRuntimeState.SPEAKING -> renderVoiceStatus("Speaking...")
            VoiceRuntimeState.IDLE -> if (voiceStatusClear == null) {
                voiceStatusText.setTextIfChanged(
                    if (localSelected) installStatusText(installState) else hostedStatusText(readiness),
                )
            }
        }
        hostedAiButton.setAlphaIfChanged(if (conversationBackendMode == ConversationBackendMode.HOSTED) 1f else 0.55f)
        localAiButton.setAlphaIfChanged(if (conversationBackendMode == ConversationBackendMode.LOCAL) 1f else 0.55f)
        configureHostedAiButton.setVisibilityIfChanged(if (conversationBackendMode == ConversationBackendMode.HOSTED) View.VISIBLE else View.GONE)
        reconcileWakeRuntime()
    }

    private fun hostedStatusText(readiness: ConversationReadiness): String = when (readiness) {
        ConversationReadiness.READY, ConversationReadiness.READY_NOT_LOADED -> "Hosted Google AI is connected. Only text, compact context, and bounded history are sent."
        else -> connectionMessage(googleHostedConversationEngine.connectionState())
    }

    private fun installButtonLabel(state: GemmaInstallState): String = when (state) {
        GemmaInstallState.NotInstalled, is GemmaInstallState.Failed -> "SET UP LOCAL AI"
        GemmaInstallState.ValidatingAccess -> "CHECKING AI ACCESS..."
        GemmaInstallState.ReadyToDownload -> "PREPARING AI DOWNLOAD..."
        is GemmaInstallState.Downloading -> "AI DOWNLOADING..."
        GemmaInstallState.Verifying -> "VERIFYING AI MODEL..."
        GemmaInstallState.Installing -> "INSTALLING AI MODEL..."
        is GemmaInstallState.Installed -> "TALK TO FORESIGHT"
    }

    private fun installStatusText(state: GemmaInstallState): String = when (state) {
        GemmaInstallState.NotInstalled -> "Local AI model setup is required."
        GemmaInstallState.ValidatingAccess -> "Checking Hugging Face access..."
        GemmaInstallState.ReadyToDownload -> "Access verified. Preparing download..."
        is GemmaInstallState.Downloading -> "Downloading Gemma 3n E2B...\n${state.bytesDownloaded / 1_000_000} MB / ${state.totalBytes / 1_000_000} MB\n${state.bytesDownloaded * 100 / state.totalBytes}%"
        GemmaInstallState.Verifying -> "Verifying Local AI model..."
        GemmaInstallState.Installing -> "Installing Local AI model..."
        is GemmaInstallState.Installed -> ""
        is GemmaInstallState.Failed -> "Local AI setup failed: ${state.userMessage}"
    }

    private fun renderVoiceResponse(message: String, requestSpeech: Boolean) {
        renderVoiceStatus(message)
        if (!requestSpeech) {
            voiceStatusClear = Runnable {
                if (voiceTurnController.state == VoiceRuntimeState.IDLE) voiceStatusText.setTextIfChanged("")
            }.also { uiHandler.postDelayed(it, VOICE_ERROR_STATUS_MILLIS) }
        }
    }

    private fun renderVoiceStatus(message: String) {
        voiceStatusClear?.let(uiHandler::removeCallbacks)
        voiceStatusClear = null
        voiceStatusText.setTextIfChanged(message)
    }

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

        val readinessBefore = binder.fieldEventReadiness()
        Log.i(
            TAG,
            "FIELD $action pressed: source=${binder.activeFieldMediaSource()}; " +
                "eventState=${eventUiState.event.state}; eventId=${eventUiState.event.eventId}; " +
                "canStart=${readinessBefore.canStartEvent}; canEnd=${readinessBefore.canEndEvent}; " +
                "reason=${readinessBefore.reason}",
        )

        eventUiState = eventUiState.pending(action)
        renderStatus()

        when (action) {
            "start" -> binder.startFieldEvent { result ->
                runOnUiThread {
                    result.fold(
                        onSuccess = { event ->
                            val readinessAfter = binder.fieldEventReadiness()
                            Log.i(
                                TAG,
                                "FIELD START callback SUCCESS: eventId=${event.eventId}; recordingId=${event.recordingId}; " +
                                    "canStart=${readinessAfter.canStartEvent}; canEnd=${readinessAfter.canEndEvent}; " +
                                    "reason=${readinessAfter.reason}",
                            )
                            eventUiState = EventControlUiState(
                                EventControlState("recording_bounded_event", event.eventId),
                                "FIELD event recording locally; laptop is not required.",
                            )
                        },
                        onFailure = { error ->
                            val readinessAfter = binder.fieldEventReadiness()
                            Log.e(
                                TAG,
                                "FIELD START callback FAILURE: ${error.javaClass.simpleName}: ${error.message}; " +
                                    "canStart=${readinessAfter.canStartEvent}; canEnd=${readinessAfter.canEndEvent}; " +
                                    "reason=${readinessAfter.reason}",
                                error,
                            )
                            eventUiState = EventControlUiState(
                                EventControlState(),
                                "FIELD event start failed: ${error.message ?: error.javaClass.simpleName}",
                            )
                            binder.activeFieldEvent { active ->
                                runOnUiThread {
                                    Log.i(
                                        TAG,
                                        "FIELD START failure active-event probe: " +
                                            "eventId=${active?.eventId}; recordingId=${active?.recordingId}",
                                    )
                                    if (active != null) {
                                        eventUiState = EventControlUiState(
                                            EventControlState("recording_bounded_event", active.eventId),
                                            "Recovered active FIELD event after start callback failure.",
                                        )
                                    }
                                    renderStatus()
                                }
                            }
                        },
                    )
                    renderStatus()
                }
            }

            "end" -> binder.endFieldEvent { result ->
                runOnUiThread {
                    result.fold(
                        onSuccess = { event ->
                            val readinessAfter = binder.fieldEventReadiness()
                            Log.i(
                                TAG,
                                "FIELD END callback SUCCESS: eventId=${event.eventId}; recordingId=${event.recordingId}; " +
                                    "canStart=${readinessAfter.canStartEvent}; canEnd=${readinessAfter.canEndEvent}; " +
                                    "reason=${readinessAfter.reason}",
                            )
                            lastEventIdForSync = event.eventId
                            preferences().edit().putString(PREF_LAST_SYNC_EVENT_ID, event.eventId).apply()
                            syncUiState = EventMediaSyncUiState(
                                EventMediaSyncState.LOCAL_ONLY,
                                "FIELD event is local; extraction follows recording finalization.",
                            )
                            eventUiState = EventControlUiState(
                                EventControlState("finalizing", event.eventId),
                                "FIELD event complete; pending local extraction.",
                            )
                        },
                        onFailure = { error ->
                            val readinessAfter = binder.fieldEventReadiness()
                            Log.e(
                                TAG,
                                "FIELD END callback FAILURE: ${error.javaClass.simpleName}: ${error.message}; " +
                                    "canStart=${readinessAfter.canStartEvent}; canEnd=${readinessAfter.canEndEvent}; " +
                                    "reason=${readinessAfter.reason}",
                                error,
                            )
                            eventUiState = eventUiState.copy(
                                detail = "FIELD event end failed: ${error.message ?: error.javaClass.simpleName}",
                            )
                        },
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
        val visibleEntries = entries.take(SYNC_HISTORY_VISIBLE_LIMIT)
        if (selectedSyncAttemptId !in entries.map { it.attemptId }) {
            selectedSyncAttemptId = entries.firstOrNull()?.attemptId
        }
        val renderState = SyncHistoryRenderState(
            entries = visibleEntries,
            retryableEventIds = retryableEventIds.toSet(),
            selectedAttemptId = selectedSyncAttemptId,
        )
        if (!syncHistoryRenderCache.shouldRender(renderState)) return

        syncHistoryContainer.removeAllViews()
        if (entries.isEmpty()) {
            syncHistoryContainer.addView(TextView(this).apply { text = "No sync attempts yet." })
            syncReceiptText.setTextIfChanged("Select a sync attempt to view its receipt.")
            return
        }
        visibleEntries.forEach { entry ->
            syncHistoryContainer.addView(Button(this).apply {
                text = "${historyIndicator(entry)} ${SyncHistoryPresentation.heading(entry)}\nEvent: ${entry.eventId.take(8)}"
                textSize = 13f
                isAllCaps = false
                setOnClickListener {
                    selectedSyncAttemptId = entry.attemptId
                    renderStatus()
                }
            })
            if (entry != visibleEntries.last()) {
                syncHistoryContainer.addView(TextView(this).apply { text = "----------------" })
            }
        }
        val selected = entries.first { it.attemptId == selectedSyncAttemptId }
        syncReceiptText.setTextIfChanged(formatSyncReceipt(selected, selected.eventId in retryableEventIds))
    }

    private fun historyIndicator(entry: EventMediaSyncHistoryEntry): String =
        if (entry.result == EventMediaSyncAttemptResult.SYNCED) "[OK]" else "[FAIL]"

    private fun formatSyncReceipt(entry: EventMediaSyncHistoryEntry, retryAvailable: Boolean): String = buildString {
        append("Event: ${entry.eventId}\n")
        append("Origin/authority: ${entry.eventOrigin} / ${entry.authority}\n")
        append("Attempt: ${SyncHistoryPresentation.detailTimestamp(entry.startedUtc)}\n")
        entry.completedUtc?.let { append("Completed: ${SyncHistoryPresentation.detailTimestamp(it)}\n") }
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

    private fun setLightIfChanged(
        light: TextView,
        isOn: Boolean,
        onColor: Int,
        cache: GatewayUiRenderCache<StatusLightRenderState>,
    ) {
        if (!cache.shouldRender(StatusLightRenderState(isOn, onColor))) return
        setLight(light, isOn, onColor)
    }

    private fun setLight(light: TextView, isOn: Boolean, onColor: Int) {
        light.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (isOn) onColor else Color.DKGRAY)
            setStroke(dp(2), Color.LTGRAY)
        }
    }

    private fun TextView.setTextIfChanged(value: CharSequence) {
        if (text.toString() != value.toString()) text = value
    }

    private fun View.setEnabledIfChanged(value: Boolean) {
        if (isEnabled != value) isEnabled = value
    }

    private fun View.setVisibilityIfChanged(value: Int) {
        if (visibility != value) visibility = value
    }

    private fun View.setAlphaIfChanged(value: Float) {
        if (alpha != value) alpha = value
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

    private inner class SharedPreferencesWakePreferenceStore : WakePreferenceStore {
        override fun isEnabled(): Boolean = preferences().getBoolean(PREF_VOICE_WAKE_ENABLED, false)
        override fun setEnabled(enabled: Boolean) {
            preferences().edit().putBoolean(PREF_VOICE_WAKE_ENABLED, enabled).apply()
        }
    }

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
        private const val REQUEST_VOICE_PERMISSION = 2
        private const val PREFERENCES_NAME = "foresight_gateway"
        private const val PREF_LAST_ENDPOINT = "last_rtsp_endpoint"
        private const val PREF_LAST_TELEMETRY_ENDPOINT = "last_telemetry_endpoint"
        private const val PREF_LAST_CONTROL_ENDPOINT = "last_control_endpoint"
        private const val PREF_LAST_SYNC_EVENT_ID = "last_sync_event_id"
        private const val PREF_OPERATING_MODE = "operating_mode"
        private const val PREF_FIELD_MEDIA_SOURCE = "field_media_source"
        private const val PREF_GOPRO_NETWORK_MODE = "gopro_network_mode"
        private const val PREF_VISUALIZATION_MODE = "visualization_mode"
        private const val PREF_CONVERSATION_BACKEND_MODE = "conversation_backend_mode"
        private const val PREF_VOICE_WAKE_ENABLED = "voice_wake_enabled"
        private const val SYNC_HISTORY_VISIBLE_LIMIT = 8
        private const val STATUS_REFRESH_MILLIS = 500L
        private const val EVENT_STATUS_REFRESH_MILLIS = 1_500L
        private const val VISION_SAMPLE_INTERVAL_MILLIS = 1_000L
        private const val VOICE_DETECTION_FRESHNESS_NANOS = 3_000_000_000L
        private const val SCENE_EVIDENCE_FRESHNESS_NANOS = 3_000_000_000L
        private const val VOICE_ERROR_STATUS_MILLIS = 2_000L
        private const val TAG = "GatewayActivity"
        private const val GOOGLE_AI_TAG = "ForesightGoogleAI"
    }
}
