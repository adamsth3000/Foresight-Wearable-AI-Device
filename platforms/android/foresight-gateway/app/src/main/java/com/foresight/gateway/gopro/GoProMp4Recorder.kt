package com.foresight.gateway.gopro

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.FileInputStream
import java.io.FileWriter
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

interface GoProMp4Muxer {
    fun addTrack(format: GoProMuxerTrackFormat): Int
    fun start()
    fun writeSampleData(trackIndex: Int, data: ByteArray, presentationTimeUs: Long, flags: Int)
    fun stop()
    fun release()
}

data class GoProMuxerTrackFormat(
    val mime: String,
    val width: Int? = null,
    val height: Int? = null,
    val sampleRate: Int? = null,
    val channelCount: Int? = null,
    val csd0: ByteArray,
    val csd1: ByteArray? = null,
)

fun interface GoProMp4MuxerFactory {
    fun create(output: File): GoProMp4Muxer
}

private class AndroidGoProMp4Muxer(output: File) : GoProMp4Muxer {
    private val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

    override fun addTrack(format: GoProMuxerTrackFormat): Int {
        val mediaFormat = when (format.mime) {
            MediaFormat.MIMETYPE_VIDEO_AVC -> MediaFormat.createVideoFormat(format.mime, format.width!!, format.height!!)
            MediaFormat.MIMETYPE_AUDIO_AAC -> MediaFormat.createAudioFormat(format.mime, format.sampleRate!!, format.channelCount!!)
            else -> error("Unsupported GoPro muxer MIME ${format.mime}")
        }.apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(format.csd0))
            format.csd1?.let { setByteBuffer("csd-1", ByteBuffer.wrap(it)) }
        }
        return muxer.addTrack(mediaFormat)
    }
    override fun start() = muxer.start()
    override fun writeSampleData(trackIndex: Int, data: ByteArray, presentationTimeUs: Long, flags: Int) {
        muxer.writeSampleData(
            trackIndex,
            ByteBuffer.wrap(data),
            MediaCodec.BufferInfo().apply {
                set(0, data.size, presentationTimeUs, flags)
            },
        )
    }
    override fun stop() = muxer.stop()
    override fun release() = muxer.release()
}

/** Shared MediaFormat construction for B2's parsed avcC and B3's zero-transcode MP4 muxer. */
object GoProMuxerFormats {
    fun video(format: GoProH264Format): GoProMuxerTrackFormat {
        require(format.representation == GoProH264Representation.AVCC) { "MP4 recording requires AVCC H.264 samples" }
        val config = AvcDecoderConfiguration.parse(format.extradata)
        return GoProMuxerTrackFormat(MediaFormat.MIMETYPE_VIDEO_AVC, format.width, format.height, csd0 = config.csd0(), csd1 = config.csd1())
    }

    fun audio(format: GoProAacFormat): GoProMuxerTrackFormat {
        require(format.representation == GoProAacRepresentation.RAW_AAC) { "MP4 recording requires raw AAC access units" }
        val config = AacAudioSpecificConfig.parse(format.extradata)
        return GoProMuxerTrackFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            // AAC's AudioSpecificConfig is authoritative for MediaMuxer. Some FLV metadata can
            // advertise a different legacy rate; the mismatch remains visible in B1 diagnostics.
            sampleRate = config.sampleRate,
            channelCount = config.channelCount,
            csd0 = format.extradata.copyOf(),
        )
    }
}

