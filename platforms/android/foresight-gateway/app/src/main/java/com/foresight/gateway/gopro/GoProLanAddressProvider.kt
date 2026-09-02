package com.foresight.gateway.gopro

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address

/** Resolves an advertised local address from Android's network-assigned LinkAddresses. */
object GoProLanAddressProvider {
    fun discover(context: Context): String? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val activeNetwork = connectivity.activeNetwork
        val candidates = connectivity.allNetworks.asSequence().flatMap { network ->
            localAddresses(connectivity, network, activeNetwork).asSequence()
        }
        return GoProLanAddressSelector.select(candidates)
    }

    private fun localAddresses(
        connectivity: ConnectivityManager,
        network: Network,
        activeNetwork: Network?,
    ): List<GoProLanAddressCandidate> {
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return emptyList()
        val properties = connectivity.getLinkProperties(network) ?: return emptyList()
        val transport = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> GoProNetworkTransport.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> GoProNetworkTransport.ETHERNET
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> GoProNetworkTransport.CELLULAR
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> GoProNetworkTransport.VPN
            else -> GoProNetworkTransport.OTHER
        }
        return properties.linkAddresses.mapNotNull { linkAddress ->
            val address = linkAddress.address as? Inet4Address ?: return@mapNotNull null
            val hostAddress = address.hostAddress ?: return@mapNotNull null
            GoProLanAddressCandidate(hostAddress, transport, network == activeNetwork)
        }
    }
}

internal enum class GoProNetworkTransport {
    WIFI,
    ETHERNET,
    CELLULAR,
    VPN,
    OTHER,
}

internal data class GoProLanAddressCandidate(
    val address: String,
    val transport: GoProNetworkTransport,
    val active: Boolean,
)

/** Pure ranking policy kept separate from Android APIs for deterministic local tests. */
internal object GoProLanAddressSelector {
    fun select(candidates: Sequence<GoProLanAddressCandidate>): String? = candidates
        .filter { isUsableIpv4(it.address) }
        .sortedWith(
            compareBy<GoProLanAddressCandidate>({ transportPriority(it.transport) }, { !it.active }, { it.address }),
        )
        .map { it.address }
        .firstOrNull()

    private fun isUsableIpv4(address: String): Boolean {
        val octets = address.split('.')
        if (octets.size != 4) return false
        val values = octets.map { it.toIntOrNull() ?: return false }
        if (values.any { it !in 0..255 }) return false
        return values[0] != 0 && values[0] != 127 && !(values[0] == 169 && values[1] == 254)
    }

    private fun transportPriority(transport: GoProNetworkTransport): Int = when (transport) {
        GoProNetworkTransport.WIFI -> 0
        GoProNetworkTransport.ETHERNET -> 1
        GoProNetworkTransport.CELLULAR -> 2
        GoProNetworkTransport.VPN -> 3
        GoProNetworkTransport.OTHER -> 4
    }
}
