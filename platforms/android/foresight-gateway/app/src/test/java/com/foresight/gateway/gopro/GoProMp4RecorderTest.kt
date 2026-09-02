package com.foresight.gateway.gopro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executor

class GoProMp4RecorderTest {
    @Test
    fun `muxer formats use parsed AVC config and AAC AudioSpecificConfig`() {
        val video = GoProMuxerFormats.video(videoFormat())
        val audio = GoProMuxerFormats.audio(audioFormat())

        assertEquals("video/avc", video.mime)
        assertEquals("audio/mp4a-latm", audio.mime)
        assertEquals(2, audio.csd0.size)
    }

    @Test
    fun `recorder waits for keyframe drops early audio and normalizes timestamps`() {
        val fixture = Fixture()
        fixture.recorder.start(videoFormat(), audioFormat())
        fixture.recorder.acceptSample(audioSample(900))
        fixture.recorder.acceptSample(videoSample(1_000, keyFrame = false))
        fixture.recorder.acceptSample(videoSample(1_000, keyFrame = true))
        fixture.recorder.acceptSample(audioSample(1_100))

        assertEquals(GoProRecordingState.RECORDING, fixture.recorder.diagnostics().state)
        assertEquals(listOf(0L), fixture.muxer.videoPts)
        assertEquals(listOf(100L), fixture.muxer.audioPts)
        assertEquals(byteArrayOf(0, 0, 0, 1, 0x65, 1).toList(), fixture.muxer.videoPayloads.single().toList())
        assertEquals(1L, fixture.recorder.diagnostics().videoSamplesWritten)
        assertEquals(1L, fixture.recorder.diagnostics().audioSamplesWritten)
    }

    @Test
    fun `manual stop saves only after muxer finalization`() {
        val fixture = Fixture()
        fixture.recorder.start(videoFormat(), audioFormat())
        fixture.recorder.acceptSample(videoSample(1_000, keyFrame = true))
        fixture.recorder.acceptSample(audioSample(1_050))
        fixture.recorder.stop()

        val diagnostics = fixture.recorder.diagnostics()
        assertEquals(GoProRecordingState.SAVED, diagnostics.state)
        assertTrue(fixture.muxer.stopped)
        assertTrue(fixture.muxer.released)
        assertTrue(File(fixture.directory, diagnostics.outputFileName!!).isFile)
        assertTrue(File(fixture.directory, diagnostics.metadataFileName!!).isFile)
        assertEquals("manual_stop", diagnostics.terminationReason)
    }

    @Test
    fun `overflow transitions to explicit error rather than saved`() {
        val executor = DeferredExecutor()
        val directory = Files.createTempDirectory("gopro-recorder-test").toFile()
        val recorder = GoProMp4Recorder(
            outputDirectory = directory,
            muxerFactory = GoProMp4MuxerFactory { FakeMuxer(it) },
            validator = {},
            executor = executor,
            capacity = 2,
        )
        recorder.start(videoFormat(), audioFormat())
        recorder.acceptSample(videoSample(1_000, true))
        recorder.acceptSample(audioSample(1_010))
        recorder.acceptSample(audioSample(1_020))
        executor.runAll()

        assertEquals(GoProRecordingState.ERROR, recorder.diagnostics().state)
        assertFalse(recorder.diagnostics().detail!!.contains("validated"))
    }

    @Test
    fun `publisher boundary interrupts one generation without accepting the next`() {
        val fixture = Fixture()
        fixture.recorder.start(videoFormat(generation = 1), audioFormat(generation = 1))
        fixture.recorder.acceptSample(videoSample(1_000, true, generation = 1))
        fixture.recorder.onPublisherBoundary("publisher disconnected")
        fixture.recorder.acceptSample(videoSample(2_000, true, generation = 2))

        assertEquals(GoProRecordingState.INTERRUPTED, fixture.recorder.diagnostics().state)
        assertEquals(listOf(0L), fixture.muxer.videoPts)
    }

    @Test
    fun `recorder conversion honors avcC NAL length size and preserves source payload`() {
        val source = byteArrayOf(0, 2, 0x65, 1, 0, 3, 0x41, 2, 3)
        val original = source.copyOf()
        val fixture = Fixture()
        fixture.recorder.start(videoFormat(nalLengthSize = 2), audioFormat())
        fixture.recorder.acceptSample(videoSample(1_000, true, data = source))

        assertEquals(byteArrayOf(0, 0, 0, 1, 0x65, 1, 0, 0, 0, 1, 0x41, 2, 3).toList(), fixture.muxer.videoPayloads.single().toList())
        assertEquals(original.toList(), source.toList())
    }

    @Test
    fun `AAC AudioSpecificConfig supplies the muxer sample rate`() {
        assertEquals(48_000, AacAudioSpecificConfig.parse(byteArrayOf(0x11, 0x90.toByte())).sampleRate)
        assertEquals(44_100, AacAudioSpecificConfig.parse(byteArrayOf(0x12, 0x10)).sampleRate)
        assertEquals(48_000, GoProMuxerFormats.audio(audioFormat(extradata = byteArrayOf(0x11, 0x90.toByte()))).sampleRate)
    }

