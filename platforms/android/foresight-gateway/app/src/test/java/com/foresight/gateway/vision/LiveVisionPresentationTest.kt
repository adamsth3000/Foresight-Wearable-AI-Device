package com.foresight.gateway.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveVisionPresentationTest {
    @Test
    fun `maps a normalized box through explicit source and destination dimensions`() {
        val mapped = PreviewCoordinateTransform(640, 360, 1280, 720).map(
            LiveNormalizedBoundingBox(0.1f, 0.25f, 0.5f, 0.75f),
        )

        assertEquals(128f, mapped.left, 0.001f)
        assertEquals(180f, mapped.top, 0.001f)
        assertEquals(640f, mapped.right, 0.001f)
        assertEquals(540f, mapped.bottom, 0.001f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects invalid normalized boxes`() {
        LiveNormalizedBoundingBox(-0.1f, 0f, 0.5f, 0.5f)
    }

    @Test
    fun `newest only gate never allows a concurrent request`() {
        val gate = NewestOnlyFrameGate()

        org.junit.Assert.assertTrue(gate.tryBegin(1_000, 1_000))
        org.junit.Assert.assertFalse(gate.tryBegin(2_000, 1_000))
        gate.complete()
        org.junit.Assert.assertTrue(gate.tryBegin(2_000, 1_000))
    }

    @Test
    fun `parser accepts valid response and rejects malformed normalized detection`() {
        val response = """
            {"schema_version":1,"runtime_generation":1,"frame_id":2,
             "capture_elapsed_realtime_nanos":3,"source":{"width":640,"height":360},
             "detector":{"backend":"fake","model":"unit"},"detections":[
               {"label":"person","confidence":0.9,
                "bounding_box":{"x_min":0.1,"y_min":0.2,"x_max":0.5,"y_max":0.8},
                "prompt":"person"}]}
        """.trimIndent()
        assertEquals("person", LiveVisionResponseParser.parse(response)?.detections?.single()?.label)
        assertNull(LiveVisionResponseParser.parse(response.replace("\"x_min\":0.1", "\"x_min\":-0.1")))
    }

    @Test
    fun `endpoint normalization keeps optional configured base local`() {
        assertEquals("http://192.168.1.2:8767/v1/detect", LocalVisionEndpoint.detectionUrl("http://192.168.1.2:8767")?.toString())
        assertNull(LocalVisionEndpoint.detectionUrl(""))
    }
}
