package com.v2ray.ang.simple

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.*
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.MmkvManager

/** Stage a complete row only after the previous VPN has stopped. The daemon owns recovery. */
object SimpleRuntime {
    const val MSG_TRAFFIC = 901
    const val MSG_REAL_DELAY = 902
    const val ACTIVE_ID = "simple.active.id"
    const val TOTAL = "simple.traffic.total"
    const val WRAP_KEY = "simple.turn.wrapKey"
    const val WIFI_ENABLED = "simple.dns.wifi.enabled"
    const val WIFI_RESOLVER = "simple.dns.wifi.resolver"
    const val SSH_HOST_KEY = "simple.dns.ssh.hostKey"
    const val SPLIT_SETTINGS = "simple.split.settings.v1"
    const val PROBE_PORT = "simple.health.port"
    const val STAGED_GUID = "simpleblt_active_profile"

    fun clearPreparedConfiguration(selectedProfileID: String?) {
        MmkvManager.removeServer(STAGED_GUID)
        MmkvManager.encodeSettings(AmneziaRuntime.SETTINGS, "")
        MmkvManager.encodeSettings(AmneziaRuntime.OUTBOUND, "")
        MmkvManager.encodeSettings(WRAP_KEY, "")
        MmkvManager.encodeSettings(WIFI_ENABLED, false)
        MmkvManager.encodeSettings(WIFI_RESOLVER, "")
        MmkvManager.encodeSettings(SSH_HOST_KEY, "")
        MmkvManager.encodeSettings(SPLIT_SETTINGS, "{}")
        MmkvManager.encodeSettings(ACTIVE_ID, selectedProfileID)
    }

    fun refreshPreparedConfiguration(context: Context) {
        if(MmkvManager.getSelectServer()!=STAGED_GUID) return
        val store=SimpleStore(context)
        val id=MmkvManager.decodeSettingsString(ACTIVE_ID)
        val profile=store.configurations.firstOrNull { it.id==id } ?: error("Подключение больше не назначено.")
        TemporaryFullTunnel.beforeConnectionStart(profile.id)
        stage(profile,store)
    }