data class AacAudioSpecificConfig(
    val audioObjectType: Int,
    val sampleRate: Int,
    val channelCount: Int,
) {
    companion object {
        private val sampleRates = intArrayOf(96_000, 88_200, 64_000, 48_000, 44_100, 32_000, 24_000, 22_050, 16_000, 12_000, 11_025, 8_000, 7_350)

        fun parse(data: ByteArray): AacAudioSpecificConfig {
            require(data.size >= 2) { "AAC AudioSpecificConfig requires at least two bytes" }
            val objectType = (data[0].toInt() and 0xff) shr 3
            val rateIndex = ((data[0].toInt() and 0x07) shl 1) or ((data[1].toInt() and 0x80) shr 7)
            require(objectType in 1..31) { "Invalid AAC object type" }
            require(rateIndex in sampleRates.indices) { "Unsupported AAC explicit sample rate" }
            val channels = (data[1].toInt() shr 3) and 0x0f
            require(channels in 1..7) { "Unsupported AAC channel configuration" }
            return AacAudioSpecificConfig(objectType, sampleRates[rateIndex], channels)
        }
    }
}

/**
 * B3 diagnostic recorder. It receives B1-owned immutable samples after the serialized transport
 * fan-out and writes recorder-local Annex-B H.264 plus raw AAC to MediaMuxer without decoding or
 * encoding. The authoritative B1 AVCC payload is never mutated.
 */
