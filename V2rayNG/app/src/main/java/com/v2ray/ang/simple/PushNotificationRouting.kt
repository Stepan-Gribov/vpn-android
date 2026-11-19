package com.v2ray.ang.simple

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.V2rayConfig

data class PushNotificationSettings(val apple: Boolean = false, val google: Boolean = false) {
    val enabled: Boolean get() = apple || google
}

/** Catalogue 1: device-side APNs and FCM, independent of imported address lists. */
object PushNotificationRouting {
    const val CATALOG_VERSION = 1
    private const val DNS_TAG = "simple-dns-push"
    val appleNetworks = listOf("17.249.0.0/16", "17.252.0.0/16", "17.57.144.0/22", "17.188.128.0/18", "17.188.20.0/23",
        "2620:149:a44::/48", "2403:300:a42::/48", "2403:300:a51::/48", "2a01:b740:a42::/48")
    val googleHosts = listOf("mtalk.google.com", "mtalk4.google.com", "mtalk-staging.google.com", "mtalk-dev.google.com") +
        (1..8).map { "alt$it-mtalk.google.com" } +
        listOf("android.apis.google.com", "device-provisioning.googleapis.com", "firebaseinstallations.googleapis.com")
    fun domains(push: PushNotificationSettings): List<String> =
        (if(push.apple) listOf("domain:push.apple.com") else emptyList()) +
            (if(push.google) googleHosts.map { "full:$it" } else emptyList())
    fun rules(push: PushNotificationSettings, apps: List<String>, proxy: String = AppConfig.TAG_PROXY): List<V2rayConfig.RoutingBean.RulesBean> {
        fun rule(port: String, domains: List<String>? = null, networks: List<String>? = null) =
            V2rayConfig.RoutingBean.RulesBean(inboundTag=apps, network="tcp", port=port,
                domain=domains, ip=networks, outboundTag=proxy)
        val result=ArrayList<V2rayConfig.RoutingBean.RulesBean>()
        if(push.apple) {
            result+=rule("443,5223",domains=listOf("domain:push.apple.com"))
            result+=rule("443,5223",networks=appleNetworks)
        }
        if(push.google) {
            // Cached IP / absent SNI on the dedicated FCM ports must still work.
            result+=rule("5228-5230")
            result+=rule("443",domains=googleHosts.map { "full:$it" })
        }
        return result
    }
    fun applyDns(config: V2rayConfig, push: PushNotificationSettings) {
        if(!push.enabled) return
        val dns=config.dns ?: V2rayConfig.DnsBean()
        val remote=dns.servers.orEmpty().map { raw ->
            if(raw is V2rayConfig.DnsBean.ServersBean) raw else V2rayConfig.DnsBean.ServersBean(address=raw.toString())
        }.filter { it.tag!="simple-dns-direct" && it.address!="fakedns" }
            .ifEmpty { listOf(V2rayConfig.DnsBean.ServersBean(address="tcp://1.1.1.1")) }
        val pushServers=remote.map { server ->
            val address=server.address.let { when {
                it.startsWith("udp://") -> "tcp://"+it.removePrefix("udp://")
                "://" !in it && it!="localhost" -> "tcp://"+(if(SplitTunnelAddress.isIP(it) && ':' in it) "[$it]" else it)+(server.port?.let { port -> ":$port" } ?: "")
                else -> it
            } }
            server.copy(address=address,port=null,domains=domains(push),tag=DNS_TAG,skipFallback=true)
        }
        config.dns=dns.copy(servers=ArrayList<Any>().apply { addAll(pushServers); addAll(dns.servers.orEmpty()) },disableFallbackIfMatch=true)
        config.routing.rules.add(0,V2rayConfig.RoutingBean.RulesBean(inboundTag=listOf(DNS_TAG),outboundTag=AppConfig.TAG_PROXY))
    }
}
