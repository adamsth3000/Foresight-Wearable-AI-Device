package com.foresight.gateway.gopro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GoProLanAddressSelectorTest {
    @Test
    fun `active wifi address wins over non-wifi addresses including a peer candidate`() {
        val selected = select(
            candidate("192.168.1.171", GoProNetworkTransport.OTHER, active = false),
            candidate("192.168.1.175", GoProNetworkTransport.WIFI, active = true),
            candidate("100.64.0.4", GoProNetworkTransport.VPN, active = true),
        )

        assertEquals("192.168.1.175", selected)
    }

    @Test
    fun `loopback and link-local addresses are rejected`() {
        val selected = select(
            candidate("127.0.0.1", GoProNetworkTransport.WIFI, active = true),
            candidate("169.254.2.3", GoProNetworkTransport.WIFI, active = true),
            candidate("192.168.1.175", GoProNetworkTransport.WIFI, active = true),
        )

        assertEquals("192.168.1.175", selected)
    }

    @Test
    fun `wifi wins over active cellular and vpn`() {
        val selected = select(
            candidate("10.0.0.4", GoProNetworkTransport.CELLULAR, active = true),
            candidate("100.64.0.4", GoProNetworkTransport.VPN, active = true),
            candidate("192.168.1.175", GoProNetworkTransport.WIFI, active = false),
        )

        assertEquals("192.168.1.175", selected)
    }

    @Test
    fun `fallback is deterministic when wifi is unavailable`() {
        val selected = select(
            candidate("10.0.0.5", GoProNetworkTransport.CELLULAR, active = false),
            candidate("10.0.0.4", GoProNetworkTransport.CELLULAR, active = true),
            candidate("100.64.0.4", GoProNetworkTransport.VPN, active = true),
        )

        assertEquals("10.0.0.4", selected)
    }

    @Test
    fun `no usable local address is unavailable`() {
        assertNull(
            select(
                candidate("127.0.0.1", GoProNetworkTransport.WIFI, active = true),
                candidate("169.254.2.3", GoProNetworkTransport.OTHER, active = false),
            ),
        )
    }

    private fun select(vararg candidates: GoProLanAddressCandidate): String? =
        GoProLanAddressSelector.select(candidates.asSequence())

    private fun candidate(address: String, transport: GoProNetworkTransport, active: Boolean): GoProLanAddressCandidate =
        GoProLanAddressCandidate(address, transport, active)
}
