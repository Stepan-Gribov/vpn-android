package com.v2ray.ang.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.v2ray.ang.util.LogUtil

/** Reads the DNS servers assigned to an underlying Wi-Fi, Ethernet, or mobile network. */
object ManagedDnsTunnelResolverDiscovery {
    @Suppress("DEPRECATION")
    fun discover(context: Context): List<String> = runCatching {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return emptyList()
        val active = connectivity.activeNetwork
        val candidates = buildList {
            active?.let(::add)
            connectivity.allNetworks.forEach { network -> if (network != active) add(network) }
        }
        candidates
            .asSequence()
            .filter { isPhysicalInternetNetwork(connectivity, it) }
            .flatMap { network ->
                connectivity.getLinkProperties(network)
                    ?.dnsServers
                    .orEmpty()
                    .asSequence()
            }
            .filterNot { it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress }
            .mapNotNull { address ->
                val host = address.hostAddress?.substringBefore('%') ?: return@mapNotNull null
                if (host.contains(':')) "[$host]:53" else "$host:53"
            }
            .mapNotNull(ManagedDnsTunnelManager::normalizeResolver)
            .distinct()
            .toList()
    }.getOrElse { error ->
        LogUtil.w("KindlyVPN", "Could not read DNS servers from the active network", error)
        emptyList()
    }

    private fun isPhysicalInternetNetwork(
        connectivity: ConnectivityManager,
        network: Network,
    ): Boolean {
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }
}