    @Test
    fun `finalized nonempty file with successful validation remains saved`() {
        val fixture = Fixture()
        fixture.recorder.start(videoFormat(), audioFormat())
        fixture.recorder.acceptSample(videoSample(1_000, keyFrame = true))
        fixture.recorder.stop()

        assertEquals(GoProRecordingState.SAVED, fixture.recorder.diagnostics().state)
        assertTrue(File(fixture.directory, fixture.recorder.diagnostics().outputFileName!!).length() > 0)
    }

    @Test
    fun `missing finalized file remains an error`() {
        val recorder = recorderWith(initialFileBytes = null)
        recorder.start(videoFormat(), audioFormat())
        recorder.stop()

        assertEquals(GoProRecordingState.ERROR, recorder.diagnostics().state)
        assertTrue(recorder.diagnostics().detail!!.contains("missing or empty"))
    }

    @Test
    fun `zero byte finalized file remains an error`() {
        val recorder = recorderWith(initialFileBytes = byteArrayOf())
        recorder.start(videoFormat(), audioFormat())
        recorder.stop()

        assertEquals(GoProRecordingState.ERROR, recorder.diagnostics().state)
        assertTrue(recorder.diagnostics().detail!!.contains("missing or empty"))
    }

    @Test
    fun `invalid finalized media remains an error`() {
        val recorder = recorderWith(validator = { error("not playable") })
        recorder.start(videoFormat(), audioFormat())
        recorder.acceptSample(videoSample(1_000, keyFrame = true))
        recorder.stop()

        assertEquals(GoProRecordingState.ERROR, recorder.diagnostics().state)
        assertTrue(recorder.diagnostics().detail!!.contains("validation failed"))
    }

    private class Fixture {
        val directory = Files.createTempDirectory("gopro-recorder-test").toFile()
        lateinit var muxer: FakeMuxer
        val recorder = GoProMp4Recorder(
            outputDirectory = directory,
            muxerFactory = GoProMp4MuxerFactory { output -> FakeMuxer(output).also { muxer = it } },
            validator = {},
            executor = Executor { it.run() },
        )
    }

    private class FakeMuxer(private val output: File) : GoProMp4Muxer {
        var stopped = false
        var released = false
        val videoPts = mutableListOf<Long>()
        val audioPts = mutableListOf<Long>()
        val videoPayloads = mutableListOf<ByteArray>()
        private var tracks = 0
        override fun addTrack(format: GoProMuxerTrackFormat): Int = tracks++
        override fun start() { output.writeBytes(byteArrayOf(1)) }
        override fun writeSampleData(trackIndex: Int, data: ByteArray, presentationTimeUs: Long, flags: Int) {
            if (trackIndex == 0) {
                videoPts += presentationTimeUs
                videoPayloads += data.copyOf()
            } else audioPts += presentationTimeUs
        }
        override fun stop() { stopped = true }
        override fun release() { released = true }
    }

    private fun videoFormat(generation: Long = 1, nalLengthSize: Int = 4) = GoProH264Format(
        generation, 0, 1280, 720, 1, 1_000_000,
        byteArrayOf(1, 0x64, 0, 0x1f, (0xfc or (nalLengthSize - 1)).toByte(), 0xe1.toByte(), 0, 4, 0x67, 0x64, 0, 0x1f, 1, 0, 2, 0x68, 0xce.toByte()),
        GoProH264Representation.AVCC, nalLengthSize, "h264",
    )
    private fun audioFormat(generation: Long = 1, extradata: ByteArray = byteArrayOf(0x12, 0x10)) = GoProAacFormat(generation, 1, 44_100, 2, 1, 44_100, extradata, GoProAacRepresentation.RAW_AAC, "aac")
    private fun videoSample(pts: Long, keyFrame: Boolean, generation: Long = 1, data: ByteArray = byteArrayOf(0, 0, 0, 2, 0x65, 1)) = GoProEncodedSample(generation, GoProStreamType.VIDEO, 0, data, pts, pts, keyFrame, GoProH264Representation.AVCC)
    private fun audioSample(pts: Long) = GoProEncodedSample(1, GoProStreamType.AUDIO, 1, byteArrayOf(1, 2), pts, pts, false, audioRepresentation = GoProAacRepresentation.RAW_AAC)

    private fun recorderWith(
        initialFileBytes: ByteArray? = byteArrayOf(1),
        validator: (File) -> Unit = {},
    ): GoProMp4Recorder {
        val directory = Files.createTempDirectory("gopro-recorder-test").toFile()
        return GoProMp4Recorder(
            outputDirectory = directory,
            muxerFactory = GoProMp4MuxerFactory { output ->
                object : GoProMp4Muxer by FakeMuxer(output) {
                    override fun start() {
                        initialFileBytes?.let(output::writeBytes)
                    }
                }
            },
            validator = validator,
            executor = Executor { it.run() },
        )
    }

    private class DeferredExecutor : Executor {
        private val commands = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { commands.addLast(command) }
        fun runAll() { while (commands.isNotEmpty()) commands.removeFirst().run() }
    }
}
