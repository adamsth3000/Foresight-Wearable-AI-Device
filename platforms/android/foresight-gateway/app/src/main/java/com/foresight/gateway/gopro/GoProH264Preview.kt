package com.foresight.gateway.gopro

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Parsed AVCDecoderConfigurationRecord data suitable for Android's AVC CSD buffers. */
data class AvcDecoderConfiguration(
    val nalLengthSize: Int,
    val sps: List<ByteArray>,
    val pps: List<ByteArray>,
) {
    fun csd0(): ByteArray = withStartCodes(sps)

    fun csd1(): ByteArray = withStartCodes(pps)

    private fun withStartCodes(units: List<ByteArray>): ByteArray {
        val output = ByteArray(units.sumOf { START_CODE.size + it.size })
        var offset = 0
        units.forEach { unit ->
            START_CODE.copyInto(output, offset)
            offset += START_CODE.size
            unit.copyInto(output, offset)
            offset += unit.size
        }
        return output
    }

    companion object {
        private val START_CODE = byteArrayOf(0, 0, 0, 1)

        fun parse(extradata: ByteArray): AvcDecoderConfiguration {
            fun fail(message: String): Nothing = throw IllegalArgumentException("Malformed avcC: $message")
            if (extradata.size < 7) fail("requires at least 7 bytes")
            if (extradata[0].toInt() and 0xff != 1) fail("unsupported configurationVersion")
            val nalLengthSize = (extradata[4].toInt() and 0x03) + 1
            if (nalLengthSize !in 1..4) fail("invalid NAL length size")
            var offset = 5
            val spsCount = extradata[offset++].toInt() and 0x1f
            if (spsCount == 0) fail("no SPS")
            val sps = readParameterSets(extradata, spsCount, offset, "SPS")
            offset = sps.nextOffset
            if (offset >= extradata.size) fail("missing PPS count")
            val ppsCount = extradata[offset++].toInt() and 0xff
            if (ppsCount == 0) fail("no PPS")
            val pps = readParameterSets(extradata, ppsCount, offset, "PPS")
            return AvcDecoderConfiguration(nalLengthSize, sps.units, pps.units)
        }

        private data class ParameterSets(val units: List<ByteArray>, val nextOffset: Int)

        private fun readParameterSets(
            source: ByteArray,
            count: Int,
            startOffset: Int,
            label: String,
        ): ParameterSets {
            var offset = startOffset
            val units = ArrayList<ByteArray>(count)
            repeat(count) {
                if (offset + 2 > source.size) throw IllegalArgumentException("Malformed avcC: truncated $label length")
                val length = ((source[offset].toInt() and 0xff) shl 8) or (source[offset + 1].toInt() and 0xff)
                offset += 2
                if (length == 0 || offset + length > source.size) {
                    throw IllegalArgumentException("Malformed avcC: invalid $label length")
                }
                units += source.copyOfRange(offset, offset + length)
                offset += length
            }
            return ParameterSets(units, offset)
        }
    }
}

/** Converts one AVCC length-prefixed access unit without changing B1's source payload. */
object AvccAccessUnit {
    private val startCode = byteArrayOf(0, 0, 0, 1)

    fun toAnnexB(source: ByteArray, nalLengthSize: Int): ByteArray {
        require(nalLengthSize in 1..4) { "NAL length size must be 1 through 4" }
        var offset = 0
        var outputLength = 0
        while (offset < source.size) {
            if (offset + nalLengthSize > source.size) throw IllegalArgumentException("Malformed AVCC access unit: truncated NAL length")
            var length = 0
            repeat(nalLengthSize) { index -> length = (length shl 8) or (source[offset + index].toInt() and 0xff) }
            offset += nalLengthSize
            if (length == 0 || offset + length > source.size) throw IllegalArgumentException("Malformed AVCC access unit: invalid NAL length")
            outputLength += startCode.size + length
            offset += length
        }
        if (outputLength == 0) throw IllegalArgumentException("Malformed AVCC access unit: no NAL units")
        val output = ByteArray(outputLength)
        offset = 0
        var destination = 0
        while (offset < source.size) {
            var length = 0
            repeat(nalLengthSize) { index -> length = (length shl 8) or (source[offset + index].toInt() and 0xff) }
            offset += nalLengthSize
            startCode.copyInto(output, destination)
            destination += startCode.size
            source.copyInto(output, destination, offset, offset + length)
            destination += length
            offset += length
        }
        return output
    }
}

