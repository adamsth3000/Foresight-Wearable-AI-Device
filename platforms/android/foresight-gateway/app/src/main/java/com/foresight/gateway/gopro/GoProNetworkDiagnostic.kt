package com.foresight.gateway.gopro

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

/** One-shot, read-only network snapshot emitted when GoPro ingress is requested. */
internal object GoProNetworkDiagnostic {
    fun log(context: Context, selectedAddress: String?, port: Int, path: String) {
        Log.i(TAG, formatJavaInterfaces(javaInterfaces()))
        Log.i(TAG, formatConnectivityNetworks(connectivityNetworks(context)))
        Log.i(TAG, formatSelection(selectedAddress, port, path))
    }

    private fun javaInterfaces(): List<JavaInterfaceSnapshot> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { networkInterface ->
            val isUp = runCatching { networkInterface.isUp }.getOrDefault(false)
            val isLoopback = runCatching { networkInterface.isLoopback }.getOrDefault(false)
            val isVirtual = runCatching { networkInterface.isVirtual }.getOrDefault(false)
            networkInterface.inetAddresses.toList().mapNotNull { address ->
                val ipv4 = address as? Inet4Address ?: return@mapNotNull null
                val hostAddress = ipv4.hostAddress ?: return@mapNotNull null
                JavaInterfaceSnapshot(networkInterface.name, hostAddress, isUp, isLoopback, isVirtual)
            }
        }
    }.getOrElse { error ->
        listOf(JavaInterfaceSnapshot("<unavailable: ${error.javaClass.simpleName}>", "<none>", false, false, false))
    }

    private fun connectivityNetworks(context: Context): List<ConnectivityNetworkSnapshot> {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
            ?: return listOf(ConnectivityNetworkSnapshot("<unavailable>", "OTHER", false, null, emptyList(), emptyList()))
        val activeNetwork = connectivity.activeNetwork
        return connectivity.allNetworks.map { network ->
            val capabilities = connectivity.getNetworkCapabilities(network)
            val properties = connectivity.getLinkProperties(network)
            ConnectivityNetworkSnapshot(
                identity = network.toString(),
                transport = transportName(capabilities),
                active = network == activeNetwork,
                interfaceName = properties?.interfaceName,
                ipv4Addresses = properties?.linkAddresses.orEmpty().mapNotNull { linkAddress ->
                    (linkAddress.address as? Inet4Address)?.hostAddress
                },
                routes = properties?.routes.orEmpty().map { it.toString() },
            )
        }
    }

    private fun transportName(capabilities: NetworkCapabilities?): String = when {
        capabilities == null -> "OTHER"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
        else -> "OTHER"
    }

    internal fun formatJavaInterfaces(interfaces: List<JavaInterfaceSnapshot>): String = buildString {
        append("FORESIGHT_NETWORK_INTERFACES")
        if (interfaces.isEmpty()) append("\n<no IPv4 addresses visible>")
        interfaces.forEach { snapshot ->
            append("\ninterface=").append(snapshot.name)
            append(" address=").append(snapshot.address)
            append(" up=").append(snapshot.isUp)
            append(" loopback=").append(snapshot.isLoopback)
            append(" virtual=").append(snapshot.isVirtual)
        }
    }

    internal fun formatConnectivityNetworks(networks: List<ConnectivityNetworkSnapshot>): String = buildString {
        append("FORESIGHT_CONNECTIVITY_NETWORKS")
        if (networks.isEmpty()) append("\n<no networks visible>")
        networks.forEach { snapshot ->
            append("\nnetwork=").append(snapshot.identity)
            append(" transport=").append(snapshot.transport)
            append(" active=").append(snapshot.active)
            append(" interfaceName=").append(snapshot.interfaceName ?: "<none>")
            append(" ipv4LinkAddresses=").append(snapshot.ipv4Addresses.joinToString(prefix = "[", postfix = "]"))
            append(" routes=").append(snapshot.routes.joinToString(prefix = "[", postfix = "]"))
        }
    }

    internal fun formatSelection(selectedAddress: String?, port: Int, path: String): String {
        val target = selectedAddress?.let { "rtmp://$it:$port/$path" }
        return "FORESIGHT_GOPRO_RTMP_SELECTION selectedGoProRtmpAddress=${selectedAddress ?: "null"}" +
            " rtmpTarget=${target ?: "null"}"
    }

    private const val TAG = "GoProNetworkDiagnostic"
}

internal data class JavaInterfaceSnapshot(
    val name: String,
    val address: String,
    val isUp: Boolean,
    val isLoopback: Boolean,
    val isVirtual: Boolean,
)

internal data class ConnectivityNetworkSnapshot(
    val identity: String,
    val transport: String,
    val active: Boolean,
    val interfaceName: String?,
    val ipv4Addresses: List<String>,
    val routes: List<String>,
)
