package com.foresight.gateway.capture

import android.content.Context

internal typealias PhoneFieldMediaControllerFactory = (
    context: Context,
    listener: PhoneCaptureController.Listener,
    repository: LocalRecordingMetadataRepository,
) -> PhoneFieldMediaController

internal typealias FieldTelemetryControllerFactory = (
    controller: PhoneFieldMediaController,
) -> FieldTelemetryController

internal typealias FieldSensorControllerFactory = (
    context: Context,
    telemetry: () -> FieldTelemetryRuntime?,
    status: (String) -> Unit,
) -> FieldSensorController

/** Narrow construction seam for the pre-coordinator PHONE FIELD service path. */
internal class CaptureServiceDependencies(
    private val controllerFactory: PhoneFieldMediaControllerFactory = { context, listener, repository ->
        PhoneCaptureController(
            context = context,
            listener = listener,
            recordingRepository = repository,
        )
    },
    private val telemetryFactory: FieldTelemetryControllerFactory = { controller ->
        FieldTelemetrySupport(controller.telemetryListener)
    },
    private val sensorFactory: FieldSensorControllerFactory = { context, telemetry, status ->
        PhoneFieldSensorSupport(
            context = context,
            telemetry = telemetry,
            status = status,
        )
    },
) {
    fun controller(
        context: Context,
        listener: PhoneCaptureController.Listener,
        repository: LocalRecordingMetadataRepository,
    ): PhoneFieldMediaController =
        controllerFactory(context, listener, repository)

    fun telemetry(
        controller: PhoneFieldMediaController,
    ): FieldTelemetryController =
        telemetryFactory(controller)

    fun sensors(
        context: Context,
        telemetry: () -> FieldTelemetryRuntime?,
        status: (String) -> Unit,
    ): FieldSensorController =
        sensorFactory(context, telemetry, status)
}