    fun applyAutomation(store: SimpleStore) = applyAutomation(PanelSettings(store.autoCaptcha,store.autoRecovery,store.turnCheckInterval,store.dnsServers,store.dnsTunnel,store.splitTunneling))
    private fun applyAutomation(settings: PanelSettings) {
        MmkvManager.encodeSettings(AppConfig.PREF_VK_TURN_AUTO_CAPTCHA, settings.autoCaptcha)
        MmkvManager.encodeSettings(AppConfig.PREF_AUTO_RECONNECT_ON_DROP, settings.autoRecovery)
        MmkvManager.encodeSettings(AppConfig.PREF_AUTO_RECONNECT_CHECK_INTERVAL, settings.turnCheckInterval.toString())
        MmkvManager.encodeSettings(AppConfig.PREF_AUTO_RECONNECT_MAX_RETRIES, "5")
        MmkvManager.encodeSettings(AppConfig.PREF_SPEED_ENABLED, true)
    }
    fun stage(value: SimpleConfiguration, store: SimpleStore, publishAllowed: () -> Boolean = { true }) {
        store.reload()
        val candidate=store.canonicalConfiguration(value)
        val current=if(candidate.managed==null) candidate else store.configurations.firstOrNull {
            it.id==candidate.id && it.managed?.subscriptionID==candidate.managed.subscriptionID
        } ?: error("Подключение больше не назначено.")
        store.requireAccess(current)
        var config = current.validated(forConnection = true)
        val settings=store.connectionSettings(config)
        val full=TemporaryFullTunnel.active(config.id)
        val effectiveSplit=TemporaryFullTunnelPolicy.split(settings.splitTunneling,config.mode,store.amneziaSplitEnabled,full)
        val effectiveApps=TemporaryFullTunnelPolicy.applications(store.excludedApplications,config.mode,store.amneziaSplitEnabled,full)
        if (config.mode == "dns" && config.autoDetectResolver) {
            val context = com.v2ray.ang.AngApplication.application
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val wifi = physicalWiFi ?: manager.getNetworkCapabilities(manager.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            if (!(config.useWiFiResolver && wifi != false)) {
                ManagedDnsTunnelResolverDiscovery.discover(context).firstOrNull()?.let { detected ->
                    config = config.copy(resolver = detected, savedResolvers = (config.savedResolvers + detected).distinct()).validated(forConnection = true)
                }
            }
        }
        StagedPublication.publish(publishAllowed) {
            PanelAccess.stage(config,store)
            applyAutomation(settings)
            if(settings.dnsServers.isNotEmpty()) {
                MmkvManager.encodeSettings(AppConfig.PREF_REMOTE_DNS,settings.dnsServers.joinToString(",") { "tcp://$it" })
                MmkvManager.encodeSettings(AppConfig.PREF_VPN_DNS,settings.dnsServers.joinToString(",") { java.net.URI("udp://$it").host.removeSurrounding("[","]") })
            } else {
                MmkvManager.encodeSettings(AppConfig.PREF_REMOTE_DNS,AppConfig.DNS_PROXY)
                MmkvManager.encodeSettings(AppConfig.PREF_VPN_DNS,AppConfig.DNS_VPN)
            }
            MmkvManager.encodeSettings(AppConfig.PREF_MODE, "VPN")
            MmkvManager.encodeSettings(AppConfig.PREF_USE_HEV_TUNNEL, true)
            MmkvManager.encodeSettings(AppConfig.PREF_VPN_BYPASS_LAN, false)
            MmkvManager.encodeSettings(AppConfig.PREF_IPV6_ENABLED, store.ipv6Enabled)
            MmkvManager.encodeSettings("simple.routing.source.revision",store.subscriptionFor(config)?.revision ?: 0L)
            MmkvManager.encodeSettings(AppVpnExclusions.STAGED_KEY, effectiveApps.toMutableSet())
            MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY, false)
            MmkvManager.encodeSettings(AppConfig.PREF_MUX_ENABLED, false)
            // Do not inherit geodata-dependent presets from the larger client.
            MmkvManager.encodeRoutingRulesets(mutableListOf())
            MmkvManager.encodeSettings(SPLIT_SETTINGS, com.google.gson.Gson().toJson(effectiveSplit))
            MmkvManager.encodeSettings(PROBE_PORT, com.v2ray.ang.util.Utils.findRandomFreePort())
            MmkvManager.encodeSettings(AppConfig.PREF_LOCAL_DNS_ENABLED, true)
            MmkvManager.encodeSettings(AmneziaRuntime.SETTINGS, "")
            MmkvManager.encodeSettings(AmneziaRuntime.OUTBOUND, "")
            MmkvManager.encodeSettings(WIFI_ENABLED, false)
            MmkvManager.encodeSettings(SSH_HOST_KEY, "")
            val profile: ProfileItem
            if (config.mode == "turn") {
                MmkvManager.encodeSettings(WRAP_KEY, config.wrapKey)
                VkTurnManager.saveSettings(config.callLink, config.server, store.turnSessionCount, autoCaptcha = settings.autoCaptcha)
                profile = ProfileItem.create(EConfigType.VLESS).apply {
                    server = AppConfig.VK_TURN_LOCAL_ADDRESS
                    serverPort = AppConfig.VK_TURN_LOCAL_PORT
                    password = config.vlessID
                    method = "none" // VLESS encryption is separate from stream TLS/security.
                    network = "tcp"
                    security = "none"
                    remarks = config.title
                }
            } else if (config.mode == "amnezia") {
                val connection = AmneziaImporter.parse(config.amneziaKey)
                connection.runtime?.let { MmkvManager.encodeSettings(AmneziaRuntime.SETTINGS, com.google.gson.Gson().toJson(if(settings.dnsServers.isEmpty()) it else it.copy(dns=settings.dnsServers))) }
                connection.outbound?.let { MmkvManager.encodeSettings(AmneziaRuntime.OUTBOUND, it.toString()) }
                profile = ProfileItem.create(EConfigType.SOCKS).apply {
                    server = "127.0.0.1"; serverPort = AmneziaImporter.SOCKS_PORT.toString()
                    network = "tcp"; remarks = config.title
                }
            } else {
                MmkvManager.encodeSettings(WIFI_ENABLED, config.useWiFiResolver)
                MmkvManager.encodeSettings(WIFI_RESOLVER, config.wifiResolver)
                MmkvManager.encodeSettings(SSH_HOST_KEY, config.sshHostKey)
                ManagedDnsTunnelManager.saveSettings(ManagedDnsTunnelSettings(
                    name = config.domain, transport = ManagedDnsTunnelTransport.DNSTT,
                    tunnelType = ManagedDnsTunnelType.fromStorage(config.tunnelType),
                    domain = config.domain, resolver = config.resolver, publicKey = config.publicKey,
                    congestionControl = "dcubic", keepAliveInterval = 400, gsoEnabled = false,
                    automaticResolverCapture = false, sshUsername = config.sshUsername,
                    sshPassword = config.sshPassword, sshPrivateKey = config.sshPrivateKey,
                    sessionCount = config.sessionCount,
                ))
                profile = ProfileItem.create(EConfigType.SOCKS).apply {
                    server = AppConfig.DNS_TUNNEL_LOCAL_ADDRESS
                    serverPort = AppConfig.DNS_TUNNEL_LOCAL_PORT
                    network = "tcp"
                    remarks = config.title
                }
            }
            MmkvManager.encodeServerConfig(STAGED_GUID, profile)
            MmkvManager.setSelectServer(STAGED_GUID)
            MmkvManager.encodeSettings(ACTIVE_ID, config.id)
            store.selectProfile(config.id)
        }
    }

    @Volatile private var physicalWiFi: Boolean? = null
    fun onPhysicalNetworkChanged(wifi: Boolean) {
        val before = physicalWiFi
        physicalWiFi = wifi
        if (before != null && before != wifi &&
            MmkvManager.decodeSettingsBool(WIFI_ENABLED, false) &&
            ManagedDnsTunnelManager.isManagedDnsTunnelProfile(MmkvManager.decodeServerConfig(STAGED_GUID))) {
            CoreServiceManager.refreshManagedDnsNetwork()
        }
    }
    fun effectiveResolver(default: String): String {
        if (!MmkvManager.decodeSettingsBool(WIFI_ENABLED, false)) return default
        val context = com.v2ray.ang.AngApplication.application
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val isWiFi = physicalWiFi ?: cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ?: false
        val resolver=if (isWiFi) MmkvManager.decodeSettingsString(WIFI_RESOLVER, "8.8.8.8:53") ?: default else default
        // Keep Wi-Fi from silently using filtered UDP to a known public resolver.
        return if(isWiFi) when(resolver) {
            "8.8.8.8:53","8.8.4.4:53" -> "https://dns.google/dns-query"
            "1.1.1.1:53","1.0.0.1:53" -> "https://cloudflare-dns.com/dns-query"
            else -> resolver
        } else resolver
    }
}
