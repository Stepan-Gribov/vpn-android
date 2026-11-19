package com.v2ray.ang.simple

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/** Private DNS hop for direct traffic. Every query uses the current physical
 * network and its assigned resolvers, including after Wi-Fi/mobile handover.
 * This does not create a VPN or use the subscription bootstrap tunnel. */
object PhysicalDnsBridge {
    @Volatile private var preferredNetwork: Network? = null
    @Volatile private var instance: Listener? = null
    val endpoint: String get() = checkNotNull(instance) { "Physical DNS is not running" }.endpoint

    fun underlyingNetwork(network: Network?) { preferredNetwork = network }

    @Synchronized fun start(context: Context) {
        if(instance != null) return
        instance = Listener(context.applicationContext)
    }

    @Synchronized fun stop() {
        instance?.close()
        instance = null
        preferredNetwork = null
    }

    private class Listener(context: Context) {
        private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        private val pending = ConcurrentHashMap.newKeySet<DatagramSocket>()
        private val socketLock=Any()
        private var closed=false
        private val server=UdpDnsBridge(::resolve) {
            synchronized(socketLock) { closed=true; pending.forEach { it.close() } }
        }
        val endpoint: String get()=server.endpoint

        @Suppress("DEPRECATION")
        private fun network(): Network? {
            fun usable(value: Network?): Boolean = value?.let(connectivity::getNetworkCapabilities)?.let {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            } == true
            return preferredNetwork?.takeIf(::usable) ?: connectivity.activeNetwork?.takeIf(::usable)
                ?: connectivity.allNetworks.filter(::usable).sortedByDescending {
                    connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true
                }.firstOrNull()
        }

        private fun resolve(query: ByteArray): ByteArray? {
            // Re-read the network for each attempt so a handover cannot pin DNS
            // to the old router/operator. Bound both work and outstanding sockets.
            repeat(2) { attempt ->
                if(server.isClosed) return null
                val network = network() ?: return null
                val resolvers = connectivity.getLinkProperties(network)?.dnsServers.orEmpty()
                    .filterNot { it.isAnyLocalAddress || it.isLoopbackAddress }.take(2)
                val resolver = resolvers.getOrNull(attempt % resolvers.size.coerceAtLeast(1)) ?: return null
                val answer = runCatching {
                    DatagramSocket(null).use { upstream ->
                        synchronized(socketLock) {
                            if(closed) return null
                            pending += upstream
                        }
                        try {
                            network.bindSocket(upstream)
                            upstream.soTimeout=1200
                            upstream.connect(resolver,53)
                            upstream.send(DatagramPacket(query,query.size))
                            val response=DatagramPacket(ByteArray(65535),65535)
                            upstream.receive(response)
                            response.data.copyOf(response.length).takeIf {
                                it.size >= 12 && it[0]==query[0] && it[1]==query[1] && it[2].toInt() and 0x80 != 0
                            }
                        } finally { pending -= upstream }
                    }
                }.getOrNull()
                if(answer != null && PhysicalDnsAnswerPolicy.accepts(query, answer)) return answer
                if(answer != null) android.util.Log.i("TnDtun-DNS", "Rejected synthetic or invalid physical DNS answer")
            }
            return null
        }

        fun close() = server.close()
    }
}
