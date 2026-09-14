package com.foresight.gateway.capture

import com.foresight.gateway.metadata.CaptureSessionMetadata

/** Service-owned FIELD lifecycle. Media adapters retain ownership of their low-level pipelines. */
internal interface FieldMediaController {
    val source: LocalMediaSourceId
    fun start(session: CaptureSessionMetadata)
    fun start(session: CaptureSessionMetadata, telemetryEndpoint: String) = start(session)
    fun stop()
    fun onSourceLive()
    fun availability(): LocalMediaAvailability
}

internal interface FieldSupportController {
    fun start(session: CaptureSessionMetadata)
    fun stop()
}

internal data class FieldSessionSnapshot(
    val session: CaptureSessionMetadata? = null,
    val source: LocalMediaSourceId? = null,
    val availability: LocalMediaAvailability = LocalMediaAvailability.UNAVAILABLE,
) {
    val active: Boolean get() = session != null
}

/** Keeps source selection immutable for one FIELD session and starts support independently of media. */
internal class FieldSessionCoordinator private constructor(
    private val phone: FieldMediaController,
    private val goPro: FieldMediaController?,
    private val startSensors: (CaptureSessionMetadata) -> Unit,
    private val stopSensors: () -> Unit,
    private val startTelemetry: (CaptureSessionMetadata, String) -> Unit,
    private val stopTelemetry: () -> Unit,
) {
    private var activeSource: LocalMediaSourceId? = null
    private var activeSession: CaptureSessionMetadata? = null

    constructor(
        phone: FieldMediaController,
        goPro: FieldMediaController,
        sensors: FieldSupportController,
        telemetry: FieldSupportController,
    ) : this(
        phone = phone,
        goPro = goPro,
        startSensors = sensors::start,
        stopSensors = sensors::stop,
        startTelemetry = { session, _ -> telemetry.start(session) },
        stopTelemetry = telemetry::stop,
    )

    /** Production constructor for PHONE_CAMERA and GOPRO_RTMP FIELD orchestration. */
    constructor(
        phone: FieldMediaController,
        goPro: FieldMediaController,
        sensors: FieldSensorController,
        telemetry: FieldTelemetryController,
    ) : this(
        phone = phone,
        goPro = goPro,
        startSensors = sensors::start,
        stopSensors = sensors::stop,
        startTelemetry = telemetry::start,
        stopTelemetry = telemetry::stop,
    )

    /** PHONE-only constructor retained for the focused C2e-1 tests. */
    constructor(
        phone: FieldMediaController,
        sensors: FieldSensorController,
        telemetry: FieldTelemetryController,
    ) : this(
        phone = phone,
        goPro = null,
        startSensors = sensors::start,
        stopSensors = sensors::stop,
        startTelemetry = telemetry::start,
        stopTelemetry = telemetry::stop,
    )

    @Synchronized
    fun snapshot(): FieldSessionSnapshot {
        val source = activeSource ?: return FieldSessionSnapshot()
        return FieldSessionSnapshot(
            session = activeSession,
            source = source,
            availability = controller(source).availability(),
        )
    }

    fun start(
        source: LocalMediaSourceId,
        session: CaptureSessionMetadata,
    ): Boolean = start(source, session, session.streamEndpoint)

    fun start(
        source: LocalMediaSourceId,
        session: CaptureSessionMetadata,
        telemetryEndpoint: String,
    ): Boolean {
        synchronized(this) {
            if (activeSource != null) return false
            activeSource = source
            activeSession = session
        }

        var telemetryStarted = false
        var sensorsStarted = false

        try {
            startTelemetry(session, telemetryEndpoint)
            telemetryStarted = true

            startSensors(session)
            sensorsStarted = true

            controller(source).start(session, telemetryEndpoint)
            return true
        } catch (error: RuntimeException) {
            if (sensorsStarted) stopSensors()
            if (telemetryStarted) stopTelemetry()
            clearSession()
            throw error
        }
    }

    fun onGoProLive() {
        synchronized(this) {
            if (activeSource != LocalMediaSourceId.GOPRO_RTMP) return
        }
        controller(LocalMediaSourceId.GOPRO_RTMP).onSourceLive()
    }

    fun end() {
        val source = synchronized(this) { activeSource } ?: return
        controller(source).stop()
        releaseSupportsAndSession(source)
    }

    fun onSourceEnded(source: LocalMediaSourceId) {
        releaseSupportsAndSession(source)
    }

    @Synchronized
    fun allowsDiagnosticGoProRecording(): Boolean =
        activeSource != LocalMediaSourceId.GOPRO_RTMP

    private fun releaseSupportsAndSession(source: LocalMediaSourceId) {
        synchronized(this) {
            if (activeSource != source) return
            activeSource = null
            activeSession = null
        }

        stopSensors()
        stopTelemetry()
    }

    @Synchronized
    private fun clearSession() {
        activeSource = null
        activeSession = null
    }

    private fun controller(source: LocalMediaSourceId): FieldMediaController =
        when (source) {
            LocalMediaSourceId.PHONE_CAMERA -> phone
            LocalMediaSourceId.GOPRO_RTMP -> requireNotNull(goPro) {
                "GoPro FIELD coordinator wiring is not enabled."
            }
        }
}
