package com.foresight.gateway.capture

import com.foresight.gateway.metadata.CaptureSessionMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldSessionCoordinatorC2ePhoneTest {

    @Test
    fun `PHONE FIELD coordinator starts and ends media and supports exactly once`() {
        val media = CountingMediaController()
        val telemetry = CountingTelemetryController()
        val sensors = CountingSensorController()
        val coordinator = FieldSessionCoordinator(media, sensors, telemetry)
        val session = CaptureSessionMetadata(streamEndpoint = "")

        assertTrue(coordinator.start(LocalMediaSourceId.PHONE_CAMERA, session, "telemetry://endpoint"))
        assertEquals(LocalMediaSourceId.PHONE_CAMERA, coordinator.snapshot().source)
        assertEquals(LocalMediaAvailability.AVAILABLE, coordinator.snapshot().availability)
        assertEquals(1, telemetry.starts)
        assertEquals("telemetry://endpoint", telemetry.lastEndpoint)
        assertEquals(1, sensors.starts)
        assertEquals(1, media.starts)

        coordinator.end()

        assertFalse(coordinator.snapshot().active)
        assertEquals(1, media.stops)
        assertEquals(1, sensors.stops)
        assertEquals(1, telemetry.stops)
    }

    @Test
    fun `PHONE FIELD source lock rejects duplicate start and releases on end`() {
        val media = CountingMediaController()
        val telemetry = CountingTelemetryController()
        val sensors = CountingSensorController()
        val coordinator = FieldSessionCoordinator(media, sensors, telemetry)
        val session = CaptureSessionMetadata(streamEndpoint = "")

        assertTrue(coordinator.start(LocalMediaSourceId.PHONE_CAMERA, session, "telemetry://one"))
        assertFalse(coordinator.start(LocalMediaSourceId.PHONE_CAMERA, session, "telemetry://two"))

        assertEquals(1, media.starts)
        assertEquals(1, sensors.starts)
        assertEquals(1, telemetry.starts)

        coordinator.end()

        assertTrue(coordinator.start(LocalMediaSourceId.PHONE_CAMERA, session, "telemetry://three"))
        assertEquals(2, media.starts)
        assertEquals(2, sensors.starts)
        assertEquals(2, telemetry.starts)
    }

    @Test
    fun `PHONE FIELD media start failure rolls supports back once and clears lock`() {
        val media = CountingMediaController(failStart = true)
        val telemetry = CountingTelemetryController()
        val sensors = CountingSensorController()
        val coordinator = FieldSessionCoordinator(media, sensors, telemetry)
        val session = CaptureSessionMetadata(streamEndpoint = "")

        assertThrows(IllegalStateException::class.java) {
            coordinator.start(LocalMediaSourceId.PHONE_CAMERA, session, "telemetry://endpoint")
        }

        assertFalse(coordinator.snapshot().active)
        assertEquals(1, media.starts)
        assertEquals(0, media.stops)
        assertEquals(1, sensors.starts)
        assertEquals(1, sensors.stops)
        assertEquals(1, telemetry.starts)
        assertEquals(1, telemetry.stops)
    }

    @Test
    fun `PHONE FIELD asynchronous media end releases supports and later end is safe`() {
        val media = CountingMediaController()
        val telemetry = CountingTelemetryController()
        val sensors = CountingSensorController()
        val coordinator = FieldSessionCoordinator(media, sensors, telemetry)
        val session = CaptureSessionMetadata(streamEndpoint = "")

        assertTrue(coordinator.start(LocalMediaSourceId.PHONE_CAMERA, session, "telemetry://endpoint"))

        coordinator.onSourceEnded(LocalMediaSourceId.PHONE_CAMERA)

        assertFalse(coordinator.snapshot().active)
        assertEquals(0, media.stops)
        assertEquals(1, sensors.stops)
        assertEquals(1, telemetry.stops)

        coordinator.end()

        assertEquals(0, media.stops)
        assertEquals(1, sensors.stops)
        assertEquals(1, telemetry.stops)
    }

    @Test
    fun `PHONE adapter passes separate telemetry endpoint and external support ownership seam`() {
        var capturedEndpoint: String? = "not-called"
        var capturedTelemetryEndpoint: String? = null
        var capturedSession: CaptureSessionMetadata? = null
        var stops = 0
        val session = CaptureSessionMetadata(streamEndpoint = "")

        val adapter = PhoneFieldMediaAdapter(
            startAction = { endpoint, telemetryEndpoint, suppliedSession ->
                capturedEndpoint = endpoint
                capturedTelemetryEndpoint = telemetryEndpoint
                capturedSession = suppliedSession
            },
            stopAction = { stops += 1 },
            availabilityProvider = { LocalMediaAvailability.AVAILABLE },
            testOnly = Unit,
        )

        adapter.start(session, "telemetry://endpoint")
        adapter.stop()

        assertEquals(null, capturedEndpoint)
        assertEquals("telemetry://endpoint", capturedTelemetryEndpoint)
        assertEquals(session, capturedSession)
        assertEquals(LocalMediaAvailability.AVAILABLE, adapter.availability())
        assertEquals(1, stops)
    }

    private class CountingMediaController(
        private val failStart: Boolean = false,
    ) : FieldMediaController {
        override val source: LocalMediaSourceId = LocalMediaSourceId.PHONE_CAMERA
        var starts = 0
        var stops = 0
        private var available = false

        override fun start(session: CaptureSessionMetadata) {
            start(session, "")
        }

        override fun start(session: CaptureSessionMetadata, telemetryEndpoint: String) {
            starts += 1
            if (failStart) throw IllegalStateException("simulated media start failure")
            available = true
        }

        override fun stop() {
            stops += 1
            available = false
        }

        override fun onSourceLive() = Unit

        override fun availability(): LocalMediaAvailability =
            if (available) LocalMediaAvailability.AVAILABLE else LocalMediaAvailability.UNAVAILABLE
    }

    private class CountingTelemetryController : FieldTelemetryController {
        var starts = 0
        var stops = 0
        var lastEndpoint: String? = null

        override fun start(session: CaptureSessionMetadata, endpoint: String) {
            starts += 1
            lastEndpoint = endpoint
        }

        override fun stop() {
            stops += 1
        }

        override fun client(): FieldTelemetryRuntime? = null
    }

    private class CountingSensorController : FieldSensorController {
        var starts = 0
        var stops = 0

        override fun start(session: CaptureSessionMetadata) {
            starts += 1
        }

        override fun stop() {
            stops += 1
        }
    }
}
