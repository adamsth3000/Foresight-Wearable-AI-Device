package com.foresight.gateway.gopro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

class GoProRtmpIngressTest {
    @Test
    fun `start is idempotent and enters listening`() {
        val fixture = Fixture()

        fixture.ingress.start()
        fixture.ingress.start()

        assertEquals(1, fixture.backend.runCount)
        assertEquals(GoProSourceStatus.LISTENING, fixture.snapshots.last().status)
        assertEquals("rtmp://192.168.1.175:1935/gopro", fixture.snapshots.last().destination)
    }

    @Test
    fun `metadata promotes publisher to live`() {
        val fixture = Fixture()
        fixture.ingress.start()

        fixture.backend.emit(
            NativeIngressEvent.PUBLISHER_CONNECTED,
            "publisher connected",
        )
        fixture.backend.emit(
            NativeIngressEvent.STREAM_METADATA,
            "metadata",
            GoProStreamMetadata("h264", 1920, 1080, 60f, "aac", 48_000, 2),
        )

        assertEquals(GoProSourceStatus.LIVE, fixture.snapshots.last().status)
        assertEquals("h264 1920x1080 60.0fps", fixture.snapshots.last().metadata?.videoSummary())
        assertEquals("aac 48000Hz stereo", fixture.snapshots.last().metadata?.audioSummary())
    }

    @Test
    fun `disconnect then listener event supports reconnect without restart`() {
        val fixture = Fixture()
        fixture.ingress.start()
        fixture.backend.emit(NativeIngressEvent.PUBLISHER_DISCONNECTED, "publisher lost")
        fixture.backend.emit(NativeIngressEvent.LISTENING, "listening again")

        assertEquals(GoProSourceStatus.LISTENING, fixture.snapshots.last().status)
        assertEquals(1, fixture.backend.runCount)
    }

    @Test
    fun `media diagnostics update snapshot without changing live lifecycle`() {
        val fixture = Fixture()
        fixture.ingress.start()
        fixture.backend.emit(NativeIngressEvent.STREAM_METADATA, "metadata", GoProStreamMetadata(videoCodec = "h264"))

        fixture.backend.emitDiagnostics(diagnostics(generationId = 1, videoPackets = 842, audioPackets = 1_294))

        val snapshot = fixture.snapshots.last()
        assertEquals(GoProSourceStatus.LIVE, snapshot.status)
        assertEquals(842L, snapshot.mediaDiagnostics?.videoPacketCount)
        assertEquals(1_294L, snapshot.mediaDiagnostics?.audioPacketCount)
        assertEquals(18L, snapshot.mediaDiagnostics?.videoKeyframeCount)
        assertEquals(28_433_000L, snapshot.mediaDiagnostics?.lastVideoPtsUs)
        assertEquals(null, snapshot.mediaDiagnostics?.lastAudioDtsUs)
    }

    @Test
    fun `encoded payload callbacks do not alter live lifecycle`() {
        val fixture = Fixture()
        fixture.ingress.start()
        fixture.backend.emit(NativeIngressEvent.STREAM_METADATA, "metadata", GoProStreamMetadata(videoCodec = "h264"))

        fixture.backend.emitVideoFormat(
            GoProH264Format(
                generationId = 1,
                streamIndex = 0,
                width = 1280,
                height = 720,
                timeBaseNumerator = 1,
                timeBaseDenominator = 1_000,
                extradata = byteArrayOf(1, 2),
                representation = GoProH264Representation.AVCC,
                nalLengthSize = 4,
                codecName = "h264",
            ),
        )
        fixture.backend.emitSample(
            GoProEncodedSample(1, GoProStreamType.VIDEO, 0, byteArrayOf(1, 2, 3), 10, 10, true),
        )

        assertEquals(GoProSourceStatus.LIVE, fixture.snapshots.last().status)
        assertEquals(GoProH264Representation.AVCC, fixture.snapshots.last().encodedTransportDiagnostics?.videoRepresentation)
    }

    @Test
    fun `new publisher generation replaces rather than inherits diagnostics`() {
        val fixture = Fixture()
        fixture.ingress.start()
        fixture.backend.emit(NativeIngressEvent.STREAM_METADATA, "metadata", GoProStreamMetadata(videoCodec = "h264"))
        fixture.backend.emitDiagnostics(diagnostics(generationId = 1, videoPackets = 842, audioPackets = 1_294))

        fixture.backend.emit(NativeIngressEvent.PUBLISHER_DISCONNECTED, "publisher lost")
        fixture.backend.emit(NativeIngressEvent.PUBLISHER_CONNECTED, "publisher reconnected")
        assertEquals(null, fixture.snapshots.last().mediaDiagnostics)

        fixture.backend.emitDiagnostics(diagnostics(generationId = 2, videoPackets = 0, audioPackets = 0))
        fixture.backend.emitDiagnostics(diagnostics(generationId = 1, videoPackets = 999, audioPackets = 999))

        val diagnostics = fixture.snapshots.last().mediaDiagnostics
        assertEquals(GoProSourceStatus.PUBLISHER_CONNECTED, fixture.snapshots.last().status)
        assertEquals(2L, diagnostics?.generationId)
        assertEquals(0L, diagnostics?.videoPacketCount)
        assertEquals(0L, diagnostics?.audioPacketCount)
        assertTrue(diagnostics!!.videoConfigReady)
        assertTrue(diagnostics.audioConfigReady)
        assertEquals(0, diagnostics.lastVideoPacketBytes)
        assertEquals(null, diagnostics.lastVideoPtsUs)
    }

