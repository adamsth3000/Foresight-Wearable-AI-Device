package com.foresight.gateway.capture

import com.foresight.gateway.metadata.CaptureSessionMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneFieldServiceLifecycleTest {

    @Test
    fun `normal PHONE FIELD lifecycle owns media telemetry and sensors exactly once`() {
        val telemetry = CountingTelemetryController()
        val sensors = CountingSensorController()
        var mediaStarts = 0
        var mediaStops = 0
        val session = CaptureSessionMetadata(streamEndpoint = "")

        val lifecycle = PhoneFieldServiceLifecycle(
            startMedia = { endpoint, telemetryEndpoint, suppliedSession ->
                assertEquals(null, endpoint)
                assertEquals("http://telemetry", telemetryEndpoint)
                assertEquals(session, suppliedSession)
                mediaStarts += 1
            },
            stopMedia = {
                mediaStops += 1
            },
            telemetry = telemetry,
            sensors = sensors,
        )

        assertTrue(
            lifecycle.start(
                endpoint = null,
                telemetryEndpoint = "http://telemetry",
                session = session,
            ),
        )

        assertTrue(lifecycle.isActive())
        assertEquals(1, telemetry.starts)
        assertEquals(1, sensors.starts)
        assertEquals(1, mediaStarts)

        lifecycle.stop()

        assertFalse(lifecycle.isActive())
        assertEquals(1, mediaStops)
        assertEquals(1, sensors.stops)
        assertEquals(1, telemetry.stops)
    }

    @Test
    fun `duplicate PHONE FIELD start and stop do not duplicate ownership calls`() {
        val telemetry = CountingTelemetryController()
        val sensors = CountingSensorController()
        var mediaStarts = 0
        var mediaStops = 0
        val session = CaptureSessionMetadata(streamEndpoint = "")

        val lifecycle = PhoneFieldServiceLifecycle(
            startMedia = { _, _, _ -> mediaStarts += 1 },
            stopMedia = { mediaStops += 1 },
            telemetry = telemetry,
            sensors = sensors,
        )

        assertTrue(lifecycle.start(null, "http://telemetry", session))
        assertFalse(lifecycle.start(null, "http://telemetry", session))

        lifecycle.stop()
        lifecycle.stop()

        assertEquals(1, telemetry.starts)
        assertEquals(1, sensors.starts)
        assertEquals(1, mediaStarts)
        assertEquals(1, mediaStops)
        assertEquals(1, sensors.stops)
        assertEquals(1, telemetry.stops)
    }

    @Test
    fun `media start failure rolls back sensor and telemetry support exactly once`() {
        val telemetry = CountingTelemetryController()
        val sensors = CountingSensorController()
        var mediaStarts = 0
        var mediaStops = 0
        val session = CaptureSessionMetadata(streamEndpoint = "")

        val lifecycle = PhoneFieldServiceLifecycle(
            startMedia = { _, _, _ ->
                mediaStarts += 1
                throw IllegalStateException("simulated phone media start failure")
            },
            stopMedia = {
                mediaStops += 1
            },
            telemetry = telemetry,
            sensors = sensors,
        )

        assertThrows(IllegalStateException::class.java) {
            lifecycle.start(
                endpoint = null,
                telemetryEndpoint = "http://telemetry",
                session = session,
            )
        }

        assertFalse(lifecycle.isActive())
        assertEquals(1, telemetry.starts)
        assertEquals(1, sensors.starts)
        assertEquals(1, mediaStarts)

        // startMedia never established media ownership, so stopMedia is not called here.
        assertEquals(0, mediaStops)
        assertEquals(1, sensors.stops)
        assertEquals(1, telemetry.stops)

        // Later cleanup must remain a no-op rather than double-stopping support.
        lifecycle.stop()
        lifecycle.onMediaEnded()

        assertEquals(0, mediaStops)
        assertEquals(1, sensors.stops)
        assertEquals(1, telemetry.stops)
    }

    @Test
    fun `asynchronous phone media end releases supports once and later stop is safe`() {
        val telemetry = CountingTelemetryController()
        val sensors = CountingSensorController()
        var mediaStarts = 0
        var mediaStops = 0
        val session = CaptureSessionMetadata(streamEndpoint = "")

        val lifecycle = PhoneFieldServiceLifecycle(
            startMedia = { _, _, _ -> mediaStarts += 1 },
            stopMedia = { mediaStops += 1 },
            telemetry = telemetry,
            sensors = sensors,
        )

        assertTrue(lifecycle.start(null, "http://telemetry", session))
        assertTrue(lifecycle.isActive())

        // Mirrors CaptureForegroundService receiving the existing IDLE/ERROR media callback.
        lifecycle.onMediaEnded()

        assertFalse(lifecycle.isActive())
        assertEquals(1, mediaStarts)
        assertEquals(0, mediaStops)
        assertEquals(1, sensors.stops)
        assertEquals(1, telemetry.stops)

        // An eventual user/service stop after the async callback must not duplicate anything.
        lifecycle.stop()
        lifecycle.onMediaEnded()

        assertEquals(0, mediaStops)
        assertEquals(1, sensors.stops)
        assertEquals(1, telemetry.stops)
    }

    @Test
    fun `service destruction style stop cleans active phone field lifecycle exactly once`() {
        val telemetry = CountingTelemetryController()
        val sensors = CountingSensorController()
        var mediaStarts = 0
        var mediaStops = 0
        val session = CaptureSessionMetadata(streamEndpoint = "")

        val lifecycle = PhoneFieldServiceLifecycle(
            startMedia = { _, _, _ -> mediaStarts += 1 },
            stopMedia = { mediaStops += 1 },
            telemetry = telemetry,
            sensors = sensors,
        )

        assertTrue(lifecycle.start(null, "http://telemetry", session))

        // CaptureForegroundService.onDestroy() delegates active PHONE FIELD cleanup here.
        lifecycle.stop()

        assertFalse(lifecycle.isActive())
        assertEquals(1, mediaStarts)
        assertEquals(1, mediaStops)
        assertEquals(1, sensors.stops)
        assertEquals(1, telemetry.stops)

        // Duplicate destruction/END cleanup remains safe.
        lifecycle.stop()
        lifecycle.onMediaEnded()

        assertEquals(1, mediaStops)
        assertEquals(1, sensors.stops)
        assertEquals(1, telemetry.stops)
    }

    private class CountingTelemetryController : FieldTelemetryController {
        var starts = 0
        var stops = 0

        override fun start(session: CaptureSessionMetadata, endpoint: String) {
            starts += 1
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