/** Small interface keeps MediaCodec out of JVM preview-state tests. */
interface GoProAvcDecoder {
    fun configure(format: GoProH264Format, config: AvcDecoderConfiguration, outputSurface: Any): String
    fun queueAccessUnit(data: ByteArray, presentationTimeUs: Long): Boolean
    fun drainOutput(): Int
    fun release()
}

fun interface GoProAvcDecoderFactory {
    fun create(): GoProAvcDecoder
}

private class AndroidGoProAvcDecoder : GoProAvcDecoder {
    private var codec: MediaCodec? = null

    override fun configure(format: GoProH264Format, config: AvcDecoderConfiguration, outputSurface: Any): String {
        val surface = outputSurface as? Surface ?: error("GoPro preview requires an Android Surface")
        val mediaFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, format.width, format.height).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(config.csd0()))
            setByteBuffer("csd-1", ByteBuffer.wrap(config.csd1()))
        }
        return MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also { created ->
            codec = created
            created.configure(mediaFormat, surface, null, 0)
            created.start()
        }.let { created ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) created.name else MediaFormat.MIMETYPE_VIDEO_AVC
        }
    }

    override fun queueAccessUnit(data: ByteArray, presentationTimeUs: Long): Boolean {
        val active = codec ?: return false
        val index = active.dequeueInputBuffer(0)
        if (index < 0) return false
        val input = active.getInputBuffer(index) ?: return false
        if (input.capacity() < data.size) return false
        input.clear()
        input.put(data)
        active.queueInputBuffer(index, 0, data.size, presentationTimeUs, 0)
        return true
    }

    override fun drainOutput(): Int {
        val active = codec ?: return 0
        val info = MediaCodec.BufferInfo()
        var rendered = 0
        while (true) {
            val index = active.dequeueOutputBuffer(info, 0)
            if (index < 0) return rendered
            active.releaseOutputBuffer(index, info.size > 0)
            if (info.size > 0) rendered += 1
        }
    }

    override fun release() {
        codec?.runCatching { stop() }
        codec?.release()
        codec = null
    }
}

/**
 * Service-owned, surface-attached H.264 preview consumer. Input is bounded independently from
 * B1's encoded transport; dropping preview work never feeds back into RTMP ingress.
 */
