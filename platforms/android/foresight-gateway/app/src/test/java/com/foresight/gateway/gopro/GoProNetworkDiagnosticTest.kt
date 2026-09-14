package com.foresight.gateway.gopro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoProNetworkDiagnosticTest {
    @Test
    fun `formats visible interface IPv4 details`() {
        val text = GoProNetworkDiagnostic.formatJavaInterfaces(
            listOf(JavaInterfaceSnapshot("wlan0", "192.168.50.1", true, false, false)),
        )

        assertEquals(
            "FORESIGHT_NETWORK_INTERFACES\n" +
                "interface=wlan0 address=192.168.50.1 up=true loopback=false virtual=false",
            text,
        )
    }

    @Test
    fun `formats connectivity network and selected target`() {
        val networks = GoProNetworkDiagnostic.formatConnectivityNetworks(
            listOf(
                ConnectivityNetworkSnapshot(
                    identity = "101",
                    transport = "WIFI",
                    active = true,
                    interfaceName = "wlan0",
                    ipv4Addresses = listOf("192.168.50.1"),
                    routes = listOf("0.0.0.0/0 -> 192.168.50.1 wlan0"),
                ),
            ),
        )

        assertTrue(networks.startsWith("FORESIGHT_CONNECTIVITY_NETWORKS\n"))
        assertTrue(networks.contains("network=101 transport=WIFI active=true interfaceName=wlan0"))
        assertTrue(networks.contains("ipv4LinkAddresses=[192.168.50.1]"))
        assertEquals(
            "FORESIGHT_GOPRO_RTMP_SELECTION selectedGoProRtmpAddress=192.168.50.1 " +
                "rtmpTarget=rtmp://192.168.50.1:1935/gopro",
            GoProNetworkDiagnostic.formatSelection("192.168.50.1", 1935, "gopro"),
        )
    }

    @Test
    fun `formats unavailable selection without constructing a target`() {
        assertEquals(
            "FORESIGHT_GOPRO_RTMP_SELECTION selectedGoProRtmpAddress=null rtmpTarget=null",
            GoProNetworkDiagnostic.formatSelection(null, 1935, "gopro"),
        )
    }
}
