package com.foresight.gateway.gopro

import android.util.Log
import android.view.Surface
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Service-owned, single-publisher RTMP ingress lifecycle for the narrow GW1-A proof. */
class GoProRtmpIngress(
    private val listener: Listener,
    private val addressProvider: () -> String?,
    private val executor: Executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ForesightGoProIngress").apply { isDaemon = true }
    },
    private val backendFactory: (GoProIngressCallbacks) -> GoProIngressBackend = { callbacks ->
        NativeRtmpIngress(callbacks)
    },
    recordingDirectory: File = File(System.getProperty("java.io.tmpdir"), "foresight-gopro-recordings"),
) {
    interface Listener {
        fun onGoProIngressChanged(snapshot: GoProIngressSnapshot)
    }

    private var backend: GoProIngressBackend? = null
    private val previewController = GoProH264PreviewController(diagnosticsListener = ::onPreviewDiagnostics)
    private val recorder = GoProMp4Recorder(recordingDirectory, diagnosticsListener = ::onRecordingDiagnostics)
    private val encodedTransport = GoProEncodedMediaTransport(
        videoPreviewConsumer = previewController::acceptVideoSample,
        recordingConsumer = recorder::acceptSample,
        diagnosticsListener = ::onEncodedTransportDiagnostics,
    )
    @Volatile
    private var requested = false
    @Volatile
    private var snapshot = GoProIngressSnapshot()

    @Synchronized
    fun start(port: Int = DEFAULT_PORT, path: String = DEFAULT_PATH): GoProIngressSnapshot {
        if (requested) return snapshot
        val host = addressProvider()
        if (host == null) {
            update(GoProIngressSnapshot(GoProSourceStatus.ERROR, detail = "No usable LAN IPv4 address found."))
            return snapshot
        }
        requested = true
        val destination = "rtmp://$host:$port/$path"
        update(GoProIngressSnapshot(GoProSourceStatus.LISTENING, destination, detail = "Starting RTMP listener."))
        val activeBackend: GoProIngressBackend = backend ?: backendFactory(
            GoProIngressCallbacks(
                eventListener = ::onNativeEvent,
                mediaDiagnosticsListener = ::onNativeMediaDiagnostics,
                videoFormatListener = ::onVideoFormat,
                audioFormatListener = encodedTransport::acceptAudioFormat,
                sampleListener = encodedTransport::acceptSample,
            ),
        ).also { created ->
            backend = created
        }
        executor.execute {
            runCatching { activeBackend.run("0.0.0.0", port, path) }
                .onFailure { error ->
                    if (requested) update(GoProIngressSnapshot(GoProSourceStatus.ERROR, destination, detail = error.message))
                }
        }
        return snapshot
    }

    @Synchronized
    fun stop(): GoProIngressSnapshot {
        if (!requested && snapshot.status == GoProSourceStatus.STOPPED) return snapshot
        requested = false
        backend?.stop()
        update(GoProIngressSnapshot(GoProSourceStatus.STOPPED, detail = "RTMP listener stopped."))
        return snapshot
    }

    fun snapshot(): GoProIngressSnapshot = snapshot

    /** Activity-owned Surface attachment; it does not affect service-owned RTMP ingest. */
    fun attachPreviewSurface(surface: Surface) {
        previewController.attachPreviewSurface(surface)
    }

    fun detachPreviewSurface(surface: Surface? = null) {
        previewController.detachPreviewSurface(surface)
    }

    fun startRecording(): GoProRecordingDiagnostics {
        check(snapshot.status == GoProSourceStatus.LIVE) { "GoPro source must be LIVE before recording." }
        return recorder.start(encodedTransport.videoFormat(), encodedTransport.audioFormat())
    }

    fun stopRecording(): GoProRecordingDiagnostics = recorder.stop()

    fun close() {
        stop()
        encodedTransport.close()
        previewController.close()
        recorder.close()
        (executor as? ExecutorService)?.shutdownNow()
    }

    private fun onNativeEvent(event: NativeIngressEvent, detail: String, metadata: GoProStreamMetadata?) {
        if (!requested && event != NativeIngressEvent.ERROR) return
        val next = when (event) {
            NativeIngressEvent.LISTENING -> snapshot.copy(status = GoProSourceStatus.LISTENING, detail = detail)
            NativeIngressEvent.PUBLISHER_CONNECTED -> {
                previewController.resetForPublisherBoundary("Publisher connected; waiting for fresh AVC config.")
                recorder.onPublisherBoundary("New publisher connected before recording completed.")
                snapshot.copy(
                    status = GoProSourceStatus.PUBLISHER_CONNECTED,
                    mediaDiagnostics = null,
                    detail = detail,
                )
            }
            NativeIngressEvent.STREAM_METADATA -> snapshot.copy(status = GoProSourceStatus.LIVE, metadata = metadata, detail = detail)
            NativeIngressEvent.PUBLISHER_DISCONNECTED -> {
                previewController.resetForPublisherBoundary("Publisher disconnected; decoder reset.")
                recorder.onPublisherBoundary("GoPro publisher disconnected during recording.")
                snapshot.copy(status = GoProSourceStatus.LOST, detail = detail)
            }
            NativeIngressEvent.ERROR -> snapshot.copy(status = GoProSourceStatus.ERROR, detail = detail)
        }
        // android.util.Log is unavailable in local JVM unit tests.
        runCatching { Log.i(TAG, "GoPro RTMP event=$event status=${next.status} detail=$detail") }
        update(next)
    }

    private fun onNativeMediaDiagnostics(diagnostics: GoProMediaDiagnostics) {
        if (!requested) return
        val currentGeneration = snapshot.mediaDiagnostics?.generationId
        if (currentGeneration != null && diagnostics.generationId < currentGeneration) return
        update(snapshot.copy(mediaDiagnostics = diagnostics))
    }

    private fun onEncodedTransportDiagnostics(diagnostics: GoProEncodedTransportDiagnostics) {
        if (!requested) return
        update(snapshot.copy(encodedTransportDiagnostics = diagnostics))
    }

    private fun onVideoFormat(format: GoProH264Format) {
        encodedTransport.acceptVideoFormat(format)
        previewController.acceptVideoFormat(format)
        val activeGeneration = recorder.diagnostics().generationId
        if (activeGeneration != null && activeGeneration != format.generationId) {
            recorder.onPublisherBoundary("H.264 format changed to a new publisher generation.")
        }
    }

    private fun onPreviewDiagnostics(diagnostics: GoProPreviewDiagnostics) {
        if (!requested) return
        update(snapshot.copy(previewDiagnostics = diagnostics))
    }

    private fun onRecordingDiagnostics(diagnostics: GoProRecordingDiagnostics) {
        update(snapshot.copy(recordingDiagnostics = diagnostics))
    }

    private fun update(next: GoProIngressSnapshot) {
        snapshot = next
        listener.onGoProIngressChanged(next)
    }

    companion object {
        const val DEFAULT_PORT = 1935
        const val DEFAULT_PATH = "gopro"
        private const val TAG = "GoProRtmpIngress"
    }
}
