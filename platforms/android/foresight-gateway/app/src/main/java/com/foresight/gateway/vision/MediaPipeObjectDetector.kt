package com.foresight.gateway.vision

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.Detection
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** MediaPipe IMAGE-mode adapter; all inference is serialized on its dedicated worker. */
internal class MediaPipeObjectDetector(
    private val context: Context,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ForesightVisionInference").apply { isDaemon = true }
    },
) : OnDeviceVisionDetector {
    override val backendIdentity: String = "mediapipe_tasks_vision"
    override val modelIdentity: String = MODEL_ASSET_NAME

    private val lock = Any()
    private val inferenceGate = OnDeviceVisionInferenceGate()
    private var detector: ObjectDetector? = null
    private var closed = false

    override fun isReadyForFrame(): Boolean = synchronized(lock) { !closed }

    override fun submit(
        frame: SampledPreviewFrame,
        onSnapshot: (DetectionSnapshot) -> Unit,
        onFailure: (Throwable) -> Unit,
    ): Boolean {
        synchronized(lock) {
            if (closed || !inferenceGate.tryBegin()) {
                Log.i(TAG, "SKIPPED_BUSY frameId=${frame.frameId} skipped=${inferenceGate.skippedSamples()}")
                frame.bitmap.recycle()
                return false
            }
        }
        executor.execute {
            val sampleStartedNanos = SystemClock.elapsedRealtimeNanos()
            try {
                val activeDetector = detector ?: createDetector().also { detector = it }
                val inferenceStartedNanos = SystemClock.elapsedRealtimeNanos()
                val result = activeDetector.detect(BitmapImageBuilder(frame.bitmap).build())
                val completedNanos = SystemClock.elapsedRealtimeNanos()
                val detections = MediaPipeDetectionMapper.map(
                    candidates = result.detections().map(::candidate),
                    sourceWidth = frame.bitmap.width,
                    sourceHeight = frame.bitmap.height,
                )
                Log.i(
                    TAG,
                    "INFERENCE frameId=${frame.frameId} inferenceMs=${elapsedMillis(inferenceStartedNanos, completedNanos)} " +
                        "sampleToResultMs=${elapsedMillis(frame.captureElapsedRealtimeNanos, completedNanos)} " +
                        "workerQueueMs=${elapsedMillis(frame.captureElapsedRealtimeNanos, sampleStartedNanos)} detections=${detections.size} " +
                        "skipped=${inferenceGate.skippedSamples()}",
                )
                onSnapshot(
                    DetectionSnapshot(
                        runtimeGeneration = frame.runtimeGeneration,
                        frameId = frame.frameId,
                        captureElapsedRealtimeNanos = frame.captureElapsedRealtimeNanos,
                        sourceWidth = frame.bitmap.width,
                        sourceHeight = frame.bitmap.height,
                        detections = detections,
                        detectorBackend = backendIdentity,
                        detectorModel = modelIdentity,
                    ),
                )
            } catch (error: Throwable) {
                Log.e(TAG, "INFERENCE_FAILURE frameId=${frame.frameId} ${error.javaClass.simpleName}: ${error.message}", error)
                onFailure(error)
            } finally {
                frame.bitmap.recycle()
                synchronized(lock) { inferenceGate.complete() }
            }
        }
        return true
    }

    override fun close() {
        val detectorToClose = synchronized(lock) {
            if (closed) return@synchronized null
            closed = true
            detector.also { detector = null }
        }
        runCatching { detectorToClose?.close() }
        executor.shutdownNow()
    }

    private fun createDetector(): ObjectDetector {
        val startedNanos = SystemClock.elapsedRealtimeNanos()
        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_ASSET_NAME).build())
            .setRunningMode(RunningMode.IMAGE)
            .setScoreThreshold(SCORE_THRESHOLD)
            .setMaxResults(MAX_RESULTS)
            .build()
        return ObjectDetector.createFromOptions(context, options).also {
            Log.i(TAG, "INITIALIZED model=$MODEL_ASSET_NAME initMs=${elapsedMillis(startedNanos, SystemClock.elapsedRealtimeNanos())}")
        }
    }

    private fun candidate(detection: Detection): MediaPipeDetectionCandidate {
        val category = detection.categories().maxByOrNull { it.score() }
        val box = detection.boundingBox()
        return MediaPipeDetectionCandidate(
            label = category?.categoryName() ?: category?.displayName(),
            confidence = category?.score() ?: 0f,
            left = box.left,
            top = box.top,
            right = box.right,
            bottom = box.bottom,
        )
    }

    private fun elapsedMillis(startNanos: Long, endNanos: Long): Long = (endNanos - startNanos) / NANOS_PER_MILLISECOND

    private companion object {
        const val TAG = "ForesightVision"
        const val MODEL_ASSET_NAME = "efficientdet_lite0_coco_int8.tflite"
        const val SCORE_THRESHOLD = 0.45f
        const val MAX_RESULTS = 10
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
