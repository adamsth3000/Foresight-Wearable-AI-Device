package com.foresight.gateway.capture

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Background, stream-copy event remuxer. It never mutates the continuous source recording. */
internal class LocalEventMediaExtractor(
    context: Context,
    private val repository: LocalRecordingMetadataRepository,
) {
    private val applicationContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()

    fun enqueueReadyEventsForRecording(recordingId: String) {
        repository.readyEventIdsForRecording(recordingId).forEach(::enqueue)
    }

    fun enqueueRecoverableEvents() {
        repository.snapshot().recordings.values.filter { it.finalized && !it.interrupted }.forEach { recording ->
            enqueueReadyEventsForRecording(recording.recordingId)
        }
    }

    fun enqueue(eventId: String) {
        executor.execute { extract(eventId) }
    }

    private fun extract(eventId: String) {
        when (val decision = repository.beginEventMediaExtraction(eventId)) {
            is EventMediaExtractionDecision.ExistingReady -> verifyExistingReady(decision.metadata)
            is EventMediaExtractionDecision.Rejected -> Log.w(TAG, "Event-media extraction skipped: eventId=$eventId; ${decision.reason}")
            is EventMediaExtractionDecision.Extract -> remux(decision.plan)
        }
    }

    private fun verifyExistingReady(metadata: EventMediaMetadata) {
        val output = eventMediaFile(metadata.outputFileName)
        val valid = output.isFile && output.length() == metadata.outputByteSize &&
            sha256(output) == metadata.outputSha256
        if (valid) {
            Log.i(TAG, "Event media already READY and verified: eventId=${metadata.eventId}")
        } else {
            repository.markEventMediaConflict(
                metadata.eventId,
                "READY event media is missing or does not match persisted output metadata",
            )
            Log.w(TAG, "Event media conflict requires explicit retry: eventId=${metadata.eventId}")
        }
    }

    private fun remux(plan: EventMediaExtractionPlan) {
        val partial = eventMediaFile("${plan.eventId}.partial.mp4")
        val final = eventMediaFile(plan.outputFileName)
        try {
            require(!final.exists()) { "event-media output already exists; refusing to overwrite it" }
            if (partial.exists() && !partial.delete()) error("unable to remove stale partial event media")
            require(partial.parentFile?.exists() == true || requireNotNull(partial.parentFile).mkdirs()) {
                "unable to create private event-media directory"
            }
            val source = File(File(applicationContext.filesDir, "recordings"), plan.recording.localMediaFileName)
            require(source.isFile) { "finalized source recording file is missing" }
            val result = copySamples(source, partial, plan)
            require(partial.isFile && partial.length() > 0L) { "remux produced no event media" }
            verifyReadableOutput(partial, result.videoPresent, result.audioPresent)
            promote(partial, final)
            val outputSize = final.length()
            require(outputSize > 0L) { "promoted event media is empty" }
            val ready = repository.completeEventMediaExtraction(
                plan = plan,
                actualStartOffsetMillis = result.actualStartOffsetMillis,
                actualEndOffsetMillis = result.actualEndOffsetMillis,
                outputByteSize = outputSize,
                outputSha256 = sha256(final),
                videoPresent = result.videoPresent,
                audioPresent = result.audioPresent,
            )
            Log.i(
                TAG,
                "Event media READY: eventId=${plan.eventId} output=${final.name} " +
                    "syncState=${ready.syncState}",
            )
        } catch (error: Exception) {
            if (partial.exists() && !partial.delete()) {
                Log.w(TAG, "Unable to remove invalid partial event media: ${partial.name}")
            }
            repository.failEventMediaExtraction(plan.eventId, error.message ?: error.javaClass.simpleName)
            Log.e(TAG, "Event-media extraction failed: eventId=${plan.eventId}", error)
        }
    }

    private fun copySamples(source: File, output: File, plan: EventMediaExtractionPlan): RemuxResult {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            extractor.setDataSource(source.absolutePath)
            val tracks = selectTracks(extractor)
            requireNotNull(tracks.videoTrackIndex) { "source MP4 has no video track" }
            val durationUs = tracks.durationUs
            val requestedStartUs = plan.requestedStartOffsetMillis * MICROS_PER_MILLISECOND
            val requestedEndUs = plan.requestedEndOffsetMillis * MICROS_PER_MILLISECOND
            require(requestedEndUs <= durationUs + DURATION_TOLERANCE_US) {
                "event interval exceeds source recording duration"
            }
            val boundedEndUs = minOf(requestedEndUs, durationUs)
            extractor.selectTrack(tracks.videoTrackIndex)
            tracks.audioTrackIndex?.let(extractor::selectTrack)
            extractor.seekTo(requestedStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outputTracks = buildMap {
                put(tracks.videoTrackIndex, muxer.addTrack(requireNotNull(tracks.videoFormat)))
                tracks.audioTrackIndex?.let { put(it, muxer.addTrack(requireNotNull(tracks.audioFormat))) }
            }
            val maxInputSize = tracks.formats.maxOf { format ->
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else DEFAULT_BUFFER_BYTES
            }.coerceAtLeast(DEFAULT_BUFFER_BYTES)
            val buffer = ByteBuffer.allocate(maxInputSize)
            val info = MediaCodec.BufferInfo()
            muxer.start()
            muxerStarted = true

            var actualStartUs: Long? = null
            var actualEndUs = -1L
            var wroteVideo = 0
            var wroteAudio = 0
            while (true) {
                val inputTrack = extractor.sampleTrackIndex
                if (inputTrack < 0) break
                val sampleTimeUs = extractor.sampleTime
                if (actualStartUs == null) {
                    val isVideoSync = inputTrack == tracks.videoTrackIndex &&
                        extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                    if (!isVideoSync) {
                        extractor.advance()
                        continue
                    }
                    actualStartUs = sampleTimeUs
                }
                if (sampleTimeUs > boundedEndUs) break
                val outputTrack = outputTracks[inputTrack]
                if (outputTrack != null && sampleTimeUs >= actualStartUs) {
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    info.set(0, size, (sampleTimeUs - actualStartUs).coerceAtLeast(0L), extractor.sampleFlags)
                    muxer.writeSampleData(outputTrack, buffer, info)
                    actualEndUs = maxOf(actualEndUs, sampleTimeUs)
                    if (inputTrack == tracks.videoTrackIndex) wroteVideo++ else wroteAudio++
                }
                extractor.advance()
            }
            require(actualStartUs != null && wroteVideo > 0) { "no usable video sync sample was found" }
            if (tracks.audioTrackIndex != null) require(wroteAudio > 0) { "source audio track produced no event samples" }
            require(actualEndUs >= actualStartUs) { "event remux wrote no samples" }
            return RemuxResult(
                actualStartOffsetMillis = actualStartUs / MICROS_PER_MILLISECOND,
                actualEndOffsetMillis = actualEndUs / MICROS_PER_MILLISECOND,
                videoPresent = true,
                audioPresent = tracks.audioTrackIndex != null,
            )
        } finally {
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            extractor.release()
        }
    }

    private fun selectTracks(extractor: MediaExtractor): SourceTracks {
        var videoIndex: Int? = null
        var audioIndex: Int? = null
        var videoFormat: MediaFormat? = null
        var audioFormat: MediaFormat? = null
        val formats = mutableListOf<MediaFormat>()
        var durationUs = 0L
        repeat(extractor.trackCount) { index ->
            val format = extractor.getTrackFormat(index)
            formats += format
            if (format.containsKey(MediaFormat.KEY_DURATION)) durationUs = maxOf(durationUs, format.getLong(MediaFormat.KEY_DURATION))
            when {
                format.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/") && videoIndex == null -> {
                    videoIndex = index
                    videoFormat = format
                }
                format.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/") && audioIndex == null -> {
                    audioIndex = index
                    audioFormat = format
                }
            }
        }
        require(durationUs > 0L) { "source MP4 has no usable duration" }
        return SourceTracks(videoIndex, audioIndex, videoFormat, audioFormat, formats, durationUs)
    }

    private fun verifyReadableOutput(file: File, expectVideo: Boolean, expectAudio: Boolean) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val mimes = (0 until extractor.trackCount).map { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
            }
            require(!expectVideo || mimes.any { it.startsWith("video/") }) { "event MP4 is missing video" }
            require(!expectAudio || mimes.any { it.startsWith("audio/") }) { "event MP4 is missing audio" }
        } finally {
            extractor.release()
        }
    }

    private fun eventMediaFile(fileName: String): File {
        require(fileName == File(fileName).name && fileName.endsWith(".mp4")) {
            "event media must use a private MP4 filename"
        }
        return File(File(applicationContext.filesDir, "event_media"), fileName)
    }

    private fun promote(partial: File, final: File) {
        try {
            Files.move(partial.toPath(), final.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(partial.toPath(), final.toPath())
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(HASH_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private data class SourceTracks(
        val videoTrackIndex: Int?,
        val audioTrackIndex: Int?,
        val videoFormat: MediaFormat?,
        val audioFormat: MediaFormat?,
        val formats: List<MediaFormat>,
        val durationUs: Long,
    )

    private data class RemuxResult(
        val actualStartOffsetMillis: Long,
        val actualEndOffsetMillis: Long,
        val videoPresent: Boolean,
        val audioPresent: Boolean,
    )

    private companion object {
        const val TAG = "LocalEventMediaExtractor"
        const val MICROS_PER_MILLISECOND = 1_000L
        const val DURATION_TOLERANCE_US = 2_000_000L
        const val DEFAULT_BUFFER_BYTES = 1_024 * 1_024
        const val HASH_BUFFER_BYTES = 64 * 1_024
    }
}
