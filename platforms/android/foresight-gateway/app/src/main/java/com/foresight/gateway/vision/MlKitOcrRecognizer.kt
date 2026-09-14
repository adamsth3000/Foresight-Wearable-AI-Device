package com.foresight.gateway.vision

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

internal interface OnDeviceOcrRecognizer : AutoCloseable {
    fun submit(frame: SampledPreviewFrame, onObservation: (OcrObservation) -> Unit, onFailure: (Throwable) -> Unit): Boolean
}

/** ML Kit's bundled Latin text-recognition model on a newest-only worker. */
internal class MlKitOcrRecognizer(
    context: Context,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ForesightOcrInference").apply { isDaemon = true }
    },
) : OnDeviceOcrRecognizer {
    private val lock = Any()
    private val gate = OnDeviceVisionInferenceGate()
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var closed = false

    init { Log.i(TAG, "FORESIGHT_OCR_INITIALIZED runtime=mlkit_text_recognition_v2 model=bundled_latin") }

    override fun submit(frame: SampledPreviewFrame, onObservation: (OcrObservation) -> Unit, onFailure: (Throwable) -> Unit): Boolean {
        synchronized(lock) {
            if (closed || !gate.tryBegin()) {
                Log.i(TAG, "FORESIGHT_OCR_SKIPPED_BUSY frameId=${frame.frameId}")
                frame.bitmap.recycle()
                return false
            }
        }
        executor.execute {
            val started = SystemClock.elapsedRealtimeNanos()
            try {
                val result = Tasks.await(recognizer.process(InputImage.fromBitmap(frame.bitmap, 0)))
                val observation = OcrObservation(
                    runtimeGeneration = frame.runtimeGeneration,
                    sourceFrameId = frame.frameId,
                    timestampNanos = frame.captureElapsedRealtimeNanos,
                    sourceWidth = frame.bitmap.width,
                    sourceHeight = frame.bitmap.height,
                    regions = result.textBlocks.flatMapIndexed { blockIndex, block ->
                        block.lines.mapIndexedNotNull { lineIndex, line ->
                            line.boundingBox?.let { box -> OcrRegionMapper.region(line.text, box.left, box.top, box.right, box.bottom, frame.bitmap.width, frame.bitmap.height, lineIndex, blockIndex) }
                        }
                    },
                )
                Log.i(TAG, "FORESIGHT_OCR_INFERENCE frameId=${frame.frameId} elapsedMs=${(SystemClock.elapsedRealtimeNanos() - started) / 1_000_000L} regions=${observation.regions.size}")
                onObservation(observation)
            } catch (error: Throwable) {
                Log.w(TAG, "FORESIGHT_OCR_FAILURE type=${error.javaClass.simpleName}")
                onFailure(error)
            } finally {
                frame.bitmap.recycle()
                synchronized(lock) { gate.complete() }
            }
        }
        return true
    }

    override fun close() {
        synchronized(lock) { closed = true }
        recognizer.close()
        executor.shutdownNow()
    }

    private companion object { const val TAG = "ForesightVision" }
}

internal object OcrRegionMapper {
    fun region(text: String, leftPx: Int, topPx: Int, rightPx: Int, bottomPx: Int, width: Int, height: Int, lineIndex: Int, blockIndex: Int): OcrRegion? {
        val normalized = text.trim().takeIf { it.isNotEmpty() } ?: return null
        if (width <= 0 || height <= 0 || leftPx >= rightPx || topPx >= bottomPx) return null
        fun x(value: Int) = (value.toFloat() / width).coerceIn(0f, 1f)
        fun y(value: Int) = (value.toFloat() / height).coerceIn(0f, 1f)
        val left = min(x(leftPx), x(rightPx)); val right = max(x(leftPx), x(rightPx))
        val top = min(y(topPx), y(bottomPx)); val bottom = max(y(topPx), y(bottomPx))
        if (left >= right || top >= bottom) return null
        return OcrRegion(normalized, normalizedBoundingBox = LiveNormalizedBoundingBox(left, top, right, bottom), lineIndex = lineIndex, blockIndex = blockIndex)
    }
}
