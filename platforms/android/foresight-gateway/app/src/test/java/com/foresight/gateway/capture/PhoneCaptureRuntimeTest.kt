package com.foresight.gateway.capture

import android.content.Context
import android.content.ContextWrapper
import android.view.SurfaceView
import com.foresight.gateway.metadata.CaptureSessionMetadata
import com.foresight.gateway.metadata.ClockAnchor
import com.foresight.gateway.transport.RtspPublisher
import com.foresight.gateway.transport.StreamLifecycle
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.time.Instant

class PhoneCaptureRuntimeTest {
    @Test fun `controller uses injected worker and publisher runtime for external support media`() {
        val worker = ImmediateWorker(); val runtime = FakeRuntime(); var factories = 0
        val controller = controller(worker, runtime) { factories++ }
        controller.start("rtsp://test", "http://telemetry", true, session())
        controller.start("rtsp://test", "http://telemetry", true, session())
        controller.stop(true); controller.stop(true)
        assertEquals(4, worker.executions)
        assertEquals(1, factories); assertEquals(1, runtime.starts); assertEquals(1, runtime.stops)
    }

    @Test fun `local recording context delegates through runtime`() {
        val runtime = FakeRuntime(context = recording()); val controller = controller(ImmediateWorker(), runtime) { }
        assertEquals(recording().recordingId, PhoneLocalMediaSource(runtime).currentRecordingContext()?.recordingId)
        assertEquals(LocalMediaSourceId.PHONE_CAMERA, PhoneLocalMediaSource(runtime).mediaSourceId)
    }

    private fun controller(worker: ImmediateWorker, runtime: FakeRuntime, factory: () -> Unit) = PhoneCaptureController(
        TestContext(), object : PhoneCaptureController.Listener { override fun onCaptureStateChanged(lifecycle: StreamLifecycle, metadata: CaptureSessionMetadata?, detail: String?) = Unit },
        LocalRecordingMetadataRepository(File("build/test-runtime-meta"), File("build/test-runtime-recordings")), worker,
        { _, listener, _ -> factory(); runtime.listener = listener; runtime },
        LocalEventMediaExtractor(TestContext(), LocalRecordingMetadataRepository(File("build/test-runtime-meta"), File("build/test-runtime-recordings"))),
    )
    private fun session() = CaptureSessionMetadata(streamEndpoint = "rtsp://test", clockAnchor = ClockAnchor(Instant.EPOCH, 1))
    private fun recording() = LocalRecordingContext("recording", "session", 1, "capture-recording.mp4", Instant.EPOCH, 1, true)
    private class ImmediateWorker : CaptureWorker { var executions=0; override fun execute(block: () -> Unit) { executions++; block() }; override fun close() = Unit }
    private class TestContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File("build/test-runtime-files").apply { mkdirs() }
    }
    private class FakeRuntime(var context: LocalRecordingContext? = null) : PhonePublisherRuntime {
        lateinit var listener: RtspPublisher.Listener; var starts=0; var stops=0; var attachments=0; var detachments=0
        override fun start(endpoint: String?, sourceSessionId: String): Boolean { starts++; listener.onLifecycleChanged(StreamLifecycle.PREPARING); return true }
        override fun stop() { stops++; listener.onLifecycleChanged(StreamLifecycle.IDLE) }
        override fun generation()=1; override fun lifecycle()=StreamLifecycle.PREPARING
        override fun attachPreview(surfaceView: SurfaceView) { attachments++ }; override fun detachPreview(surfaceView: SurfaceView) { detachments++ }
        override fun localRecordingContext()=context
    }
}
