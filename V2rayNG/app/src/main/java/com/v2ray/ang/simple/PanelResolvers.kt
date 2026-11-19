package com.v2ray.ang.simple

/** Keep the configured provider; offer its HTTPS DNS endpoint if UDP is filtered. */
internal object PanelResolvers {
    fun candidates(dns: PanelDns,discovered: List<String>,wifi: Boolean): List<String> {
        val preferred=(if(dns.useWiFiResolver && wifi) listOf(dns.wifiResolver) else emptyList())+
            if(dns.autoDetectResolver) discovered else emptyList()
        val doh=(listOf(dns.resolver)+dns.savedResolvers).mapNotNull {
            when(it) {
                "1.1.1.1:53","1.0.0.1:53" -> "https://cloudflare-dns.com/dns-query"
                "8.8.8.8:53","8.8.4.4:53" -> "https://dns.google/dns-query"
                else -> null
            }
        }
        // Cloudflare returned SERVFAIL for this tunnel on the test carrier.
        // Prefer the explicitly configured Google fallback on Wi-Fi; carrier
        // DNS remains first on LTE, where whitelist reachability can differ.
        val first=if(wifi && !dns.useWiFiResolver) {
            if(dns.autoDetectResolver) doh.sortedBy { if(it=="https://dns.google/dns-query") 0 else 1 } else doh
        } else emptyList()
        return (first+preferred.take(1)+doh+dns.resolver+dns.savedResolvers+preferred.drop(1)).distinct().take(4)
    }
}
