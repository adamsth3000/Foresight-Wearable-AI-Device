package com.foresight.gateway.capture

import com.foresight.gateway.metadata.CaptureSessionMetadata

/**
 * PHONE_CAMERA adapter for FieldSessionCoordinator.
 *
 * The adapter deliberately keeps PhoneCaptureController's media pipeline intact and always marks
 * FIELD support as externally owned, because the coordinator owns telemetry/sensors.
 */
internal class PhoneFieldMediaAdapter private constructor(
    private val startAction: (
        endpoint: String?,
        telemetryEndpoint: String,
        session: CaptureSessionMetadata,
    ) -> Unit,
    private val stopAction: () -> Unit,
    private val availabilityProvider: () -> LocalMediaAvailability,
) : FieldMediaController {
    constructor(controller: PhoneFieldMediaController) : this(
        startAction = { endpoint, telemetryEndpoint, session ->
            controller.start(
                endpoint = endpoint,
                telemetryEndpoint = telemetryEndpoint,
                fieldSupportExternallyOwned = true,
                sessionOverride = session,
            )
        },
        stopAction = {
            controller.stop(fieldSupportExternallyOwned = true)
        },
        availabilityProvider = controller::localMediaAvailability,
    )

    internal constructor(
        startAction: (
            endpoint: String?,
            telemetryEndpoint: String,
            session: CaptureSessionMetadata,
        ) -> Unit,
        stopAction: () -> Unit,
        availabilityProvider: () -> LocalMediaAvailability,
        @Suppress("UNUSED_PARAMETER") testOnly: Unit,
    ) : this(startAction, stopAction, availabilityProvider)

    override val source: LocalMediaSourceId = LocalMediaSourceId.PHONE_CAMERA

    override fun start(session: CaptureSessionMetadata) {
        start(session, telemetryEndpoint = "")
    }

    override fun start(session: CaptureSessionMetadata, telemetryEndpoint: String) {
        startAction(
            session.streamEndpoint.takeIf { it.isNotBlank() },
            telemetryEndpoint,
            session,
        )
    }

    override fun stop() {
        stopAction()
    }

    override fun onSourceLive() = Unit

    override fun availability(): LocalMediaAvailability = availabilityProvider()
}
