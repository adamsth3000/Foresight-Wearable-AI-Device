package com.foresight.gateway.capture

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class LocalMediaSourceTest {
    @Test
    fun `phone context retains the legacy phone media identity by default`() {
        val context = LocalRecordingContext(
            "phone-1", "phone-session", 7, "capture-phone-1.mp4",
            Instant.parse("2026-09-02T12:00:00Z"), 1_000L, true,
        )

        assertEquals(LocalMediaSourceId.PHONE_CAMERA, context.mediaSource)
        assertEquals(LocalMediaLocation.phoneCamera("capture-phone-1.mp4"), context.mediaLocation)
    }

    @Test
    fun `gopro context uses a distinct private location and source identity`() {
        val context = LocalRecordingContext(
            "gopro-1", "gopro-generation-12", 0, "gopro-gopro-1.mp4",
            Instant.parse("2026-09-02T12:00:00Z"), 1_000L, true,
            mediaSource = LocalMediaSourceId.GOPRO_RTMP,
            mediaLocation = LocalMediaLocation.goProRtmp("gopro-gopro-1.mp4"),
            sourceGenerationId = "12",
        )

        assertEquals(LocalMediaSourceId.GOPRO_RTMP, context.mediaSource)
        assertEquals("gopro_ingest_recordings", context.mediaLocation.directoryName)
    }
}
