package com.foresight.gateway.gopro

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

class GoProEncodedMediaTransportTest {
    @Test
    fun `transport copies payload before consumer runs`() {
        val executor = DeferredExecutor()
        val consumed = mutableListOf<GoProEncodedSample>()
        val transport = GoProEncodedMediaTransport(capacity = 2, consumerExecutor = executor, diagnosticConsumer = consumed::add)
        val payload = byteArrayOf(1, 2, 3)

        transport.acceptSample(videoSample(data = payload))
        payload[0] = 99
        executor.runAll()

        assertArrayEquals(byteArrayOf(1, 2, 3), consumed.single().data)
    }

    @Test
    fun `transport has fixed capacity and drops oldest sample`() {
        val executor = DeferredExecutor()
        val transport = GoProEncodedMediaTransport(capacity = 2, consumerExecutor = executor)

        transport.acceptSample(videoSample(data = byteArrayOf(1)))
        transport.acceptSample(audioSample(data = byteArrayOf(2)))
        transport.acceptSample(videoSample(data = byteArrayOf(3)))

        val diagnostics = transport.diagnostics()
        assertEquals(2, diagnostics.queueDepth)
        assertEquals(2, diagnostics.peakQueueDepth)
        assertEquals(1L, diagnostics.samplesDropped)
        assertEquals(1L, diagnostics.videoSamplesDropped)
        assertEquals(0L, diagnostics.audioSamplesDropped)
    }

    @Test
    fun `new generation clears queued samples and prior formats`() {
        val executor = DeferredExecutor()
        val transport = GoProEncodedMediaTransport(capacity = 2, consumerExecutor = executor)
        val extradata = byteArrayOf(1, 2, 3)
        transport.acceptVideoFormat(videoFormat(extradata = extradata))
        extradata[0] = 99
        transport.acceptSample(videoSample(generationId = 1, data = byteArrayOf(1)))

        assertArrayEquals(byteArrayOf(1, 2, 3), transport.videoFormat()!!.extradata)
        transport.acceptSample(videoSample(generationId = 2, data = byteArrayOf(2)))

        assertEquals(2L, transport.diagnostics().generationId)
        assertEquals(1, transport.diagnostics().queueDepth)
        assertNull(transport.videoFormat())
        assertNull(transport.audioFormat())
    }

    @Test
    fun `sample metadata preserves timestamps keyframe stream and representation`() {
        val executor = DeferredExecutor()
        val consumed = mutableListOf<GoProEncodedSample>()
        val transport = GoProEncodedMediaTransport(consumerExecutor = executor, diagnosticConsumer = consumed::add)

        transport.acceptSample(videoSample())
        transport.acceptSample(audioSample())
        executor.runAll()

        assertEquals(2, consumed.size)
        assertTrue(consumed[0].keyFrame)
        assertEquals(123L, consumed[0].presentationTimeUs)
        assertEquals(120L, consumed[0].decodingTimeUs)
        assertEquals(3, consumed[0].streamIndex)
        assertEquals(GoProH264Representation.AVCC, consumed[0].videoRepresentation)
        assertEquals(GoProAacRepresentation.RAW_AAC, consumed[1].audioRepresentation)
    }

    @Test
    fun `serialized transport drain fans video to preview without a second queue consumer`() {
        val executor = DeferredExecutor()
        val diagnostics = mutableListOf<GoProEncodedSample>()
        val preview = mutableListOf<GoProEncodedSample>()
        val transport = GoProEncodedMediaTransport(
            consumerExecutor = executor,
            diagnosticConsumer = diagnostics::add,
            videoPreviewConsumer = preview::add,
        )

        transport.acceptSample(videoSample())
        transport.acceptSample(audioSample())
        executor.runAll()

        assertEquals(2, diagnostics.size)
        assertEquals(1, preview.size)
        assertEquals(GoProStreamType.VIDEO, preview.single().streamType)
    }

    private fun videoFormat(extradata: ByteArray) = GoProH264Format(
        generationId = 1,
        streamIndex = 3,
        width = 1280,
        height = 720,
        timeBaseNumerator = 1,
        timeBaseDenominator = 1_000,
        extradata = extradata,
        representation = GoProH264Representation.AVCC,
        nalLengthSize = 4,
        codecName = "h264",
    )

    private fun videoSample(generationId: Long = 1, data: ByteArray = byteArrayOf(0, 0, 0, 1)) = GoProEncodedSample(
        generationId = generationId,
        streamType = GoProStreamType.VIDEO,
        streamIndex = 3,
        data = data,
        presentationTimeUs = 123,
        decodingTimeUs = 120,
        keyFrame = true,
        videoRepresentation = GoProH264Representation.AVCC,
    )

    private fun audioSample(data: ByteArray = byteArrayOf(0x11, 0x22)) = GoProEncodedSample(
        generationId = 1,
        streamType = GoProStreamType.AUDIO,
        streamIndex = 4,
        data = data,
        presentationTimeUs = 130,
        decodingTimeUs = 130,
        keyFrame = false,
        audioRepresentation = GoProAacRepresentation.RAW_AAC,
    )

    private class DeferredExecutor : Executor {
        private val commands = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            commands.addLast(command)
        }

        fun runAll() {
            while (commands.isNotEmpty()) commands.removeFirst().run()
        }
    }
}
