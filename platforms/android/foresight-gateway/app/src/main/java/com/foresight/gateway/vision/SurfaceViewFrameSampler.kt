package com.foresight.gateway.vision

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.SurfaceView
import kotlin.math.max
import kotlin.math.roundToInt

data class SampledPreviewFrame(
    val runtimeGeneration: Long,
    val frameId: Long,
    val captureElapsedRealtimeNanos: Long,
    val bitmap: Bitmap,
)

/** Samples the current display surface at a bounded rate without retaining a frame backlog. */
internal class SurfaceViewFrameSampler(
    private val sampleIntervalMillis: Long,
    private val maxSampleEdgePixels: Int = 640,
) : AutoCloseable {
    private val workerThread = HandlerThread("ForesightVisionSampler").apply { start() }
    private val workerHandler = Handler(workerThread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val frameGate = NewestOnlyFrameGate()
    private var running = false
    private var generation = 0L
    private var surfaceView: SurfaceView? = null
    private var canRequestFrame: (() -> Boolean)? = null
    private var onFrame: ((SampledPreviewFrame) -> Unit)? = null
    private var nextFrameId = 0L

    fun start(
        surfaceView: SurfaceView,
        runtimeGeneration: Long,
        canRequestFrame: () -> Boolean,
        onFrame: (SampledPreviewFrame) -> Unit,
    ) {
        if (running && generation == runtimeGeneration && this.surfaceView === surfaceView) return
        stop()
        this.running = true
        this.generation = runtimeGeneration
        this.surfaceView = surfaceView
        this.canRequestFrame = canRequestFrame
        this.onFrame = onFrame
        requestNextFrame(0L)
    }

    fun stop() {
        running = false
        frameGate.reset()
        mainHandler.removeCallbacksAndMessages(null)
        workerHandler.removeCallbacksAndMessages(null)
        surfaceView = null
        canRequestFrame = null
        onFrame = null
    }

    fun isRunningFor(runtimeGeneration: Long): Boolean = running && generation == runtimeGeneration

    override fun close() {
        stop()
        workerThread.quitSafely()
    }

    private fun requestNextFrame(delayMillis: Long) {
        workerHandler.postDelayed({ mainHandler.post(::requestFrameFromSurface) }, delayMillis)
    }

    private fun requestFrameFromSurface() {
        val activeSurfaceView = surfaceView ?: return
        if (!running || !activeSurfaceView.holder.surface.isValid || !(canRequestFrame?.invoke() ?: false)) {
            scheduleFollowingFrame()
            return
        }
        val width = activeSurfaceView.width
        val height = activeSurfaceView.height
        if (width <= 0 || height <= 0 || !frameGate.tryBegin(SystemClock.elapsedRealtime(), sampleIntervalMillis)) {
            scheduleFollowingFrame()
            return
        }
        val bitmap = Bitmap.createBitmap(
            sampledWidth(width, height),
            sampledHeight(width, height),
            Bitmap.Config.ARGB_8888,
        )
        val requestGeneration = generation
        val captureElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        val frameId = ++nextFrameId
        try {
            PixelCopy.request(activeSurfaceView, bitmap, { result ->
                frameGate.complete()
                if (result == PixelCopy.SUCCESS && running && generation == requestGeneration) {
                    onFrame?.invoke(
                        SampledPreviewFrame(requestGeneration, frameId, captureElapsedRealtimeNanos, bitmap),
                    ) ?: bitmap.recycle()
                } else {
                    bitmap.recycle()
                }
                scheduleFollowingFrame()
            }, workerHandler)
        } catch (_: IllegalArgumentException) {
            frameGate.complete()
            bitmap.recycle()
            scheduleFollowingFrame()
        }
    }

    private fun scheduleFollowingFrame() {
        if (running) requestNextFrame(sampleIntervalMillis)
    }

    private fun sampledWidth(width: Int, height: Int): Int {
        val scale = minOf(1f, maxSampleEdgePixels.toFloat() / max(width, height).toFloat())
        return max(1, (width * scale).roundToInt())
    }

    private fun sampledHeight(width: Int, height: Int): Int {
        val scale = minOf(1f, maxSampleEdgePixels.toFloat() / max(width, height).toFloat())
        return max(1, (height * scale).roundToInt())
    }
}
