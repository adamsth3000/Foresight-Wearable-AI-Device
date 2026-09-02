package com.foresight.gateway.gopro

import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Generation-isolated encoded media handoff. It owns copied JVM payloads only, holds at most 64
 * samples, and drops the oldest queued sample when full so a slow consumer cannot stall native
 * ingress. One serialized drain fans out immutable transport-owned samples to diagnostics and the
 * optional preview consumer; consumers never race to remove from this queue.
 */
class GoProEncodedMediaTransport(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val consumerExecutor: Executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ForesightGoProEncodedConsumer").apply { isDaemon = true }
    },
    private val diagnosticConsumer: (GoProEncodedSample) -> Unit = {},
    private val videoPreviewConsumer: (GoProEncodedSample) -> Unit = {},
    private val recordingConsumer: (GoProEncodedSample) -> Unit = {},
    private val diagnosticsListener: (GoProEncodedTransportDiagnostics) -> Unit = {},
) {
    private val lock = Any()
    private val queue = ArrayDeque<GoProEncodedSample>()
    private var consumerScheduled = false
    private var closed = false
    private var diagnostics = GoProEncodedTransportDiagnostics(queueCapacity = capacity)
    private var videoFormat: GoProH264Format? = null
    private var audioFormat: GoProAacFormat? = null
    private var lastDiagnosticsNanos = Long.MIN_VALUE
    private var hasPublishedDiagnostics = false

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    fun acceptVideoFormat(format: GoProH264Format) = synchronized(lock) {
        resetForGenerationLocked(format.generationId)
        videoFormat = format.copy(extradata = format.extradata.copyOf())
        diagnostics = diagnostics.copy(videoRepresentation = format.representation)
        publishDiagnosticsLocked(force = true)
    }

    fun acceptAudioFormat(format: GoProAacFormat) = synchronized(lock) {
        resetForGenerationLocked(format.generationId)
        audioFormat = format.copy(extradata = format.extradata.copyOf())
        diagnostics = diagnostics.copy(audioRepresentation = format.representation)
        publishDiagnosticsLocked(force = true)
    }

    fun acceptSample(sample: GoProEncodedSample) = synchronized(lock) {
        if (closed) return
        resetForGenerationLocked(sample.generationId)
        val ownedSample = sample.copy(data = sample.data.copyOf())
        if (queue.size == capacity) {
            recordDropLocked(queue.removeFirst())
        }
        queue.addLast(ownedSample)
        diagnostics = when (ownedSample.streamType) {
            GoProStreamType.VIDEO -> diagnostics.copy(
                queueDepth = queue.size,
                peakQueueDepth = maxOf(diagnostics.peakQueueDepth, queue.size),
                videoSamplesReceived = diagnostics.videoSamplesReceived + 1,
                videoBytesReceived = diagnostics.videoBytesReceived + ownedSample.data.size,
                lastVideoPayloadBytes = ownedSample.data.size,
                videoRepresentation = ownedSample.videoRepresentation ?: diagnostics.videoRepresentation,
            )
            GoProStreamType.AUDIO -> diagnostics.copy(
                queueDepth = queue.size,
                peakQueueDepth = maxOf(diagnostics.peakQueueDepth, queue.size),
                audioSamplesReceived = diagnostics.audioSamplesReceived + 1,
                audioBytesReceived = diagnostics.audioBytesReceived + ownedSample.data.size,
                lastAudioPayloadBytes = ownedSample.data.size,
                audioRepresentation = ownedSample.audioRepresentation ?: diagnostics.audioRepresentation,
            )
        }
        scheduleConsumerLocked()
        publishDiagnosticsLocked(force = false)
    }

    fun diagnostics(): GoProEncodedTransportDiagnostics = synchronized(lock) { diagnostics }

    fun videoFormat(): GoProH264Format? = synchronized(lock) { videoFormat?.copy(extradata = videoFormat!!.extradata.copyOf()) }

    fun audioFormat(): GoProAacFormat? = synchronized(lock) { audioFormat?.copy(extradata = audioFormat!!.extradata.copyOf()) }

    fun close() {
        synchronized(lock) {
            closed = true
            queue.clear()
            diagnostics = diagnostics.copy(queueDepth = 0)
        }
        (consumerExecutor as? ExecutorService)?.shutdownNow()
    }

    private fun resetForGenerationLocked(generationId: Long) {
        if (diagnostics.generationId == generationId) return
        queue.clear()
        diagnostics = GoProEncodedTransportDiagnostics(generationId = generationId, queueCapacity = capacity)
        videoFormat = null
        audioFormat = null
        consumerScheduled = false
        hasPublishedDiagnostics = false
    }

    private fun recordDropLocked(sample: GoProEncodedSample) {
        diagnostics = when (sample.streamType) {
            GoProStreamType.VIDEO -> diagnostics.copy(
                samplesDropped = diagnostics.samplesDropped + 1,
                videoSamplesDropped = diagnostics.videoSamplesDropped + 1,
            )
            GoProStreamType.AUDIO -> diagnostics.copy(
                samplesDropped = diagnostics.samplesDropped + 1,
                audioSamplesDropped = diagnostics.audioSamplesDropped + 1,
            )
        }
    }

    private fun scheduleConsumerLocked() {
        if (consumerScheduled) return
        consumerScheduled = true
        consumerExecutor.execute(::drain)
    }

    private fun drain() {
        while (true) {
            val sample = synchronized(lock) {
                if (queue.isEmpty() || closed) {
                    consumerScheduled = false
                    publishDiagnosticsLocked(force = false)
                    return
                }
                queue.removeFirst().also {
                    diagnostics = diagnostics.copy(queueDepth = queue.size)
                }
            }
            diagnosticConsumer(sample)
            if (sample.streamType == GoProStreamType.VIDEO) videoPreviewConsumer(sample)
            recordingConsumer(sample)
        }
    }

    private fun publishDiagnosticsLocked(force: Boolean) {
        val now = System.nanoTime()
        if (!force && hasPublishedDiagnostics && now - lastDiagnosticsNanos < DIAGNOSTIC_INTERVAL_NANOS) return
        lastDiagnosticsNanos = now
        hasPublishedDiagnostics = true
        diagnosticsListener(diagnostics)
    }

    companion object {
        const val DEFAULT_CAPACITY = 64
        private const val DIAGNOSTIC_INTERVAL_NANOS = 500_000_000L
    }
}
