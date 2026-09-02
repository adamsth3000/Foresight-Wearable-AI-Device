package com.foresight.gateway.gopro

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

class GoProH264PreviewTest {
    @Test
    fun `avcC parser extracts nal length size SPS and PPS`() {
        val config = AvcDecoderConfiguration.parse(validAvcC())

        assertEquals(4, config.nalLengthSize)
        assertArrayEquals(byteArrayOf(0x67.toByte(), 0x64.toByte(), 0, 0x1f), config.sps.single())
        assertArrayEquals(byteArrayOf(0x68.toByte(), 0xce.toByte()), config.pps.single())
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x67.toByte(), 0x64.toByte(), 0, 0x1f), config.csd0())
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x68.toByte(), 0xce.toByte()), config.csd1())
    }

    @Test
    fun `avcC parser rejects malformed parameter set lengths`() {
        assertMalformed { AvcDecoderConfiguration.parse(validAvcC().copyOf(9)) }
    }

    @Test
    fun `AVCC conversion handles multiple NAL units without mutating input`() {
        val source = byteArrayOf(0, 0, 0, 2, 0x65, 1, 0, 0, 0, 3, 0x41, 2, 3)
        val original = source.copyOf()

        assertArrayEquals(
            byteArrayOf(0, 0, 0, 1, 0x65, 1, 0, 0, 0, 1, 0x41, 2, 3),
            AvccAccessUnit.toAnnexB(source, 4),
        )
        assertArrayEquals(original, source)
        assertMalformed { AvccAccessUnit.toAnnexB(byteArrayOf(0, 0, 0, 5, 1), 4) }
    }

    @Test
    fun `preview waits for a keyframe then configures and decodes`() {
        val decoder = FakeDecoder()
        val controller = GoProH264PreviewController(
            decoderFactory = GoProAvcDecoderFactory { decoder },
            executor = Executor { it.run() },
        )

        controller.attachPreviewSurface("surface")
        controller.acceptVideoFormat(videoFormat())
        controller.acceptVideoSample(videoSample(keyFrame = false))
        assertEquals(GoProPreviewState.WAITING_FOR_KEYFRAME, controller.diagnostics().state)
        assertEquals(0, decoder.configureCalls)

        controller.acceptVideoSample(videoSample(keyFrame = true))
        assertEquals(GoProPreviewState.DECODING, controller.diagnostics().state)
        assertEquals(1, decoder.configureCalls)
        assertEquals(1, decoder.queued.size)
        assertEquals(1L, controller.diagnostics().framesQueued)
        assertEquals(1L, controller.diagnostics().framesRendered)
    }

    @Test
    fun `surface loss releases decoder and generation change requires fresh keyframe`() {
        val decoder = FakeDecoder()
        val controller = GoProH264PreviewController(
            decoderFactory = GoProAvcDecoderFactory { decoder },
            executor = Executor { it.run() },
        )
        controller.attachPreviewSurface("first")
        controller.acceptVideoFormat(videoFormat(generation = 1))
        controller.acceptVideoSample(videoSample(generation = 1, keyFrame = true))
        controller.detachPreviewSurface("first")
        assertEquals(GoProPreviewState.DETACHED, controller.diagnostics().state)
        assertTrue(decoder.releaseCalls >= 1)

        controller.attachPreviewSurface("second")
        controller.acceptVideoFormat(videoFormat(generation = 2))
        controller.acceptVideoSample(videoSample(generation = 2, keyFrame = false))
        assertEquals(GoProPreviewState.WAITING_FOR_KEYFRAME, controller.diagnostics().state)
        controller.acceptVideoSample(videoSample(generation = 2, keyFrame = true))
        assertEquals(GoProPreviewState.DECODING, controller.diagnostics().state)
        assertEquals(2, decoder.configureCalls)
    }

    @Test
    fun `publisher boundary releases decoder and waits for fresh configuration`() {
        val decoder = FakeDecoder()
        val controller = GoProH264PreviewController(
            decoderFactory = GoProAvcDecoderFactory { decoder },
            executor = Executor { it.run() },
        )
        controller.attachPreviewSurface("surface")
        controller.acceptVideoFormat(videoFormat())
        controller.acceptVideoSample(videoSample())

        controller.resetForPublisherBoundary("publisher disconnected")

        assertEquals(GoProPreviewState.WAITING_FOR_STREAM, controller.diagnostics().state)
        assertTrue(decoder.releaseCalls >= 1)
    }

    @Test
    fun `preview queue is bounded and drops separately from B1 transport`() {
        val executor = DeferredExecutor()
        val controller = GoProH264PreviewController(
            decoderFactory = GoProAvcDecoderFactory { FakeDecoder() },
            executor = executor,
            capacity = 2,
        )
        controller.attachPreviewSurface("surface")
        controller.acceptVideoFormat(videoFormat())
        executor.runAll()
        controller.acceptVideoSample(videoSample(data = accessUnit(1)))
        controller.acceptVideoSample(videoSample(data = accessUnit(2)))
        controller.acceptVideoSample(videoSample(data = accessUnit(3)))

        assertEquals(2, controller.diagnostics().queueDepth)
        assertEquals(1L, controller.diagnostics().framesDropped)
        executor.runAll()
    }

    private fun validAvcC() = byteArrayOf(
        1, 0x64, 0, 0x1f, 0xff.toByte(), 0xe1.toByte(),
        0, 4, 0x67, 0x64, 0, 0x1f,
        1, 0, 2, 0x68, 0xce.toByte(),
    )

    private fun videoFormat(generation: Long = 1) = GoProH264Format(
        generationId = generation,
        streamIndex = 0,
        width = 1280,
        height = 720,
        timeBaseNumerator = 1,
        timeBaseDenominator = 1_000_000,
        extradata = validAvcC(),
        representation = GoProH264Representation.AVCC,
        nalLengthSize = 4,
        codecName = "h264",
    )

    private fun videoSample(
        generation: Long = 1,
        keyFrame: Boolean = true,
        data: ByteArray = accessUnit(0x65),
    ) = GoProEncodedSample(
        generationId = generation,
        streamType = GoProStreamType.VIDEO,
        streamIndex = 0,
        data = data,
        presentationTimeUs = 123_000,
        decodingTimeUs = 120_000,
        keyFrame = keyFrame,
        videoRepresentation = GoProH264Representation.AVCC,
    )

    private fun accessUnit(value: Int) = byteArrayOf(0, 0, 0, 2, 0x65, value.toByte())

    private class FakeDecoder : GoProAvcDecoder {
        var configureCalls = 0
        var releaseCalls = 0
        val queued = mutableListOf<ByteArray>()

        override fun configure(format: GoProH264Format, config: AvcDecoderConfiguration, outputSurface: Any): String {
            configureCalls += 1
            return "fake.avc.decoder"
        }

        override fun queueAccessUnit(data: ByteArray, presentationTimeUs: Long): Boolean {
            queued += data.copyOf()
            return true
        }

        override fun drainOutput(): Int = 1

        override fun release() {
            releaseCalls += 1
        }
    }

    private class DeferredExecutor : Executor {
        private val commands = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            commands.addLast(command)
        }

        fun runAll() {
            while (commands.isNotEmpty()) commands.removeFirst().run()
        }
    }

    private fun assertMalformed(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("Expected malformed AVC input to be rejected")
    }
}
