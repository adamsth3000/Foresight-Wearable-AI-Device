package com.foresight.gateway.capture

import android.content.Context
import android.content.ContextWrapper
import android.view.SurfaceView
import com.foresight.gateway.metadata.CaptureSessionMetadata
import com.foresight.gateway.telemetry.TelemetryClient
import com.foresight.gateway.transport.StreamLifecycle
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import kotlin.io.path.createTempDirectory

class CaptureServiceDependenciesTest {

    @Test
    fun `provider returns injected service-facing dependencies`() {
        val root = createTempDirectory("capture-service-dependencies").toFile()

        val context = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = root
        }

        val listener = object : PhoneCaptureController.Listener {
            override fun onCaptureStateChanged(
                lifecycle: StreamLifecycle,
                metadata: CaptureSessionMetadata?,
                detail: String?,
            ) = Unit
        }

        val repository = LocalRecordingMetadataRepository(
            metadataDirectory = File(root, "recording_metadata"),
            recordingsDirectory = File(root, "recordings"),
            mediaDirectories = mapOf(
                LocalMediaSourceId.PHONE_CAMERA to File(root, "recordings"),
                LocalMediaSourceId.GOPRO_RTMP to File(
                    root,
                    "gopro_ingest_recordings",
                ),
            ),
        )

        val fakePhone = FakePhoneFieldMediaController()
        val fakeTelemetry = FakeFieldTelemetryController()
        val fakeSensors = FakeFieldSensorController()

        var controllerFactoryCalled = false
        var telemetryFactoryCalled = false
        var sensorFactoryCalled = false

        val dependencies = CaptureServiceDependencies(
            controllerFactory = {
                    suppliedContext,
                    suppliedListener,
                    suppliedRepository,
                ->
                controllerFactoryCalled = true

                assertSame(context, suppliedContext)
                assertSame(listener, suppliedListener)
                assertSame(repository, suppliedRepository)

                fakePhone
            },
            telemetryFactory = { suppliedController ->
                telemetryFactoryCalled = true

                assertSame(fakePhone, suppliedController)

                fakeTelemetry
            },
            sensorFactory = {
                    suppliedContext,
                    _,
                    _,
                ->
                sensorFactoryCalled = true

                assertSame(context, suppliedContext)

                fakeSensors
            },
        )

        assertSame(
            fakePhone,
            dependencies.controller(
                context = context,
                listener = listener,
                repository = repository,
            ),
        )

        assertSame(
            fakeTelemetry,
            dependencies.telemetry(fakePhone),
        )

        assertSame(
            fakeSensors,
            dependencies.sensors(
                context = context,
                telemetry = { fakeTelemetry.client() },
                status = {},
            ),
        )

        assertTrue(controllerFactoryCalled)
        assertTrue(telemetryFactoryCalled)
        assertTrue(sensorFactoryCalled)
    }

    private class FakePhoneFieldMediaController :
        PhoneFieldMediaController {

        override val telemetryListener: TelemetryClient.Listener =
            object : TelemetryClient.Listener {
                override fun onTelemetryBound(
                    captureSessionId: String,
                ) = Unit

                override fun onTelemetryStatus(
                    detail: String,
                ) = Unit
            }

        override fun start(
            endpoint: String?,
            telemetryEndpoint: String,
            fieldSupportExternallyOwned: Boolean,
            sessionOverride: CaptureSessionMetadata?,
        ) = Unit

        override fun stop(
            fieldSupportExternallyOwned: Boolean,
        ) = Unit

        override fun startFieldSupport(
            session: CaptureSessionMetadata,
            telemetryEndpoint: String,
        ) = Unit

        override fun stopFieldSupport() = Unit

        override fun attachPreview(
            surfaceView: SurfaceView,
        ) = Unit

        override fun detachPreview(
            surfaceView: SurfaceView,
        ) = Unit

        override fun authoritativeEventStarted(
            eventId: String,
            receiptUtc: Instant,
            receiptMonotonicMillis: Long,
        ) = Unit

        override fun authoritativeEventEnded(
            eventId: String,
            receiptUtc: Instant,
            receiptMonotonicMillis: Long,
        ) = Unit

        override fun startFieldEvent(
            callback: (Result<LocalEventMapping>) -> Unit,
        ) = Unit

        override fun endFieldEvent(
            callback: (Result<LocalEventMapping>) -> Unit,
        ) = Unit

        override fun activeFieldEvent(
            callback: (LocalEventMapping?) -> Unit,
        ) = Unit

        override fun fieldEventReadiness(): FieldEventReadiness =
            FieldEventReadiness.NOT_CAPTURING

        override fun localMediaAvailability(): LocalMediaAvailability =
            LocalMediaAvailability.UNAVAILABLE

        override fun syncReadyEventMedia(
            eventId: String,
            controlEndpoint: String,
            callback: (EventMediaSyncUiState) -> Unit,
        ) = Unit

        override fun syncAllReadyEventMedia(
            controlEndpoint: String,
            callback: (
                eventId: String,
                state: EventMediaSyncUiState,
                completed: Int,
                total: Int,
            ) -> Unit,
        ) = Unit

        override fun eventMediaSyncState(
            eventId: String,
        ): EventMediaSyncState? = null

        override fun eventMediaExtractionState(
            eventId: String,
        ): EventMediaExtractionState? = null

        override fun latestSyncableEventId(): String? = null

        override fun syncHistory():
            List<EventMediaSyncHistoryEntry> = emptyList()

        override fun syncSummary(): EventMediaSyncSummary =
            EventMediaSyncSummary(
                readyLocalOnlyCount = 0,
                syncedCount = 0,
                retryableCount = 0,
            )

        override fun syncableEventIds(): List<String> =
            emptyList()

        override fun startDiagnostics(): String = "fake"
    }

    private class FakeFieldTelemetryController :
        FieldTelemetryController {

        override fun start(
            session: CaptureSessionMetadata,
            endpoint: String,
        ) = Unit

        override fun stop() = Unit

        override fun client(): FieldTelemetryRuntime? = null
    }

    private class FakeFieldSensorController :
        FieldSensorController {

        override fun start(
            session: CaptureSessionMetadata,
        ) = Unit

        override fun stop() = Unit
    }
}