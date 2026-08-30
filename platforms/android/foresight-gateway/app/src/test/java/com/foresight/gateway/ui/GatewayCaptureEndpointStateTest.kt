package com.foresight.gateway.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class GatewayCaptureEndpointStateTest {
    @Test
    fun `first capture start uses the restored RTSP destination`() {
        val state = GatewayCaptureEndpointState.restore("rtsp://192.168.1.171:8555/foresight-phone")

        assertEquals("rtsp://192.168.1.171:8555/foresight-phone", state.rtspEndpoint)
    }

    @Test
    fun `changed RTSP destination replaces the prior session destination`() {
        val first = GatewayCaptureEndpointState.restore("rtsp://192.168.1.171:8555/foresight-phone")

        val restarted = first.update(" rtsp://100.73.120.70:8555/foresight-phone ")

        assertEquals("rtsp://100.73.120.70:8555/foresight-phone", restarted.rtspEndpoint)
    }
}