    @Test
    fun `stop is idempotent and suppresses late callbacks`() {
        val fixture = Fixture()
        fixture.ingress.start()
        fixture.ingress.stop()
        fixture.ingress.stop()
        fixture.backend.emit(NativeIngressEvent.STREAM_METADATA, "late", GoProStreamMetadata(videoCodec = "h264"))

        assertEquals(1, fixture.backend.stopCount)
        assertEquals(GoProSourceStatus.STOPPED, fixture.snapshots.last().status)
    }

    @Test
    fun `missing usable address reports error without running native backend`() {
        val snapshots = mutableListOf<GoProIngressSnapshot>()
        var backend: FakeBackend? = null
        val ingress = GoProRtmpIngress(
            listener = object : GoProRtmpIngress.Listener {
                override fun onGoProIngressChanged(snapshot: GoProIngressSnapshot) {
                    snapshots += snapshot
                }
            },
            addressProvider = { null },
            executor = Executor { it.run() },
            backendFactory = { callbacks ->
                FakeBackend(callbacks).also { backend = it }
            },
        )

        ingress.start()

        assertEquals(GoProSourceStatus.ERROR, snapshots.last().status)
        assertTrue(snapshots.last().detail!!.contains("No usable LAN"))
        assertEquals(null, backend)
    }

    private class Fixture {
        val snapshots = mutableListOf<GoProIngressSnapshot>()
        lateinit var backend: FakeBackend
        val ingress = GoProRtmpIngress(
            listener = object : GoProRtmpIngress.Listener {
                override fun onGoProIngressChanged(snapshot: GoProIngressSnapshot) {
                    snapshots += snapshot
                }
            },
            addressProvider = { "192.168.1.175" },
            executor = Executor { it.run() },
            backendFactory = { callbacks ->
                FakeBackend(callbacks).also { backend = it }
            },
        )
    }

    private class FakeBackend(
        private val callbacks: GoProIngressCallbacks,
    ) : GoProIngressBackend {
        var runCount = 0
        var stopCount = 0

        override fun run(host: String, port: Int, path: String) {
            runCount += 1
            callbacks.eventListener(NativeIngressEvent.LISTENING, "native listening", null)
        }

        override fun stop() {
            stopCount += 1
        }

        fun emit(event: NativeIngressEvent, detail: String, metadata: GoProStreamMetadata? = null) {
            callbacks.eventListener(event, detail, metadata)
        }

        fun emitDiagnostics(diagnostics: GoProMediaDiagnostics) = callbacks.mediaDiagnosticsListener(diagnostics)

        fun emitVideoFormat(format: GoProH264Format) = callbacks.videoFormatListener(format)

        fun emitSample(sample: GoProEncodedSample) = callbacks.sampleListener(sample)
    }

    private fun diagnostics(
        generationId: Long,
        videoPackets: Long,
        audioPackets: Long,
    ) = GoProMediaDiagnostics(
        generationId = generationId,
        videoConfigReady = true,
        videoExtradataBytes = 45,
        videoStreamIndex = 0,
        videoTimeBaseNumerator = 1,
        videoTimeBaseDenominator = 1_000,
        videoWidth = 1280,
        videoHeight = 720,
        audioConfigReady = true,
        audioExtradataBytes = 2,
        audioStreamIndex = 1,
        audioTimeBaseNumerator = 1,
        audioTimeBaseDenominator = 44_100,
        audioSampleRate = 44_100,
        audioChannelCount = 2,
        videoPacketCount = videoPackets,
        audioPacketCount = audioPackets,
        videoKeyframeCount = 18,
        lastVideoPtsUs = if (videoPackets == 0L) null else 28_433_000L,
        lastVideoDtsUs = if (videoPackets == 0L) null else 28_400_000L,
        lastAudioPtsUs = if (audioPackets == 0L) null else 28_412_000L,
        lastAudioDtsUs = null,
        lastVideoPacketBytes = if (videoPackets == 0L) 0 else 18_322,
        lastAudioPacketBytes = if (audioPackets == 0L) 0 else 371,
    )
}