class GoProMp4Recorder(
    private val outputDirectory: File,
    private val muxerFactory: GoProMp4MuxerFactory = GoProMp4MuxerFactory { AndroidGoProMp4Muxer(it) },
    private val validator: (File) -> Unit = ::validateGoProMp4,
    private val executor: Executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ForesightGoProMp4Recorder").apply { isDaemon = true }
    },
    private val diagnosticsListener: (GoProRecordingDiagnostics) -> Unit = {},
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val lock = Any()
    private val queue = ArrayDeque<GoProEncodedSample>()
    private var drainScheduled = false
    private var finalizationStarted = false
    private var accepting = false
    private var stopRequested = false
    private var closed = false
    private var videoFormat: GoProH264Format? = null
    private var audioFormat: GoProAacFormat? = null
    private var muxer: GoProMp4Muxer? = null
    private var videoTrack = -1
    private var audioTrack = -1
    private var originUs: Long? = null
    private var lastVideoUs = -1L
    private var lastAudioUs = -1L
    private var partialFile: File? = null
    private var outputFile: File? = null
    private var metadataFile: File? = null
    private var startedAt: Instant? = null
    private var diagnostics = GoProRecordingDiagnostics(queueCapacity = capacity)

    init { require(capacity > 0) { "capacity must be positive" } }

    fun start(video: GoProH264Format?, audio: GoProAacFormat?): GoProRecordingDiagnostics {
        synchronized(lock) {
            if (diagnostics.state in setOf(GoProRecordingState.ARMING, GoProRecordingState.WAITING_FOR_KEYFRAME, GoProRecordingState.RECORDING, GoProRecordingState.FINALIZING)) {
                return diagnostics
            }
            requireNotNull(video) { "H.264 format is unavailable" }
            requireNotNull(audio) { "AAC format is unavailable; B3 expects HERO8 audio" }
            require(video.generationId == audio.generationId) { "Audio/video formats belong to different generations" }
            videoFormat = video.copy(extradata = video.extradata.copyOf())
            audioFormat = audio.copy(extradata = audio.extradata.copyOf())
            queue.clear()
            accepting = true
            stopRequested = false
            finalizationStarted = false
            originUs = null
            lastVideoUs = -1L
            lastAudioUs = -1L
            val id = UUID.randomUUID().toString()
            diagnostics = GoProRecordingDiagnostics(
                state = GoProRecordingState.ARMING,
                recordingId = id,
                generationId = video.generationId,
                outputFileName = "gopro-$id.mp4",
                metadataFileName = "gopro-$id.json",
                queueCapacity = capacity,
                detail = "Preparing zero-transcode MP4 recorder.",
            )
        }
        publish()
        executor.execute(::armMuxer)
        return diagnostics()
    }

    fun acceptSample(sample: GoProEncodedSample) {
        val schedule = synchronized(lock) {
            if (!accepting || sample.generationId != diagnostics.generationId) return
            if (queue.size == capacity) {
                accepting = false
                diagnostics = diagnostics.copy(
                    state = GoProRecordingState.ERROR,
                    queueDepth = queue.size,
                    terminationReason = "recording_queue_overflow",
                    detail = "Recording queue overflow; recording is incomplete.",
                )
                true
            } else {
                queue.addLast(sample)
                diagnostics = diagnostics.copy(
                    queueDepth = queue.size,
                    peakQueueDepth = maxOf(diagnostics.peakQueueDepth, queue.size),
                )
                !drainScheduled
            }
        }
        if (schedule) scheduleDrain()
    }

    fun onPublisherBoundary(detail: String) {
        val shouldSchedule = synchronized(lock) {
            if (!accepting && diagnostics.state != GoProRecordingState.WAITING_FOR_KEYFRAME) return
            accepting = false
            stopRequested = true
            diagnostics = diagnostics.copy(
                state = GoProRecordingState.INTERRUPTED,
                terminationReason = "publisher_boundary",
                detail = detail,
            )
            !drainScheduled
        }
        if (shouldSchedule) scheduleDrain()
    }

    fun stop(): GoProRecordingDiagnostics {
        val shouldSchedule = synchronized(lock) {
            if (diagnostics.state !in setOf(GoProRecordingState.ARMING, GoProRecordingState.WAITING_FOR_KEYFRAME, GoProRecordingState.RECORDING)) return diagnostics
            accepting = false
            stopRequested = true
            diagnostics = diagnostics.copy(
                state = GoProRecordingState.FINALIZING,
                terminationReason = "manual_stop",
                detail = "Draining recorder queue.",
            )
            !drainScheduled
        }
        publish()
        if (shouldSchedule) scheduleDrain()
        return diagnostics()
    }

    fun diagnostics(): GoProRecordingDiagnostics = synchronized(lock) { diagnostics }

    fun close() {
        onPublisherBoundary("Recorder closed with service.")
        (executor as? ExecutorService)?.shutdownNow()
    }

    private fun armMuxer() {
        // Queue overflow or source loss can happen before this worker starts. Never resurrect a
        // terminal attempt by replacing its state with WAITING_FOR_KEYFRAME.
        if (synchronized(lock) { diagnostics.state != GoProRecordingState.ARMING }) return
        val prepared = runCatching {
            val id = synchronized(lock) { diagnostics.recordingId ?: error("Recording ID missing") }
            outputDirectory.mkdirs()
            require(outputDirectory.isDirectory) { "Cannot create GoPro recording directory" }
            val partial = File(outputDirectory, "gopro-$id.partial")
            val output = File(outputDirectory, "gopro-$id.mp4")
            val metadata = File(outputDirectory, "gopro-$id.json")
            val activeVideo = synchronized(lock) { videoFormat } ?: error("H.264 format disappeared")
            val activeAudio = synchronized(lock) { audioFormat } ?: error("AAC format disappeared")
            val activeMuxer = muxerFactory.create(partial)
            val vTrack = activeMuxer.addTrack(GoProMuxerFormats.video(activeVideo))
            val aTrack = activeMuxer.addTrack(GoProMuxerFormats.audio(activeAudio))
            activeMuxer.start()
            synchronized(lock) {
                muxer = activeMuxer
                videoTrack = vTrack
                audioTrack = aTrack
                partialFile = partial
                outputFile = output
                metadataFile = metadata
                startedAt = Instant.now()
                diagnostics = diagnostics.copy(state = GoProRecordingState.WAITING_FOR_KEYFRAME, detail = "Waiting for next H.264 keyframe.")
            }
        }
        prepared.onFailure { fail(it.message ?: "Unable to arm MediaMuxer") }
            .onSuccess { publish(); scheduleDrain() }
    }

    private fun scheduleDrain() {
        val shouldSchedule = synchronized(lock) {
            if (drainScheduled || closed) false else { drainScheduled = true; true }
        }
        if (shouldSchedule) executor.execute(::drain)
    }

    private fun drain() {
        while (true) {
            var shouldFinalize = false
            val sample = synchronized(lock) {
                if (queue.isEmpty()) {
                    drainScheduled = false
                    diagnostics = diagnostics.copy(queueDepth = 0)
                    if (stopRequested || diagnostics.state == GoProRecordingState.ERROR || diagnostics.state == GoProRecordingState.INTERRUPTED) {
                        shouldFinalize = !finalizationStarted
                        finalizationStarted = true
                    }
                    null
                } else {
                    queue.removeFirst().also { diagnostics = diagnostics.copy(queueDepth = queue.size) }
                }
            }
            if (sample == null) {
                if (shouldFinalize) finalizeMuxer()
                return
            }
            if (diagnostics().state == GoProRecordingState.ERROR) continue
            write(sample)
        }
    }

    private fun write(sample: GoProEncodedSample) {
        val samplePts = sample.presentationTimeUs ?: return fail("Source sample PTS is unavailable")
        if (sample.streamType == GoProStreamType.VIDEO && originUs == null) {
            if (!sample.keyFrame) return
            originUs = samplePts
            synchronized(lock) { diagnostics = diagnostics.copy(state = GoProRecordingState.RECORDING, detail = "Recording from source keyframe.") }
            publish()
        }
        val origin = originUs ?: return // Audio and dependent video are intentionally discarded before origin.
        val normalized = samplePts - origin
        if (normalized < 0) return
        val previous = if (sample.streamType == GoProStreamType.VIDEO) lastVideoUs else lastAudioUs
        if (normalized < previous) return fail("Non-monotonic ${sample.streamType.name.lowercase()} PTS")
        val payload = if (sample.streamType == GoProStreamType.VIDEO) {
            val activeFormat = synchronized(lock) { videoFormat } ?: return fail("H.264 format is unavailable")
            runCatching {
                when (activeFormat.representation) {
                    GoProH264Representation.AVCC -> AvccAccessUnit.toAnnexB(
                        sample.data,
                        activeFormat.nalLengthSize ?: AvcDecoderConfiguration.parse(activeFormat.extradata).nalLengthSize,
                    )
                    GoProH264Representation.ANNEX_B -> sample.data
                    GoProH264Representation.UNKNOWN -> error("Unknown H.264 source representation")
                }
            }.getOrElse { error ->
                fail("Recorder H.264 framing conversion failed: ${error.message}")
                return
            }
        } else {
            sample.data
        }
        val track = if (sample.streamType == GoProStreamType.VIDEO) videoTrack else audioTrack
        val flags = if (sample.streamType == GoProStreamType.VIDEO && sample.keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
        runCatching { muxer?.writeSampleData(track, payload, normalized, flags) ?: error("MediaMuxer is unavailable") }
            .onFailure { fail("MediaMuxer write failed: ${it.message}"); return }
        synchronized(lock) {
            if (sample.streamType == GoProStreamType.VIDEO) {
                lastVideoUs = normalized
                diagnostics = diagnostics.copy(videoSamplesWritten = diagnostics.videoSamplesWritten + 1, durationUs = maxOf(diagnostics.durationUs, normalized))
            } else {
                lastAudioUs = normalized
                diagnostics = diagnostics.copy(audioSamplesWritten = diagnostics.audioSamplesWritten + 1, durationUs = maxOf(diagnostics.durationUs, normalized))
            }
        }
    }

    private fun finalizeMuxer() {
        val stateBefore = diagnostics().state
        val partial = synchronized(lock) { partialFile }
        val output = synchronized(lock) { outputFile }
        val activeMuxer = muxer
        val stopResult = runCatching { requireNotNull(activeMuxer) { "MediaMuxer is unavailable" }.stop() }
        val releaseResult = runCatching { activeMuxer?.release() }
        muxer = null
        if (stopResult.isFailure) {
            fail("MediaMuxer stop failed: ${stopResult.exceptionOrNull()?.message}")
            return
        }
        if (releaseResult.isFailure) {
            fail("MediaMuxer release failed: ${releaseResult.exceptionOrNull()?.message}")
            return
        }
        if (stateBefore == GoProRecordingState.ERROR) {
            publish()
            return
        }
        if (partial == null || output == null || !partial.exists() || partial.length() == 0L) {
            if (stateBefore != GoProRecordingState.INTERRUPTED) fail("Finalized MP4 is missing or empty")
            publish()
            return
        }
        if (!partial.renameTo(output)) {
            fail("Could not finalize MP4 file")
            return
        }
        val validation = runCatching { validator(output) }
        if (validation.isFailure) {
            fail("Finalized MP4 validation failed: ${validation.exceptionOrNull()?.message}")
            return
        }
        val size = output.length()
        val sha = sha256(output)
        synchronized(lock) {
            diagnostics = diagnostics.copy(
                fileSizeBytes = size,
                sha256 = sha,
                detail = "MP4 finalized and validated; writing metadata.",
            )
        }
        runCatching { writeMetadata(output) }.onFailure {
            fail("Finalized MP4 metadata write failed: ${it.message}")
            return
        }
        synchronized(lock) {
            diagnostics = diagnostics.copy(
                state = if (stateBefore == GoProRecordingState.INTERRUPTED) GoProRecordingState.INTERRUPTED else GoProRecordingState.SAVED,
                detail = "MP4 finalized and validated.",
            )
        }
        publish()
    }

    private fun fail(detail: String) {
        synchronized(lock) {
            accepting = false
            stopRequested = true
            diagnostics = diagnostics.copy(state = GoProRecordingState.ERROR, terminationReason = "recorder_error", detail = detail)
        }
        publish()
    }

    private fun publish() = diagnosticsListener(diagnostics())

    private fun writeMetadata(output: File) {
        val metadata = synchronized(lock) { metadataFile } ?: return
        val snapshot = diagnostics()
        val video = synchronized(lock) { videoFormat }
        val audio = synchronized(lock) { audioFormat }
        val audioConfig = audio?.let { runCatching { AacAudioSpecificConfig.parse(it.extradata) }.getOrNull() }
        val started = startedAt?.toString().orEmpty()
        FileWriter(metadata, false).use { writer ->
            writer.write(
                """{"recording_id":"${snapshot.recordingId}","publisher_generation_id":${snapshot.generationId},"source":"GOPRO_RTMP","stream_path":"gopro","started_at":"$started","ended_at":"${Instant.now()}","termination_reason":"${snapshot.terminationReason}","file_name":"${output.name}","file_size_bytes":${snapshot.fileSizeBytes},"duration_us":${snapshot.durationUs},"sha256":"${snapshot.sha256}","video":{"codec":"${video?.codecName}","width":${video?.width},"height":${video?.height},"samples":${snapshot.videoSamplesWritten}},"audio":{"codec":"${audio?.codecName}","reported_sample_rate":${audio?.sampleRate},"reported_channels":${audio?.channelCount},"audio_specific_config_sample_rate":${audioConfig?.sampleRate},"audio_specific_config_channels":${audioConfig?.channelCount},"samples":${snapshot.audioSamplesWritten}},"recording_queue_peak":${snapshot.peakQueueDepth}}""",
            )
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object { const val DEFAULT_CAPACITY = 256 }
}

private fun validateGoProMp4(file: File) {
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(file.absolutePath)
        var video = false
        var audio = false
        var durationUs = 0L
        repeat(extractor.trackCount) { index ->
            val format = extractor.getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME)
            video = video || mime == MediaFormat.MIMETYPE_VIDEO_AVC
            audio = audio || mime == MediaFormat.MIMETYPE_AUDIO_AAC
            if (format.containsKey(MediaFormat.KEY_DURATION)) durationUs = maxOf(durationUs, format.getLong(MediaFormat.KEY_DURATION))
        }
        require(video) { "MP4 has no H.264 video track" }
        require(audio) { "MP4 has no AAC audio track" }
        require(durationUs > 0) { "MP4 duration is not positive" }
    } finally { extractor.release() }
}
