package com.foresight.gateway.capture

import android.content.Context
import com.foresight.gateway.metadata.CaptureSessionMetadata
import com.foresight.gateway.sensors.PhoneSensorCapture
import com.foresight.gateway.telemetry.TelemetryClient

internal interface FieldTelemetryRuntime {
    fun start(session: CaptureSessionMetadata, endpoint: String)
    fun stop()
    fun sensorClient(): TelemetryClient?
}

internal interface FieldSensorRuntime {
    fun start()
    fun stop()
}

private class TelemetryRuntime(
    private val client: TelemetryClient,
) : FieldTelemetryRuntime {
    override fun start(session: CaptureSessionMetadata, endpoint: String) =
        client.start(session, endpoint)

    override fun stop() = client.stop()

    override fun sensorClient(): TelemetryClient = client
}

private class SensorRuntime(
    private val capture: PhoneSensorCapture,
) : FieldSensorRuntime {
    override fun start() = capture.start()

    override fun stop() = capture.stop()
}

/** Source-neutral owner for the real telemetry client used by one FIELD session. */
internal class FieldTelemetrySupport(
    private val listener: TelemetryClient.Listener,
    private val factory: (TelemetryClient.Listener) -> FieldTelemetryRuntime = {
        TelemetryRuntime(TelemetryClient(it))
    },
) : FieldSupportController, FieldTelemetryController {
    private var client: FieldTelemetryRuntime? = null

    override fun start(session: CaptureSessionMetadata) =
        start(session, session.streamEndpoint)

    override fun start(session: CaptureSessionMetadata, endpoint: String) {
        if (client != null) return
        client = factory(listener).also { it.start(session, endpoint) }
    }

    override fun stop() {
        client?.stop()
        client = null
    }

    override fun client(): FieldTelemetryRuntime? = client
}

/** Source-neutral owner for the real Android sensor capture used by one FIELD session. */
internal class PhoneFieldSensorSupport(
    context: Context,
    private val telemetry: () -> FieldTelemetryRuntime?,
    private val status: (String) -> Unit,
    private val factory: (
        Context,
        FieldTelemetryRuntime,
        (String) -> Unit,
    ) -> FieldSensorRuntime = { runtimeContext, telemetryRuntime, callback ->
        SensorRuntime(
            PhoneSensorCapture(
                runtimeContext,
                requireNotNull(telemetryRuntime.sensorClient()),
                callback,
            ),
        )
    },
) : FieldSupportController, FieldSensorController {
    // A local JVM test context has no application context; Android application contexts do.
    private val applicationContext = context.applicationContext ?: context
    private var sensors: FieldSensorRuntime? = null

    override fun start(session: CaptureSessionMetadata) {
        if (sensors != null) return
        val client = requireNotNull(telemetry()) {
            "Telemetry must start before sensors."
        }
        sensors = factory(applicationContext, client, status).also { it.start() }
    }

    override fun stop() {
        sensors?.stop()
        sensors = null
    }
}