class GoProH264PreviewController(
    private val decoderFactory: GoProAvcDecoderFactory = GoProAvcDecoderFactory { AndroidGoProAvcDecoder() },
    private val executor: Executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ForesightGoProPreview").apply { isDaemon = true }
    },
    private val diagnosticsListener: (GoProPreviewDiagnostics) -> Unit = {},
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val lock = Any()
    private val pending = ArrayDeque<GoProEncodedSample>()
    private var closed = false
    private var drainScheduled = false
    private var attachedSurface: Any? = null
    private var format: GoProH264Format? = null
    private var config: AvcDecoderConfiguration? = null
    private var decoder: GoProAvcDecoder? = null
    private var diagnostics = GoProPreviewDiagnostics(queueCapacity = capacity)
    private var lastPublishedNanos = Long.MIN_VALUE

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    fun attachPreviewSurface(surface: Any) {
        executor.execute {
            synchronized(lock) { attachedSurface = surface }
            resetDecoderForSurface("Preview surface attached.")
        }
    }

    fun detachPreviewSurface(surface: Any? = null) {
        executor.execute {
            synchronized(lock) {
                if (surface != null && attachedSurface !== surface) return@execute
                attachedSurface = null
                pending.clear()
                diagnostics = diagnostics.copy(queueDepth = 0)
            }
            releaseDecoder()
            updateState(GoProPreviewState.DETACHED, "Preview surface detached.")
        }
    }

    fun acceptVideoFormat(next: GoProH264Format) {
        executor.execute {
            val parsed = runCatching { AvcDecoderConfiguration.parse(next.extradata) }
            synchronized(lock) {
                pending.removeIf { it.generationId != next.generationId }
                format = next.copy(extradata = next.extradata.copyOf())
                config = parsed.getOrNull()
                diagnostics = GoProPreviewDiagnostics(
                    state = diagnostics.state,
                    generationId = next.generationId,
                    framesQueued = diagnostics.framesQueued,
                    framesRendered = diagnostics.framesRendered,
                    framesDropped = diagnostics.framesDropped,
                    queueDepth = pending.size,
                    queueCapacity = capacity,
                )
            }
            releaseDecoder()
            val error = parsed.exceptionOrNull()
            when {
                error != null -> updateState(GoProPreviewState.ERROR, error.message)
                currentSurface() == null -> updateState(GoProPreviewState.DETACHED, "Codec config ready; waiting for preview surface.")
                else -> updateState(GoProPreviewState.WAITING_FOR_KEYFRAME, "Codec config ready; waiting for keyframe.")
            }
            scheduleDrain()
        }
    }

    /** A publisher boundary invalidates prior config and decoder state without touching RTMP ingest. */
    fun resetForPublisherBoundary(detail: String) {
        executor.execute {
            synchronized(lock) {
                pending.clear()
                format = null
                config = null
                diagnostics = diagnostics.copy(queueDepth = 0)
            }
            releaseDecoder()
            val nextState = if (currentSurface() == null) GoProPreviewState.DETACHED else GoProPreviewState.WAITING_FOR_STREAM
            updateState(nextState, detail)
        }
    }

    fun acceptVideoSample(sample: GoProEncodedSample) {
        if (sample.presentationTimeUs == null) {
            recordDrop("Preview dropped video sample without PTS.")
            return
        }
        synchronized(lock) {
            if (closed || attachedSurface == null) return
            if (pending.size == capacity) {
                pending.removeFirst()
                diagnostics = diagnostics.copy(framesDropped = diagnostics.framesDropped + 1)
            }
            pending.addLast(sample)
            diagnostics = diagnostics.copy(queueDepth = pending.size)
        }
        scheduleDrain()
    }

    fun diagnostics(): GoProPreviewDiagnostics = synchronized(lock) { diagnostics }

    fun close() {
        synchronized(lock) {
            closed = true
            pending.clear()
        }
        executor.execute {
            releaseDecoder()
            updateState(GoProPreviewState.DETACHED, "Preview closed.")
        }
        (executor as? ExecutorService)?.shutdownNow()
    }

    private fun scheduleDrain() {
        val shouldSchedule = synchronized(lock) {
            if (drainScheduled || closed) false else {
                drainScheduled = true
                true
            }
        }
        if (shouldSchedule) executor.execute(::drain)
    }

    private fun drain() {
        while (true) {
            val sample = synchronized(lock) {
                if (pending.isEmpty() || closed) {
                    drainScheduled = false
                    diagnostics = diagnostics.copy(queueDepth = pending.size)
                    return
                }
                pending.removeFirst().also { diagnostics = diagnostics.copy(queueDepth = pending.size) }
            }
            decode(sample)
        }
    }

    private fun decode(sample: GoProEncodedSample) {
        val activeFormat = synchronized(lock) { format }
        val activeConfig = synchronized(lock) { config }
        val surface = currentSurface()
        if (surface == null) return
        if (activeFormat == null || activeConfig == null) {
            recordDrop("Preview waiting for valid AVC config.", GoProPreviewState.WAITING_FOR_CONFIG)
            return
        }
        if (sample.generationId != activeFormat.generationId) {
            recordDrop("Preview dropped stale generation ${sample.generationId}.")
            return
        }
        if (decoder == null) {
            if (!sample.keyFrame) {
                recordDrop("Preview waiting for keyframe.", GoProPreviewState.WAITING_FOR_KEYFRAME)
                return
            }
            updateState(GoProPreviewState.CONFIGURING, "Configuring AVC decoder for generation ${sample.generationId}.")
            decoder = runCatching {
                decoderFactory.create().also { created ->
                    val name = created.configure(activeFormat, activeConfig, surface)
                    updateDecoderName(name)
                }
            }.getOrElse { error ->
                updateState(GoProPreviewState.ERROR, "AVC decoder configure failed: ${error.message}")
                return
            }
            updateState(GoProPreviewState.DECODING, "AVC decoder running; source PTS in microseconds.")
        }
        val accessUnit = runCatching {
            when (activeFormat.representation) {
                GoProH264Representation.AVCC -> AvccAccessUnit.toAnnexB(sample.data, activeConfig.nalLengthSize)
                GoProH264Representation.ANNEX_B -> sample.data
                GoProH264Representation.UNKNOWN -> error("Unsupported H.264 representation")
            }
        }.getOrElse { error ->
            recordDrop("Preview access-unit conversion failed: ${error.message}")
            return
        }
        val activeDecoder = decoder ?: return
        runCatching {
            if (!activeDecoder.queueAccessUnit(accessUnit, sample.presentationTimeUs ?: return)) {
                recordDrop("Preview decoder input unavailable.")
                return
            }
            val rendered = activeDecoder.drainOutput()
            synchronized(lock) {
                diagnostics = diagnostics.copy(
                    framesQueued = diagnostics.framesQueued + 1,
                    framesRendered = diagnostics.framesRendered + rendered,
                )
            }
            publish()
        }.onFailure { error ->
            releaseDecoder()
            updateState(GoProPreviewState.ERROR, "AVC decoder failed: ${error.message}")
        }
    }

    private fun resetDecoderForSurface(detail: String) {
        releaseDecoder()
        when {
            synchronized(lock) { format } == null -> updateState(GoProPreviewState.WAITING_FOR_STREAM, detail)
            synchronized(lock) { config } == null -> updateState(GoProPreviewState.WAITING_FOR_CONFIG, detail)
            else -> updateState(GoProPreviewState.WAITING_FOR_KEYFRAME, detail)
        }
    }

    private fun releaseDecoder() {
        decoder?.release()
        decoder = null
        synchronized(lock) { diagnostics = diagnostics.copy(decoderName = null) }
    }

    private fun currentSurface(): Any? = synchronized(lock) { attachedSurface }

    private fun updateDecoderName(name: String) {
        synchronized(lock) { diagnostics = diagnostics.copy(decoderName = name) }
    }

    private fun recordDrop(detail: String, state: GoProPreviewState? = null) {
        synchronized(lock) { diagnostics = diagnostics.copy(framesDropped = diagnostics.framesDropped + 1) }
        if (state != null) updateState(state, detail) else publish(detail)
    }

    private fun updateState(state: GoProPreviewState, detail: String?) {
        synchronized(lock) { diagnostics = diagnostics.copy(state = state, detail = detail) }
        publish(force = true)
    }

    private fun publish(detail: String? = null, force: Boolean = false) {
        val snapshot = synchronized(lock) {
            if (detail != null) diagnostics = diagnostics.copy(detail = detail)
            diagnostics
        }
        val now = System.nanoTime()
        if (!force && now - lastPublishedNanos < DIAGNOSTIC_INTERVAL_NANOS) return
        lastPublishedNanos = now
        diagnosticsListener(snapshot)
    }

    companion object {
        const val DEFAULT_CAPACITY = 8
        private const val DIAGNOSTIC_INTERVAL_NANOS = 500_000_000L
    }
}
