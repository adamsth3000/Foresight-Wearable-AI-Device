package com.foresight.gateway.transport

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.util.Log
import android.view.SurfaceView
import com.pedro.common.ConnectChecker
import com.pedro.common.socket.base.SocketType
import com.pedro.encoder.input.sources.OrientationForced
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.video.Camera2Source
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.encoder.input.video.FrameCapturedCallback
import com.pedro.encoder.utils.gl.AspectRatioMode
import com.pedro.library.rtsp.RtspStream
import com.pedro.library.base.recording.RecordController
import com.foresight.gateway.capture.LocalRecordingContext
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Phone-local RTSP publisher. RootEncoder is confined here so higher layers only
 * depend on lifecycle callbacks and an RTSP endpoint.
 */
class RtspPublisher(
    context: Context,
    private val listener: Listener,
) : ConnectChecker {
    interface Listener {
        fun onLifecycleChanged(lifecycle: StreamLifecycle, detail: String? = null)
        fun onBitrateChanged(bitsPerSecond: Long)
        fun onCameraTimingCapability(timestampSource: String)
        fun onCameraFrameCaptured(frameNumber: Long, timestampNanos: Long)
        fun onLocalRecordingStarted(context: LocalRecordingContext)
        fun onLocalRecordingFinalized(context: LocalRecordingContext, stopUtc: Instant)
        fun onLocalRecordingInterrupted(context: LocalRecordingContext, detail: String)
    }

    private val applicationContext = context.applicationContext
    private var streamGeneration = 0
    private val generationOwnership = RtspGenerationOwnership()
    private lateinit var cameraSource: Camera2Source
    private var stream = createStream()
    private val reconnectPolicy = RtspReconnectPolicy()
    private val retryState = RtspRetryState()
    private val transportHealthMonitor = RtspTransportHealthMonitor()
    private val senderProgressMonitor = RtspSenderProgressMonitor()
    private val previewAttachment = PreviewAttachmentState()
    private val cameraFramesProduced = AtomicLong(0)
    private var captureRequested = false
    private var retryReason: String? = null
    private var awaitingKtorFailure = false
    private var fatalTransportError = false
    private var transportConnected = false
    private var activeEndpoint: String? = null
    private var activeSourceSessionId: String? = null
    private var retryAttemptStartedElapsedMillis: Long? = null
    private var rebuildingTransport = false
    private var stoppingTransport = false
    private var previewSurfaceView: SurfaceView? = null
    private var videoPrepared = false
    private var localRecordingPath: File? = null
    private var localRecordingContext: LocalRecordingContext? = null
    private var localRecordingFailed = false
    @Volatile
    private var publisherLifecycle = StreamLifecycle.IDLE

    fun generation(): Int = streamGeneration

    fun lifecycle(): StreamLifecycle = publisherLifecycle

    private fun reportLifecycle(lifecycle: StreamLifecycle, detail: String? = null) {
        publisherLifecycle = lifecycle
        listener.onLifecycleChanged(lifecycle, detail)
    }

    fun start(endpoint: String, sourceSessionId: String): Boolean {
        return startInternal(endpoint, sourceSessionId, resetRetryPolicy = true)
    }

    private fun startInternal(endpoint: String, sourceSessionId: String, resetRetryPolicy: Boolean): Boolean {
        if (stream.isStreaming) {
            Log.w(TAG, "RTSP start ignored because the previous transport is still active: $endpoint")
            return true
        }
        check(!stoppingTransport) { "RTSP transport is still stopping; wait for IDLE before starting." }
        captureRequested = true
        activeEndpoint = endpoint
        activeSourceSessionId = sourceSessionId
        if (resetRetryPolicy) reconnectPolicy.reset()
        cancelScheduledRetry("new capture request")
        cancelKtorFailureWait("new capture request")
        cancelRetryAttemptWatchdog("new capture request")
        transportHealthMonitor.reset()
        senderProgressMonitor.reset()
        cameraFramesProduced.set(0)
        fatalTransportError = false
        transportConnected = false

        reportLifecycle(StreamLifecycle.PREPARING)
        Log.i(TAG, "Capture generation $streamGeneration preparing: rtsp=$endpoint")
        val cameraSize = selectCameraSize()
        cameraSource.setRequiredResolution(cameraSize)
        logCameraGeometry(cameraSize)
        listener.onCameraTimingCapability(cameraTimestampSource())
        Log.i(
            TAG,
            "Preparing H.264 encoder ${VIDEO_WIDTH}x${VIDEO_HEIGHT} at ${VIDEO_FPS} fps, " +
                "${VIDEO_BITRATE_BITS_PER_SECOND} bps, keyframe interval " +
                "${VIDEO_KEYFRAME_INTERVAL_SECONDS}s, rotation $VIDEO_ROTATION_DEGREES.",
        )
        val videoReady = stream.prepareVideo(
            width = VIDEO_WIDTH,
            height = VIDEO_HEIGHT,
            bitrate = VIDEO_BITRATE_BITS_PER_SECOND,
            fps = VIDEO_FPS,
            iFrameInterval = VIDEO_KEYFRAME_INTERVAL_SECONDS,
            rotation = VIDEO_ROTATION_DEGREES,
        )
        videoPrepared = videoReady
        Log.i(TAG, "Video preparation result: $videoReady")
        if (videoReady) {
            // Keep the output viewport landscape, then apply the inverse of the Camera2
            // SurfaceTexture's clockwise axis exchange to the camera-quad MVP transform.
            stream.getGlInterface().forceOrientation(OrientationForced.LANDSCAPE)
            // This applies only to the attached display surface. The 1280x720 encoder viewport
            // and its validated camera-MVP compensation remain unchanged.
            stream.getGlInterface().setAspectRatioMode(AspectRatioMode.Adjust)
            stream.setOrientation(CAMERA_TEXTURE_COMPENSATION_DEGREES)
            Log.i(
                TAG,
                "GL geometry: forcedLandscape=true, cameraMvpCompensation=" +
                    "$CAMERA_TEXTURE_COMPENSATION_DEGREES, " +
                    "encoderViewport=${VIDEO_WIDTH}x${VIDEO_HEIGHT}, " +
                    "previewAspectMode=Adjust (preview-only), " +
                    "surfaceTextureTransform=applied by RootEncoder CameraRender per frame.",
            )
        }
        val audioReady = stream.prepareAudio(
            sampleRate = AUDIO_SAMPLE_RATE_HZ,
            isStereo = true,
            bitrate = AUDIO_BITRATE_BITS_PER_SECOND,
            echoCanceler = false,
            noiseSuppressor = false,
        )
        Log.i(TAG, "Audio preparation result: $audioReady")
        if (!videoReady || !audioReady) {
            videoPrepared = false
            reportLifecycle(StreamLifecycle.ERROR, "Unable to prepare rear camera or microphone.")
            return false
        }

        attachPreviewIfReady()

        reportLifecycle(StreamLifecycle.CONNECTING)
        Log.i(TAG, "Capture generation $streamGeneration RTSP connecting: $endpoint")
        // RootEncoder reconnects only when its retry budget is positive. The app owns timing;
        // this generous budget simply keeps the requested foreground capture retryable.
        stream.getStreamClient().setReTries(ROOT_ENCODER_RETRY_BUDGET)
        // The Java socket implementation has no write timeout. Ktor turns a blocked RTSP/TCP
        // media write into an exception, which RootEncoder delivers through ConnectChecker.
        stream.getStreamClient().setSocketType(SocketType.KTOR)
        stream.getStreamClient().setSocketTimeout(RTSP_SOCKET_TIMEOUT_MILLIS)
        stream.getStreamClient().setLogs(true)
        Log.i(
            TAG,
            "RTSP transport configured: protocol=TCP, socket=Ktor, ioTimeout=" +
                "${RTSP_SOCKET_TIMEOUT_MILLIS}ms, serverLivenessProbe=disabled.",
        )
        cameraSource.enableFrameCaptureCallback(object : FrameCapturedCallback {
            override fun onFrameCaptured(frameNumber: Long, timestamp: Long) {
                cameraFramesProduced.incrementAndGet()
                listener.onCameraFrameCaptured(frameNumber, timestamp)
            }
        })
        stream.startStream(endpoint)
        startLocalRecording(sourceSessionId)
        startTransportDiagnostics()
        Log.i(
            TAG,
            "Capture generation $streamGeneration camera started: " +
                "id=${cameraSource.getCurrentCameraId()}.",
        )
        scheduleTextureDiagnostics(cameraSize)
        return true
    }

    fun stop() {
        if (stoppingTransport) {
            Log.i(TAG, "RTSP stop already in progress.")
            return
        }
        captureRequested = false
        val retiredGeneration = streamGeneration
        generationOwnership.retire(retiredGeneration)
        Log.i(TAG, "Capture generation $retiredGeneration stop requested: rtsp=$activeEndpoint")
        cancelScheduledRetry("capture stop")
        cancelKtorFailureWait("capture stop")
        cancelRetryAttemptWatchdog("capture stop")
        reconnectPolicy.reset()
        transportHealthMonitor.reset()
        senderProgressMonitor.reset()
        fatalTransportError = false
        transportConnected = false
        mainHandler.removeCallbacks(transportDiagnostics)
        detachPreviewForTransportStop()
        stopLocalRecording()
        videoPrepared = false
        stoppingTransport = true
        if (!stream.isStreaming) {
            completeStoppedTransport(retiredGeneration, "stream was already inactive")
            return
        }
        reportLifecycle(StreamLifecycle.STOPPING)
        try {
            // RootEncoder's disconnect callback can occur before stopStream has stopped Camera2,
            // EGL, and encoders. Only retire/rebuild once this synchronous method returns.
            stream.stopStream()
        } catch (error: RuntimeException) {
            Log.w(TAG, "Capture generation $retiredGeneration stopStream raised an exception.", error)
        }
        completeStoppedTransport(retiredGeneration, "RootEncoder stopStream returned")
    }

    internal fun localRecordingContext(): LocalRecordingContext? = localRecordingContext

    private fun startLocalRecording(sourceSessionId: String) {
        val recordings = File(applicationContext.filesDir, "recordings")
        if (!recordings.exists() && !recordings.mkdirs()) {
            Log.e(TAG, "Unable to create private local recording directory.")
            return
        }
        val recordingId = UUID.randomUUID().toString()
        val path = File(recordings, "capture-$recordingId-g$streamGeneration.mp4")
        val context = LocalRecordingContext(
            recordingId = recordingId,
            sourceSessionId = sourceSessionId,
            captureGeneration = streamGeneration,
            localMediaFileName = path.name,
            startedUtc = Instant.now(),
            startedMonotonicMillis = SystemClock.elapsedRealtime(),
            isRecording = true,
        )
        try {
            stream.startRecord(path.absolutePath, listener = object : RecordController.Listener {
                override fun onStatusChange(status: RecordController.Status) {
                    Log.i(TAG, "Local recording status generation=$streamGeneration status=$status file=${path.name}")
                }

                override fun onError(e: Exception?) {
                    localRecordingFailed = true
                    val detail = "RootEncoder local recorder error: ${e?.message ?: "unknown error"}"
                    Log.e(TAG, detail, e)
                    listener.onLocalRecordingInterrupted(context, detail)
                }
            })
            localRecordingPath = path
            localRecordingContext = context
            localRecordingFailed = false
            listener.onLocalRecordingStarted(context)
            Log.i(TAG, "Local authoritative recording started generation=$streamGeneration file=${path.name}")
        } catch (error: RuntimeException) {
            Log.e(TAG, "Unable to start local recording; RTSP capture continues.", error)
        }
    }

    private fun stopLocalRecording() {
        val path = localRecordingPath ?: return
        val context = localRecordingContext ?: return
        try {
            stream.stopRecord()
            if (localRecordingFailed) {
                listener.onLocalRecordingInterrupted(context, "RootEncoder local recorder reported an error before stop.")
            } else {
                listener.onLocalRecordingFinalized(context, Instant.now())
                Log.i(TAG, "Local authoritative recording finalized generation=$streamGeneration file=${path.name}")
            }
        } catch (error: RuntimeException) {
            val detail = "Unable to finalize local recording file=${path.name}: ${error.message}"
            Log.e(TAG, detail, error)
            listener.onLocalRecordingInterrupted(context, detail)
        } finally {
            localRecordingPath = null
            localRecordingContext = context.copy(isRecording = false)
            localRecordingFailed = false
        }
    }

    private fun interruptLocalRecording(detail: String) {
        val context = localRecordingContext ?: return
        listener.onLocalRecordingInterrupted(context, detail)
        localRecordingPath = null
        localRecordingContext = context.copy(isRecording = false)
        localRecordingFailed = false
    }

    /** Attach an activity display surface to the existing RootEncoder GL pipeline. */
    fun attachPreview(surfaceView: SurfaceView) {
        previewSurfaceView = surfaceView
        previewAttachment.request()
        Log.i(
            TAG,
            "Capture generation $streamGeneration preview request: valid=${surfaceView.holder.surface.isValid}, " +
                "visible=${surfaceView.visibility == android.view.View.VISIBLE}, alpha=${surfaceView.alpha}, " +
                "dimensions=${surfaceView.width}x${surfaceView.height}.",
        )
        attachPreviewIfReady()
    }

    /** Release only the display sink; camera, microphone, encoder, and RTSP remain service-owned. */
    fun detachPreview(surfaceView: SurfaceView) {
        if (previewSurfaceView !== surfaceView) return
        previewSurfaceView = null
        if (previewAttachment.releaseRequest() && stream.isOnPreview) {
            Log.i(TAG, "Detaching RootEncoder preview surface from the activity.")
            stream.stopPreview()
        }
    }

    private fun attachPreviewIfReady() {
        val surfaceView = previewSurfaceView ?: return
        if (!videoPrepared || !previewAttachment.shouldAttach()) return
        if (!surfaceView.holder.surface.isValid || surfaceView.width <= 0 || surfaceView.height <= 0) {
            Log.i(TAG, "Preview attachment deferred until the activity SurfaceView has valid dimensions.")
            return
        }
        try {
            Log.i(TAG, "Capture generation $streamGeneration executing RootEncoder startPreview().")
            stream.startPreview(surfaceView)
            previewAttachment.markAttached()
            Log.i(
                TAG,
                "Capture generation $streamGeneration preview attached to the camera-to-encoder GL pipeline; " +
                    "preview=${surfaceView.width}x${surfaceView.height}, encoder=${VIDEO_WIDTH}x${VIDEO_HEIGHT}, " +
                    "previewAspectMode=Adjust.",
            )
        } catch (error: IllegalStateException) {
            Log.w(TAG, "RootEncoder preview attachment was deferred.", error)
        }
    }

    private fun detachPreviewForTransportStop() {
        if (previewAttachment.detachForTransportStop() && stream.isOnPreview) {
            Log.i(TAG, "Capture generation $streamGeneration preview detached before transport stop or replacement.")
            stream.stopPreview()
        }
    }

    override fun onConnectionStarted(url: String) {
        retryState.connectionStarted()
        cancelKtorFailureWait("RootEncoder connection started")
        transportConnected = false
        Log.i(TAG, "Capture generation $streamGeneration RTSP connection started: $url")
    }

    override fun onConnectionSuccess() {
        val recoveredAfterAttempts = reconnectPolicy.attempts()
        reconnectPolicy.reset()
        retryState.reset()
        cancelKtorFailureWait("RTSP connection success")
        cancelRetryAttemptWatchdog("RTSP connection success")
        transportHealthMonitor.reset()
        transportConnected = true
        if (recoveredAfterAttempts > 0) {
            Log.i(TAG, "Capture generation $streamGeneration RTSP recovered after $recoveredAfterAttempts retry attempt(s).")
            reportLifecycle(
                StreamLifecycle.STREAMING,
                "RTSP recovered after $recoveredAfterAttempts retry attempt(s).",
            )
        } else {
            Log.i(TAG, "Capture generation $streamGeneration RTSP connected: $activeEndpoint")
            reportLifecycle(StreamLifecycle.STREAMING, "RTSP connected.")
        }
    }

    override fun onConnectionFailed(reason: String) {
        transportConnected = false
        retryState.connectionFailed()
        cancelKtorFailureWait("RootEncoder connection failure")
        cancelRetryAttemptWatchdog("RTSP connection failure")
        Log.w(TAG, "RTSP connection failed: $reason")
        scheduleReconnect(reason)
    }

    override fun onNewBitrate(bitrate: Long) {
        listener.onBitrateChanged(bitrate)
    }

    override fun onDisconnect() {
        transportConnected = false
        retryState.connectionFailed()
        cancelKtorFailureWait("RootEncoder disconnect")
        cancelRetryAttemptWatchdog("RootEncoder disconnect")
        Log.i(TAG, "RTSP connection disconnected.")
        if (fatalTransportError) {
            Log.i(TAG, "RTSP disconnect follows a fatal transport error; retaining ERROR state.")
            return
        }
        if (captureRequested) {
            scheduleReconnect("RTSP transport disconnected")
        }
    }

    /**
     * A RootEncoder RtspStream is not reused after a normal stop. Retiring it prevents the next
     * session from inheriting a stopped sender, stale endpoint, or detached Camera2/GL state.
     */
    private fun completeStoppedTransport(retiredGeneration: Int, reason: String) {
        if (!stoppingTransport) return
        stoppingTransport = false
        val retiredStream = stream
        activeEndpoint = null
        retryState.reset()
        transportConnected = false
        fatalTransportError = false
        try {
            // RootEncoder documents release() as the full source/encoder/GL disposal operation.
            // stopStream() alone intentionally leaves prepared encoder state for reuse.
            retiredStream.release()
        } catch (error: RuntimeException) {
            Log.w(TAG, "Capture generation $retiredGeneration release raised an exception.", error)
        }
        stream = createStream()
        Log.i(
            TAG,
            "Capture generation $retiredGeneration retired after $reason; " +
                "generation $streamGeneration created and idle.",
        )
        reportLifecycle(StreamLifecycle.IDLE, "Capture stopped.")
    }

    override fun onAuthError() {
        captureRequested = false
        cancelScheduledRetry("RTSP authentication failure")
        cancelKtorFailureWait("RTSP authentication failure")
        cancelRetryAttemptWatchdog("RTSP authentication failure")
        fatalTransportError = true
        transportConnected = false
        Log.e(TAG, "RTSP authentication failed; automatic retry is disabled.")
        reportLifecycle(StreamLifecycle.ERROR, "RTSP authentication failed.")
        if (stream.isStreaming) stream.stopStream()
    }

    override fun onAuthSuccess() {
        Log.i(TAG, "RTSP authentication succeeded.")
    }

    private fun scheduleReconnect(reason: String) {
        transportConnected = false
        if (!captureRequested) {
            Log.i(TAG, "RTSP retry skipped because capture is no longer requested.")
            return
        }
        if (!retryState.schedule()) {
            Log.i(
                TAG,
                "RTSP retry already scheduled or in flight; ignoring duplicate failure: $reason " +
                    "(timerScheduled=${retryState.isTimerScheduled}, inFlight=${retryState.isAttemptInFlight})",
            )
            return
        }
        val delayMillis = reconnectPolicy.nextDelayMillis()
        retryReason = reason
        Log.w(
            TAG,
            "RTSP reconnect attempt ${reconnectPolicy.attempts()} scheduled in ${delayMillis}ms: $reason",
        )
        reportLifecycle(
            StreamLifecycle.RECONNECTING,
            "RTSP reconnect ${reconnectPolicy.attempts()} in ${delayMillis / 1_000}s: $reason",
        )
        mainHandler.postDelayed(retryTimer, delayMillis)
    }

    private val retryTimer = Runnable {
        val reason = retryReason ?: "RTSP transport failure"
        if (!retryState.fireTimer()) {
            Log.w(TAG, "RTSP retry timer fired after cancellation; no retry will run.")
            return@Runnable
        }
        retryReason = null
        if (!captureRequested || fatalTransportError) {
            Log.i(TAG, "RTSP retry timer fired after capture stopped; retry cancelled.")
            retryState.reset()
            return@Runnable
        }
        Log.i(TAG, "RTSP retry timer fired for attempt ${reconnectPolicy.attempts()}: $reason")
        try {
            Log.i(TAG, "Calling RootEncoder transport-only reTry() for attempt ${reconnectPolicy.attempts()}.")
            // The app has already waited the backoff interval; zero avoids an opaque second timer
            // inside RootEncoder and makes retry execution observable in this lifecycle.
            val accepted = stream.getStreamClient().reTry(0L, reason)
            Log.i(TAG, "RootEncoder reTry() returned $accepted for attempt ${reconnectPolicy.attempts()}.")
            if (!accepted) {
                retryState.reset()
                captureRequested = false
                fatalTransportError = true
                Log.e(TAG, "RTSP cannot retry further: $reason")
                reportLifecycle(StreamLifecycle.ERROR, "RTSP retry unavailable: $reason")
                if (stream.isStreaming) stream.stopStream()
            } else {
                retryAttemptStartedElapsedMillis = SystemClock.elapsedRealtime()
                Log.i(
                    TAG,
                    "RTSP retry attempt ${reconnectPolicy.attempts()} started at " +
                        "${retryAttemptStartedElapsedMillis}ms; deadline in " +
                        "${RETRY_ATTEMPT_DEADLINE_MILLIS}ms.",
                )
                mainHandler.postDelayed(retryAttemptWatchdog, RETRY_ATTEMPT_DEADLINE_MILLIS)
            }
        } catch (error: RuntimeException) {
            retryState.connectionFailed()
            Log.e(TAG, "RootEncoder reTry() threw for attempt ${reconnectPolicy.attempts()}.", error)
            scheduleReconnect("RTSP retry invocation failed: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private val retryAttemptWatchdog = Runnable {
        if (!retryState.isAttemptInFlight || !captureRequested || fatalTransportError) {
            Log.i(TAG, "RTSP retry-attempt watchdog cancelled before expiration.")
            return@Runnable
        }
        val client = stream.getStreamClient()
        Log.e(
            TAG,
            "RTSP retry-attempt watchdog expired after ${RETRY_ATTEMPT_DEADLINE_MILLIS}ms; " +
                "startedAt=${retryAttemptStartedElapsedMillis}ms, streamIsStreaming=${stream.isStreaming}, " +
                "senderQueue=${client.getItemsInCache()}/${client.getCacheSize()}, " +
                "sentVideo=${client.getSentVideoFrames()}, sentAudio=${client.getSentAudioFrames()}.",
        )
        if (!retryState.attemptDeadlineExpired()) return@Runnable
        retryAttemptStartedElapsedMillis = null
        reportLifecycle(
            StreamLifecycle.RECONNECTING,
            "RTSP retry stalled; rebuilding the transport stack.",
        )
        rebuildTransportStack("retry attempt did not produce a terminal callback")
    }

    private fun awaitKtorFailure(reason: String) {
        if (awaitingKtorFailure || retryState.isTimerScheduled || retryState.isAttemptInFlight) return
        awaitingKtorFailure = true
        transportConnected = false
        Log.w(
            TAG,
            "RTSP sender stalled; waiting ${KTOR_FAILURE_GRACE_MILLIS}ms for Ktor write failure " +
                "before fallback retry: $reason",
        )
        reportLifecycle(StreamLifecycle.RECONNECTING, "RTSP sender stalled; verifying transport failure.")
        mainHandler.postDelayed(stalledTransportFallback, KTOR_FAILURE_GRACE_MILLIS)
    }

    private val stalledTransportFallback = Runnable {
        awaitingKtorFailure = false
        if (!captureRequested || fatalTransportError || retryState.isTimerScheduled || retryState.isAttemptInFlight) {
            Log.i(TAG, "RTSP stalled-transport fallback cancelled.")
            return@Runnable
        }
        Log.w(TAG, "Ktor failure callback did not arrive before fallback deadline; scheduling RTSP retry.")
        scheduleReconnect("RTSP sender stalled beyond Ktor write-timeout grace")
    }

    private fun cancelScheduledRetry(reason: String) {
        if (retryState.isTimerScheduled) Log.i(TAG, "Cancelling scheduled RTSP retry: $reason")
        mainHandler.removeCallbacks(retryTimer)
        retryReason = null
        retryState.reset()
    }

    private fun cancelKtorFailureWait(reason: String) {
        if (awaitingKtorFailure) Log.i(TAG, "Cancelling Ktor failure wait: $reason")
        mainHandler.removeCallbacks(stalledTransportFallback)
        awaitingKtorFailure = false
    }

    private fun cancelRetryAttemptWatchdog(reason: String) {
        if (retryAttemptStartedElapsedMillis != null) {
            Log.i(TAG, "Cancelling RTSP retry-attempt watchdog: $reason")
        }
        mainHandler.removeCallbacks(retryAttemptWatchdog)
        retryAttemptStartedElapsedMillis = null
    }

    private fun rebuildTransportStack(reason: String) {
        if (rebuildingTransport) {
            Log.i(TAG, "RTSP transport rebuild is already in progress.")
            return
        }
        val endpoint = activeEndpoint
        if (endpoint == null) {
            captureRequested = false
            fatalTransportError = true
            reportLifecycle(StreamLifecycle.ERROR, "RTSP retry lost its endpoint.")
            return
        }
        rebuildingTransport = true
        val retiredGeneration = streamGeneration
        // Retire callbacks before stopping the old stream: its RootEncoder retry coroutine can
        // be stuck in socket cleanup and must not alter the replacement stream's state later.
        generationOwnership.retire(retiredGeneration)
        Log.w(
            TAG,
            "Retiring stuck RootEncoder transport generation $retiredGeneration: $reason",
        )
        try {
            detachPreviewForTransportStop()
            videoPrepared = false
            interruptLocalRecording("RootEncoder transport was rebuilt before local recording finalization")
            stream.stopStream()
            stream.release()
        } catch (error: RuntimeException) {
            Log.w(TAG, "Retired RootEncoder transport stop raised an exception.", error)
        }
        mainHandler.postDelayed({
            if (!captureRequested || fatalTransportError) {
                rebuildingTransport = false
                Log.i(TAG, "RTSP transport rebuild cancelled before replacement start.")
                return@postDelayed
            }
            stream = createStream()
            rebuildingTransport = false
            Log.i(TAG, "Starting replacement RootEncoder transport generation $streamGeneration.")
            if (!startInternal(requireNotNull(activeEndpoint), requireNotNull(activeSourceSessionId), resetRetryPolicy = false)) {
                captureRequested = false
                fatalTransportError = true
                reportLifecycle(StreamLifecycle.ERROR, "Unable to rebuild RTSP transport.")
            }
        }, TRANSPORT_REBUILD_DELAY_MILLIS)
    }

    private fun createStream(): RtspStream {
        val generation = ++streamGeneration
        generationOwnership.activate(generation)
        cameraSource = Camera2Source(applicationContext)
        Log.i(TAG, "Capture generation $generation RootEncoder transport created.")
        return RtspStream(
            applicationContext,
            generationScopedConnectChecker(generation),
            cameraSource,
            MicrophoneSource(),
        )
    }

    private fun generationScopedConnectChecker(generation: Int) = object : ConnectChecker {
        override fun onConnectionStarted(url: String) {
            forwardIfCurrent(generation) { this@RtspPublisher.onConnectionStarted(url) }
        }

        override fun onConnectionSuccess() {
            forwardIfCurrent(generation) { this@RtspPublisher.onConnectionSuccess() }
        }

        override fun onConnectionFailed(reason: String) {
            forwardIfCurrent(generation) { this@RtspPublisher.onConnectionFailed(reason) }
        }

        override fun onNewBitrate(bitrate: Long) {
            forwardIfCurrent(generation) { this@RtspPublisher.onNewBitrate(bitrate) }
        }

        override fun onDisconnect() {
            forwardIfCurrent(generation) { this@RtspPublisher.onDisconnect() }
        }

        override fun onAuthError() {
            forwardIfCurrent(generation) { this@RtspPublisher.onAuthError() }
        }

        override fun onAuthSuccess() {
            forwardIfCurrent(generation) { this@RtspPublisher.onAuthSuccess() }
        }
    }

    private fun forwardIfCurrent(generation: Int, callback: () -> Unit) {
        if (generation == streamGeneration && generationOwnership.accepts(generation)) callback()
        else Log.i(TAG, "Ignoring callback from retired RootEncoder transport generation $generation.")
    }

    private fun startTransportDiagnostics() {
        mainHandler.removeCallbacks(transportDiagnostics)
        mainHandler.postDelayed(transportDiagnostics, TRANSPORT_DIAGNOSTIC_INTERVAL_MILLIS)
    }

    private val transportDiagnostics = object : Runnable {
        override fun run() {
            if (!captureRequested || fatalTransportError) return

            val client = stream.getStreamClient()
            val snapshot = RtspTransportHealthMonitor.Snapshot(
                queueItems = client.getItemsInCache(),
                queueCapacity = client.getCacheSize(),
                sentVideoFrames = client.getSentVideoFrames(),
                sentAudioFrames = client.getSentAudioFrames(),
                droppedVideoFrames = client.getDroppedVideoFrames(),
                droppedAudioFrames = client.getDroppedAudioFrames(),
                bytesSent = client.getBytesSend(),
            )
            val progress = senderProgressMonitor.observe(
                RtspSenderProgressMonitor.Snapshot(
                    elapsedRealtimeMillis = SystemClock.elapsedRealtime(),
                    cameraFrames = cameraFramesProduced.get(),
                    queueItems = snapshot.queueItems,
                    sentVideoFrames = snapshot.sentVideoFrames,
                    sentAudioFrames = snapshot.sentAudioFrames,
                    senderByteCounter = snapshot.bytesSent,
                ),
            )
            Log.i(
                TAG,
                "RTSP transport probe: callbackConnected=$transportConnected, " +
                    "retryScheduled=${retryState.isTimerScheduled}, retryInFlight=${retryState.isAttemptInFlight}, " +
                    "senderQueue=${snapshot.queueItems}/${snapshot.queueCapacity}, " +
                    "sentVideo=${snapshot.sentVideoFrames}, sentAudio=${snapshot.sentAudioFrames}, " +
                    "droppedVideo=${snapshot.droppedVideoFrames}, droppedAudio=${snapshot.droppedAudioFrames}, " +
                    "senderByteCounter=${snapshot.bytesSent}, " +
                    "cameraInputDelta=${progress.cameraFramesDelta}, queueDelta=${progress.queueDelta}, " +
                    "senderFrameDelta=${progress.senderFramesDelta}, senderByteDelta=${progress.senderBytesDelta}, " +
                    "lastRootEncoderSenderProgress=${progress.lastSenderProgressElapsedMillis}ms, " +
                    "socketWriteCompletion=not_exposed_by_rootencoder.",
            )
            if (transportConnected && progress.shouldRebuild && !rebuildingTransport) {
                transportConnected = false
                Log.w(
                    TAG,
                    "RTSP sender progress stalled for ${progress.stalledSamples} probes while Camera2 input " +
                        "continued; rebuilding before the sender queue fills.",
                )
                reportLifecycle(StreamLifecycle.RECONNECTING, "RTSP sender stalled; rebuilding transport.")
                rebuildTransportStack("camera input continued while RootEncoder sender counters froze")
                return
            }
            val result = transportHealthMonitor.observe(snapshot)
            if (transportConnected && result.shouldReconnect) {
                Log.w(TAG, "RTSP transport stall detected: ${result.reason}")
                awaitKtorFailure(result.reason ?: "RTSP sender stalled")
            }
            mainHandler.postDelayed(this, TRANSPORT_DIAGNOSTIC_INTERVAL_MILLIS)
        }
    }

    /**
     * RootEncoder otherwise selects a camera surface independently from the encoder size. Its GL
     * route does not aspect-fit a mismatched camera surface, so require an actual 16:9 Camera2 size.
     */
    private fun selectCameraSize(): Size {
        val supported = cameraSource.getCameraResolutions(CameraHelper.Facing.BACK)
        val target = Size(VIDEO_WIDTH, VIDEO_HEIGHT)
        val matchingAspect = supported.filter { size ->
            size.width.toLong() * target.height == size.height.toLong() * target.width
        }
        require(matchingAspect.isNotEmpty()) {
            "The rear camera exposes no 16:9 output size; refusing to stretch it into " +
                "${VIDEO_WIDTH}x${VIDEO_HEIGHT}. Supported: ${supported.joinToString()}"
        }
        return matchingAspect.minWith(
            compareBy<Size> { abs(it.width - target.width) + abs(it.height - target.height) }
                .thenBy { abs((it.width.toLong() * it.height) - (target.width.toLong() * target.height)) },
        )
    }

    private fun logCameraGeometry(cameraSize: Size) {
        val cameraId = cameraSource.getCurrentCameraId()
        val cameraManager = applicationContext.getSystemService(CameraManager::class.java)
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)
        Log.i(
            TAG,
            "Camera geometry: id=$cameraId, sensorOrientation=$sensorOrientation, " +
                "selectedCameraSize=${cameraSize.width}x${cameraSize.height}, " +
                "encoder=${VIDEO_WIDTH}x${VIDEO_HEIGHT}, rotation=$VIDEO_ROTATION_DEGREES, " +
                "requestedCameraToEncoder=16:9-to-16:9.",
        )
    }

    private fun cameraTimestampSource(): String {
        val cameraManager = applicationContext.getSystemService(CameraManager::class.java)
        val source = cameraManager.getCameraCharacteristics(cameraSource.getCurrentCameraId())
            .get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
        return when (source) {
            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME -> "realtime"
            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN -> "unknown"
            else -> "unknown"
        }.also { Log.i(TAG, "Camera sensor timestamp source: $it") }
    }

    private fun scheduleTextureDiagnostics(cameraSize: Size) {
        mainHandler.postDelayed({
            if (!stream.isStreaming) return@postDelayed
            try {
                val texture = stream.getGlInterface().getSurfaceTexture()
                val matrix = FloatArray(16)
                texture.getTransformMatrix(matrix)
                // Android matrices are column-major: (m0, m1) maps source X and (m4, m5) source Y.
                val sourceXScale = sqrt(matrix[0] * matrix[0] + matrix[1] * matrix[1])
                val sourceYScale = sqrt(matrix[4] * matrix[4] + matrix[5] * matrix[5])
                val axesExchanged = isAxisExchange(matrix)
                val transformedSourceAspect = if (axesExchanged) {
                    cameraSize.height.toFloat() / cameraSize.width
                } else {
                    cameraSize.width.toFloat() / cameraSize.height
                }
                Log.i(
                    TAG,
                    "SurfaceTexture geometry: defaultBuffer=requested ${cameraSize.width}x${cameraSize.height} " +
                        "(Android SurfaceTexture does not expose its negotiated buffer dimensions), " +
                        "camera=${cameraSize.width}x${cameraSize.height}, " +
                        "encoderViewport=${VIDEO_WIDTH}x${VIDEO_HEIGHT}, " +
                        "nominalSourceAspect=${formatAspect(cameraSize)}, " +
                        "stAxesExchanged=$axesExchanged, stSourceAspect=${formatFloat(transformedSourceAspect)}, " +
                        "mvpCompensation=$CAMERA_TEXTURE_COMPENSATION_DEGREES, " +
                        "composedSourceAspect=${formatAspect(cameraSize)}, " +
                        "destinationAspect=${formatAspect(VIDEO_WIDTH, VIDEO_HEIGHT)}, " +
                        "textureScaleX=${formatFloat(sourceXScale)}, textureScaleY=${formatFloat(sourceYScale)}, " +
                        "matrix=${matrix.joinToString(prefix = "[", postfix = "]") { formatFloat(it) }}",
                )
            } catch (error: RuntimeException) {
                Log.w(TAG, "Unable to read RootEncoder SurfaceTexture diagnostics.", error)
            }
        }, TEXTURE_DIAGNOSTIC_DELAY_MILLIS)
    }

    private fun formatAspect(size: Size): String = formatAspect(size.width, size.height)

    private fun formatAspect(width: Int, height: Int): String = formatFloat(width.toFloat() / height)

    private fun formatFloat(value: Float): String = String.format(Locale.US, "%.5f", value)

    private fun isAxisExchange(matrix: FloatArray): Boolean =
        abs(matrix[0]) < MATRIX_EPSILON &&
            abs(matrix[5]) < MATRIX_EPSILON &&
            abs(matrix[1]) > MATRIX_EPSILON &&
            abs(matrix[4]) > MATRIX_EPSILON

    private companion object {
        const val TAG = "RtspPublisher"
        const val VIDEO_WIDTH = 1280
        const val VIDEO_HEIGHT = 720
        const val VIDEO_FPS = 30
        const val VIDEO_BITRATE_BITS_PER_SECOND = 2_000_000
        const val VIDEO_KEYFRAME_INTERVAL_SECONDS = 2
        const val VIDEO_ROTATION_DEGREES = 0
        const val CAMERA_TEXTURE_COMPENSATION_DEGREES = 270
        const val AUDIO_SAMPLE_RATE_HZ = 44_100
        const val AUDIO_BITRATE_BITS_PER_SECOND = 128_000
        const val TEXTURE_DIAGNOSTIC_DELAY_MILLIS = 1_000L
        const val MATRIX_EPSILON = 0.0001f
        const val ROOT_ENCODER_RETRY_BUDGET = 1_000
        const val RTSP_SOCKET_TIMEOUT_MILLIS = 8_000L
        const val TRANSPORT_DIAGNOSTIC_INTERVAL_MILLIS = 2_000L
        const val KTOR_FAILURE_GRACE_MILLIS = RTSP_SOCKET_TIMEOUT_MILLIS + 2_000L
        const val RETRY_ATTEMPT_DEADLINE_MILLIS = 12_000L
        const val TRANSPORT_REBUILD_DELAY_MILLIS = 750L
        val mainHandler = Handler(Looper.getMainLooper())
    }
}
