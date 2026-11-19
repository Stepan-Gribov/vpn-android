package com.v2ray.ang.core

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import java.net.URI

/** A separate loopback endpoint makes transport selection belong to the row. */
object DnsTunnelProfiles {
    const val PORT = "9001"

    fun canSupplyIdentity(profile: ProfileItem): Boolean =
        profile.configType == EConfigType.VLESS && !profile.password.isNullOrBlank()

    /** Create a plain local connection without inheriting REALITY, Vision or XHTTP settings. */
    fun connection(source: ProfileItem, label: String, subscriptionId: String = ""): ProfileItem {
        require(canSupplyIdentity(source) && label.isNotBlank())
        return ProfileItem(configType = EConfigType.VLESS, subscriptionId = subscriptionId,
            remarks = label.trim(), server = "127.0.0.1", serverPort = PORT,
            password = source.password, method = "none", security = "none", network = "tcp")
    }
    fun isDns(profile: ProfileItem?): Boolean = local(profile, PORT)
    fun isSource(profile: ProfileItem?): Boolean = local(profile, "9000") &&
        profile?.security.orEmpty() in setOf("", "none") &&
        profile?.network.orEmpty() in setOf("", "tcp", "raw")

    private fun local(profile: ProfileItem?, port: String): Boolean =
        profile?.configType == EConfigType.VLESS && profile.serverPort == port &&
            profile.server?.lowercase() in setOf("127.0.0.1", "localhost")

    fun isKindlySubscription(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.host == URI(AppConfig.KINDLYVPN_PUBLIC_ORIGIN).host &&
            uri.userInfo == null && uri.port in setOf(-1, 443)
    }.getOrDefault(false)

    fun adding(profiles: List<ProfileItem>, available: Boolean, label: String): List<ProfileItem> {
        if (!available) return profiles
        val credentials = profiles.filter(::isDns).map { it.password }.toMutableSet()
        val added = profiles.filter(::isSource).mapNotNull { source ->
            if (source.password.isNullOrBlank() || !credentials.add(source.password)) null
            else connection(source, label, source.subscriptionId)
        }
        return profiles + added
    }
}
