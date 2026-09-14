package com.foresight.gateway.capture

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.view.SurfaceView
import com.foresight.gateway.transport.RtspPublisher
import com.foresight.gateway.transport.StreamLifecycle

internal interface CaptureWorker { fun execute(block: () -> Unit); fun close() }

internal class HandlerCaptureWorker : CaptureWorker {
    private val thread = HandlerThread("ForesightCaptureWorker").apply { start() }
    internal val handler = Handler(thread.looper)
    override fun execute(block: () -> Unit) { handler.post(block) }
    override fun close() { thread.quitSafely() }
}

internal interface PhonePublisherRuntime {
    fun start(endpoint: String?, sourceSessionId: String): Boolean
    fun stop()
    fun generation(): Int
    fun lifecycle(): StreamLifecycle
    fun attachPreview(surfaceView: SurfaceView)
    fun detachPreview(surfaceView: SurfaceView)
    fun localRecordingContext(): LocalRecordingContext?
}

internal class RtspPhonePublisherRuntime(private val publisher: RtspPublisher) : PhonePublisherRuntime {
    override fun start(endpoint: String?, sourceSessionId: String) = publisher.start(endpoint, sourceSessionId)
    override fun stop() = publisher.stop()
    override fun generation() = publisher.generation()
    override fun lifecycle() = publisher.lifecycle()
    override fun attachPreview(surfaceView: SurfaceView) = publisher.attachPreview(surfaceView)
    override fun detachPreview(surfaceView: SurfaceView) = publisher.detachPreview(surfaceView)
    override fun localRecordingContext() = publisher.localRecordingContext()
}

internal typealias PhonePublisherRuntimeFactory = (Context, RtspPublisher.Listener, CaptureWorker) -> PhonePublisherRuntime

internal val defaultPhonePublisherRuntimeFactory: PhonePublisherRuntimeFactory = { context, listener, worker ->
    val handlerWorker = worker as? HandlerCaptureWorker
        ?: error("The production phone publisher requires HandlerCaptureWorker.")
    RtspPhonePublisherRuntime(RtspPublisher(context, listener, handlerWorker.handler))
}
