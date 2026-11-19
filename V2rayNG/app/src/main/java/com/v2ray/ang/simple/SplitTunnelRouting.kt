package com.v2ray.ang.simple

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.V2rayConfig

/** Apply to app inbounds only; DNS transport and health checks retain their own paths. */
object SplitTunnelRouting {
    const val HEALTH_TAG = "simple-health"
    private const val DIRECT_DNS_TAG = "simple-dns-direct"
    private const val PROXY_DNS_TAG = "simple-dns-proxy"
    fun apply(config: V2rayConfig, split: SplitTunnelSettings, probePort: Int, managedDns: Boolean, directDnsEndpoint: String = "localhost", ipv6Enabled: Boolean = false) {
        // A dedicated loopback SOCKS listener prevents a direct-list match from making
        // a broken tunnel look healthy. It is used for both ping and recovery probes.
        val socks = config.inbounds.firstOrNull { it.tag == "socks" }
        if (socks != null && probePort in 1..65535) {
            config.inbounds.add(socks.copy(tag = HEALTH_TAG, port = probePort, listen = "127.0.0.1"))
            config.routing.rules.add(0, V2rayConfig.RoutingBean.RulesBean(inboundTag = listOf(HEALTH_TAG), outboundTag = AppConfig.TAG_PROXY))
        }
        // Match DNS to the effective family admitted by Android's VPN builder.
        config.dns = (config.dns ?: V2rayConfig.DnsBean()).copy(queryStrategy = if(ipv6Enabled) "UseIP" else "UseIPv4")
        // A denied TLS flow must never receive a plaintext HTTP response.
        config.outbounds.filter { it.protocol=="blackhole" }.forEach { it.settings?.response?.type="none" }
        val apps = listOf("socks", "http", "tun")
        // Keep the IP the phone actually connected to. Replacing it with SNI
        // makes CIDR routing resolve it a second time (possibly to another CDN).
        config.inbounds.filter { it.tag in apps }.forEach { it.sniffing?.routeOnly=true }
        val push=split.pushNotifications ?: PushNotificationSettings()
        if(push.enabled) config.inbounds.filter { it.tag in apps }.forEach {
            val sniff=it.sniffing
            it.sniffing=V2rayConfig.InboundBean.SniffingBean(true,ArrayList((sniff?.destOverride.orEmpty()+listOf("tls")).distinct()),
                metadataOnly=false,routeOnly=true)
        }
        if (split.mode == SplitTunnelSettings.DISABLED && !push.enabled) return
        val selected = if (split.mode == SplitTunnelSettings.BYPASS) AppConfig.TAG_DIRECT else AppConfig.TAG_PROXY
        val fallback = if (split.mode == SplitTunnelSettings.BYPASS) AppConfig.TAG_PROXY else AppConfig.TAG_DIRECT
        val rules = arrayListOf<V2rayConfig.RoutingBean.RulesBean>()
        if (config.outbounds.any { it.tag == "dns-out" }) {
            rules += V2rayConfig.RoutingBean.RulesBean(inboundTag = apps, port = "53", outboundTag = "dns-out")
        }
        if(!ipv6Enabled) rules += V2rayConfig.RoutingBean.RulesBean(inboundTag = apps, ip = listOf("::/0"), outboundTag = AppConfig.TAG_BLOCKED)
        if(push.enabled) {
            // Never capture local services just because they use an FCM port.
            rules += V2rayConfig.RoutingBean.RulesBean(inboundTag=apps, ip=listOf("10.0.0.0/8","172.16.0.0/12","192.168.0.0/16","169.254.0.0/16","127.0.0.0/8","fc00::/7","fe80::/10","::1"), outboundTag=AppConfig.TAG_DIRECT)
            rules += PushNotificationRouting.rules(push,apps)
        }
        if(split.mode == SplitTunnelSettings.DISABLED) {
            config.routing.rules.addAll(0,rules)
            PushNotificationRouting.applyDns(config,push)
            return
        }
        // Manually selected domains can match SNI without replacing the IP.
        if (split.domains.isNotEmpty()) rules += V2rayConfig.RoutingBean.RulesBean(inboundTag = apps, domain = split.domains, outboundTag = selected)
        rules += V2rayConfig.RoutingBean.RulesBean(inboundTag = apps,
            ip = listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "127.0.0.0/8"), outboundTag = AppConfig.TAG_DIRECT)
        // Xray combines domain and IP fields with AND, not OR.
        if (split.networks.isNotEmpty()) rules += V2rayConfig.RoutingBean.RulesBean(inboundTag = apps, ip = split.networks, outboundTag = selected)
        rules += V2rayConfig.RoutingBean.RulesBean(inboundTag = apps, network = "tcp,udp", outboundTag = fallback)
        config.routing.rules.addAll(0, rules)
        config.routing.domainStrategy = "AsIs"
        val dns = config.dns
        val remote = dns?.servers.orEmpty().ifEmpty { listOf(if(managedDns) "tcp://8.8.8.8" else "tcp://1.1.1.1") }
        val servers = arrayListOf<Any>()
        // Matching names select the DNS path, never override packet CIDRs.
        val directDomains = split.dnsDomains.takeIf { split.mode == SplitTunnelSettings.BYPASS && it.isNotEmpty() }
        val proxyDomains = split.dnsDomains.takeIf { split.mode == SplitTunnelSettings.TUNNEL && it.isNotEmpty() }
        val local = V2rayConfig.DnsBean.ServersBean(address=directDnsEndpoint.substringBeforeLast(':',directDnsEndpoint),
            port=directDnsEndpoint.substringAfterLast(':',"").toIntOrNull(),
            domains=directDomains, tag=DIRECT_DNS_TAG,
            skipFallback=split.mode == SplitTunnelSettings.BYPASS, timeoutMs=3000)
        if(split.mode == SplitTunnelSettings.TUNNEL) servers += local
        remote.forEach { raw ->
            val server = when(raw) {
                is V2rayConfig.DnsBean.ServersBean -> raw
                else -> V2rayConfig.DnsBean.ServersBean(address=raw.toString())
            }
            // All three transports carry TCP. In particular, DNSTT cannot carry
            // UDP-over-SOCKS. Also accept staged values from an older process.
            val address = server.address.let {
                when {
                    it.startsWith("udp://") -> "tcp://"+it.removePrefix("udp://")
                    "://" !in it && it != "localhost" -> "tcp://$it"+(server.port?.let { port -> ":$port" } ?: "")
                    else -> it
                }
            }
            servers += server.copy(address=address, port=null, domains=proxyDomains,
                tag=PROXY_DNS_TAG, skipFallback=false, timeoutMs=4000)
        }
        if(split.mode == SplitTunnelSettings.BYPASS) servers += local
        config.dns = (dns ?: V2rayConfig.DnsBean()).copy(servers=servers,
            enableParallelQuery=false, disableFallbackIfMatch=true)
        config.routing.rules.addAll(0, listOf(
            V2rayConfig.RoutingBean.RulesBean(inboundTag=listOf(DIRECT_DNS_TAG), outboundTag=AppConfig.TAG_DIRECT),
            V2rayConfig.RoutingBean.RulesBean(inboundTag=listOf(PROXY_DNS_TAG), outboundTag=AppConfig.TAG_PROXY),
        ))
        PushNotificationRouting.applyDns(config,push)
    }
}
