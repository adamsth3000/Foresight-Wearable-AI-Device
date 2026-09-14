package com.foresight.gateway.capture

import android.content.Context
import android.content.ContextWrapper
import com.foresight.gateway.metadata.CaptureSessionMetadata
import com.foresight.gateway.metadata.ClockAnchor
import com.foresight.gateway.telemetry.TelemetryClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.Instant

class FieldSupportTest {
    @Test
    fun `telemetry support owns one runtime per active lifecycle`() {
        val runtimes = mutableListOf<FakeTelemetryRuntime>()
        val support = FieldTelemetrySupport(listener = listener()) {
            FakeTelemetryRuntime().also(runtimes::add)
        }
        val session = session()

        support.start(session, "http://receiver:8766")
        support.start(session, "http://ignored:8766")

        assertEquals(1, runtimes.size)
        assertEquals(1, runtimes.single().starts)
        assertSame(session, runtimes.single().session)
        assertEquals("http://receiver:8766", runtimes.single().endpoint)

        support.stop()
        support.stop()
        assertEquals(1, runtimes.single().stops)

        support.start(session, "http://receiver:8766")
        assertEquals(2, runtimes.size)
        assertEquals(1, runtimes.last().starts)
    }

    @Test
    fun `sensor support owns one runtime per active lifecycle and keeps dependencies wired`() {
        val telemetry = FakeTelemetryRuntime()
        val runtimes = mutableListOf<FakeSensorRuntime>()
        val statuses = mutableListOf<String>()
        var factoryContext: Context? = null
        var factoryTelemetry: FieldTelemetryRuntime? = null
        var factoryStatus: ((String) -> Unit)? = null
        val support = PhoneFieldSensorSupport(
            context = TestContext(),
            telemetry = { telemetry },
            status = statuses::add,
            factory = { context, runtime, status ->
                factoryContext = context
                factoryTelemetry = runtime
                factoryStatus = status
                FakeSensorRuntime().also(runtimes::add)
            },
        )

        support.start(session())
        support.start(session())

        assertEquals(1, runtimes.size)
        assertEquals(1, runtimes.single().starts)
        assertNotNull(factoryContext)
        assertSame(telemetry, factoryTelemetry)
        requireNotNull(factoryStatus)("sensor status")
        assertEquals(listOf("sensor status"), statuses)

        support.stop()
        support.stop()
        assertEquals(1, runtimes.single().stops)

        support.start(session())
        assertEquals(2, runtimes.size)
        assertEquals(1, runtimes.last().starts)
    }

    private fun session() = CaptureSessionMetadata(
        streamEndpoint = "rtsp://field-test",
        clockAnchor = ClockAnchor(Instant.EPOCH, 1L),
    )

    private fun listener() = object : TelemetryClient.Listener {
        override fun onTelemetryBound(captureSessionId: String) = Unit
        override fun onTelemetryStatus(detail: String) = Unit
    }

    private class FakeTelemetryRuntime : FieldTelemetryRuntime {
        var starts = 0
        var stops = 0
        var session: CaptureSessionMetadata? = null
        var endpoint: String? = null

        override fun start(session: CaptureSessionMetadata, endpoint: String) {
            starts++
            this.session = session
            this.endpoint = endpoint
        }

        override fun stop() {
            stops++
        }

        override fun sensorClient(): TelemetryClient? = null
    }

    private class FakeSensorRuntime : FieldSensorRuntime {
        var starts = 0
        var stops = 0

        override fun start() {
            starts++
        }

        override fun stop() {
            stops++
        }
    }

    private class TestContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }
}
