package com.foresight.gateway.voice.conversation

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceView
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** Acquires one preview-only JPEG on demand; it never samples continuously or writes to disk. */
class SurfaceViewConversationVisualFrameProvider(
    private val surfaceView: () -> SurfaceView?,
    private val source: () -> String,
    private val maxFrameAgeMillis: Long = 1_500L,
) : ConversationVisualFrameProvider {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = HandlerThread("ForesightConversationFrame").apply { start() }
    private val workerHandler = Handler(worker.looper)

    override fun acquire(onComplete: (ConversationVisualFrame?) -> Unit) {
        mainHandler.post {
            val view = surfaceView()
            if (view == null || !view.holder.surface.isValid || view.width <= 0 || view.height <= 0) {
                onComplete(null)
                return@post
            }
            val bitmap = Bitmap.createBitmap(scaledWidth(view.width, view.height), scaledHeight(view.width, view.height), Bitmap.Config.ARGB_8888)
            val capturedAt = SystemClock.elapsedRealtime()
            runCatching {
                PixelCopy.request(view, bitmap, { result ->
                    if (result != PixelCopy.SUCCESS) {
                        bitmap.recycle(); onComplete(null); return@request
                    }
                    workerHandler.post {
                        val frame = runCatching { compress(bitmap, capturedAt) }.getOrNull()
                        bitmap.recycle()
                        mainHandler.post { onComplete(frame) }
                    }
                }, workerHandler)
            }.onFailure { bitmap.recycle(); onComplete(null) }
        }
    }

    private fun compress(bitmap: Bitmap, capturedAt: Long): ConversationVisualFrame? {
        val now = SystemClock.elapsedRealtime()
        if (now - capturedAt > maxFrameAgeMillis) return null
        val bytes = ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
            output.toByteArray()
        }
        return ConversationVisualFrame(bytes, bitmap.width, bitmap.height, capturedAt, source())
            .also { Log.i(TAG, "FORESIGHT_VISUAL_FRAME_READY source=${it.source} width=${it.width} height=${it.height} ageMs=${SystemClock.elapsedRealtime() - capturedAt} jpegBytes=${it.jpegBytes.size}") }
    }

    override fun close() { worker.quitSafely() }

    private fun scaledWidth(width: Int, height: Int): Int = scaled(width, height).first
    private fun scaledHeight(width: Int, height: Int): Int = scaled(width, height).second
    private fun scaled(width: Int, height: Int): Pair<Int, Int> {
        val factor = minOf(1f, MAX_EDGE.toFloat() / max(width, height).toFloat())
        return (width * factor).roundToInt().coerceAtLeast(1) to (height * factor).roundToInt().coerceAtLeast(1)
    }
    private companion object { const val TAG = "ForesightGoogleAI"; const val MAX_EDGE = 1024; const val JPEG_QUALITY = 82 }
}
