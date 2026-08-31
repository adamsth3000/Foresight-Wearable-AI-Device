package com.foresight.gateway.mode

import org.junit.Assert.assertEquals
import org.junit.Test

class GatewayOperatingModeTest {
    @Test
    fun `missing or invalid persisted mode defaults conservatively to lab`() {
        assertEquals(GatewayOperatingMode.LAB, GatewayOperatingMode.restore(null))
        assertEquals(GatewayOperatingMode.LAB, GatewayOperatingMode.restore("UNKNOWN"))
    }

    @Test
    fun `lab and field persisted values restore without network input`() {
        assertEquals(GatewayOperatingMode.LAB, GatewayOperatingMode.restore("LAB"))
        assertEquals(GatewayOperatingMode.FIELD, GatewayOperatingMode.restore("FIELD"))
    }

    @Test
    fun `field starts without RTSP while lab retains its configured endpoint requirement`() {
        assertEquals(false, GatewayOperatingModePolicy.canStartCapture(GatewayOperatingMode.LAB, ""))
        assertEquals(true, GatewayOperatingModePolicy.canStartCapture(GatewayOperatingMode.LAB, "rtsp://lab/phone"))
        assertEquals(true, GatewayOperatingModePolicy.canStartCapture(GatewayOperatingMode.FIELD, ""))
    }
}
