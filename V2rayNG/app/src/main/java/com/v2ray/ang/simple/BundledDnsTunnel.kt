package com.v2ray.ang.simple

import android.content.Context

/** Public server parameters, shared with bootstrap; no client keys or admin access. */
internal object BundledDnsTunnel {
    const val ID="tndtun-bundled-dns"
    fun isBuiltIn(profile: SimpleConfiguration) = profile.id==ID
    fun profile(context: Context): SimpleConfiguration {
        val dns=PanelManager.bootstrap(context).dns
        return SimpleConfiguration(id=ID,mode="dns",domain=dns.domain,publicKey=dns.publicKey,
            resolver="8.8.8.8:53",sessionCount=dns.sessionCount,autoDetectResolver=true,
            savedResolvers=listOf("8.8.8.8:53","1.1.1.1:53"),useWiFiResolver=true,
            wifiResolver="https://dns.google/dns-query").validated()
    }
}
