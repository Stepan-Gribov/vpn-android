package com.v2ray.ang.simple

import com.google.gson.GsonBuilder
import com.v2ray.ang.dto.V2rayConfig
import com.v2ray.ang.handler.MmkvManager
import java.security.MessageDigest

/** Public diagnostics contain counts/hashes only, never destinations or credentials. */
data class RoutingPolicySnapshot(val mode: String, val ipv6Enabled: Boolean, val ipv4Count: Int,
    val ipv6Count: Int, val networkSHA256: String, val policySHA256: String, val sourceRevision: Long = 0, val excludedAppCount: Int = 0,
    val pushApple: Boolean = false, val pushGoogle: Boolean = false, val pushCatalogVersion: Int = 0) {
    companion object {
        private val gson=GsonBuilder().disableHtmlEscaping().create()
        private fun hash(value: Any)=MessageDigest.getInstance("SHA-256").digest(gson.toJson(value).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        fun create(split: SplitTunnelSettings,ipv6: Boolean,revision: Long=0,excludedApplications: List<String> = emptyList()): RoutingPolicySnapshot {
            val networks=split.networks.distinct().sorted()
            val apps=AppVpnExclusions.normalized(excludedApplications)
            val policy=listOf(split.mode,ipv6,networks,split.domains.sorted(),split.dnsDomains.sorted())
            val appPolicy=if(apps.isEmpty()) policy else policy+listOf(apps)
            val push=split.pushNotifications ?: PushNotificationSettings()
            val effectivePolicy=if(push.enabled) appPolicy+listOf("push",PushNotificationRouting.CATALOG_VERSION,push.apple,push.google) else appPolicy
            return RoutingPolicySnapshot(split.mode,ipv6,networks.count { ':' !in it },networks.count { ':' in it },hash(networks),
                hash(effectivePolicy),revision,apps.size,push.apple,push.google,PushNotificationRouting.CATALOG_VERSION)
        }
        fun capture(config: V2rayConfig,split: SplitTunnelSettings,ipv6: Boolean,revision: Long,excludedApplications: List<String> = emptyList()): RoutingPolicySnapshot {
            if(split.mode!=SplitTunnelSettings.DISABLED && split.networks.isNotEmpty()) check(config.routing.rules.any {
                it.inboundTag==listOf("socks","http","tun") && it.ip==split.networks &&
                    it.outboundTag==if(split.mode==SplitTunnelSettings.TUNNEL) "proxy" else "direct"
            }) { "Runtime split networks differ from staged policy" }
            check(config.dns?.queryStrategy==if(ipv6) "UseIP" else "UseIPv4")
            val push=split.pushNotifications ?: PushNotificationSettings()
            if(push.apple) check(config.routing.rules.any { it.ip==PushNotificationRouting.appleNetworks && it.port=="443,5223" && it.outboundTag=="proxy" })
            if(push.google) check(config.routing.rules.any { it.port=="5228-5230" && it.network=="tcp" && it.outboundTag=="proxy" })
            if(push.enabled) check(config.routing.rules.any { it.inboundTag==listOf("simple-dns-push") && it.outboundTag=="proxy" })
            return create(split,ipv6,revision,excludedApplications)
        }
    }
}

internal object RoutingPolicyState {
    private const val KEY="simple.routing.applied.v1"
    private val gson=GsonBuilder().create()
    fun recordApplied(snapshot: RoutingPolicySnapshot) {
        MmkvManager.encodeSettings(KEY,gson.toJson(snapshot))
        android.util.Log.i("TnDtun-Routing","applied mode=${snapshot.mode} ipv6Enabled=${snapshot.ipv6Enabled} "+
            "ipv4=${snapshot.ipv4Count} ipv6=${snapshot.ipv6Count} networkSHA256=${snapshot.networkSHA256} "+
            "policySHA256=${snapshot.policySHA256} sourceRevision=${snapshot.sourceRevision} excludedApps=${snapshot.excludedAppCount} "+
            "pushApple=${snapshot.pushApple} pushGoogle=${snapshot.pushGoogle} pushCatalog=${snapshot.pushCatalogVersion}")
    }
    fun read(): RoutingPolicySnapshot? = runCatching {
        gson.fromJson(MmkvManager.decodeSettingsString(KEY,""),RoutingPolicySnapshot::class.java)
    }.getOrNull()
    fun pending(store: SimpleStore): Boolean = DaemonConnectionState.hasLiveService() && read()?.let {
        val id=MmkvManager.decodeSettingsString(SimpleRuntime.ACTIVE_ID)
        val profile=store.configurations.firstOrNull { p->p.id==id }
        val mode=profile?.mode ?: store.mode
        val full=TemporaryFullTunnel.active(id)
        val saved=profile?.let(store::connectionSettings)?.splitTunneling ?: store.splitTunneling
        val split=TemporaryFullTunnelPolicy.split(saved,mode,store.amneziaSplitEnabled,full)
        val apps=TemporaryFullTunnelPolicy.applications(store.excludedApplications,mode,store.amneziaSplitEnabled,full)
        it.policySHA256!=RoutingPolicySnapshot.create(split,store.ipv6Enabled,excludedApplications=apps).policySHA256
    }==true
}
