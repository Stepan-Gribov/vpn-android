package com.v2ray.ang.fmt

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.NetworkType
import com.v2ray.ang.extension.idnHost
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.extension.nullIfBlank
import com.v2ray.ang.util.Utils
import java.net.URI

/** Parses and exports Hysteria 2 subscription links for the bundled Xray core. */
object Hysteria2Fmt : FmtBase() {
    fun parse(str: String): ProfileItem {
        val config = ProfileItem.create(EConfigType.HYSTERIA2)
        val uri = URI(Utils.fixIllegalUrl(str))
        config.remarks = Utils.decodeURIComponent(uri.fragment.orEmpty()).ifEmpty { "Hysteria 2" }
        config.server = uri.idnHost
        config.serverPort = uri.port.toString()
        config.password = uri.userInfo?.let { Utils.decodeURIComponent(it) }.orEmpty()
        config.network = NetworkType.HYSTERIA.type
        config.security = AppConfig.TLS
        config.alpn = "h3"

        if (!uri.rawQuery.isNullOrEmpty()) {
            val params = getQueryParam(uri)
            getItemFormQuery(config, params)
            config.network = NetworkType.HYSTERIA.type
            config.security = params["security"]?.takeIf { it.equals(AppConfig.TLS, true) } ?: AppConfig.TLS
            config.alpn = params["alpn"]?.nullIfBlank() ?: "h3"
            config.obfsPassword = params["obfs-password"]
                ?.takeIf { params["obfs"].equals("salamander", true) }
            config.portHopping = params["mport"]?.nullIfBlank()
            config.portHoppingInterval = params["mportHopInt"]?.nullIfBlank()
            config.pinnedCA256 = params["pinSHA256"]?.nullIfBlank() ?: config.pinnedCA256
        }
        return config
    }

    fun toUri(config: ProfileItem): String {
        val params = hashMapOf<String, String>()
        config.security?.nullIfBlank()?.let { params["security"] = it }
        config.sni?.nullIfBlank()?.let { params["sni"] = it }
        config.alpn?.nullIfBlank()?.let { params["alpn"] = it }
        params["insecure"] = if (config.insecure == true) "1" else "0"

        if (config.obfsPassword.isNotNullEmpty()) {
            params["obfs"] = "salamander"
            params["obfs-password"] = config.obfsPassword.orEmpty()
        }
        config.portHopping?.nullIfBlank()?.let { params["mport"] = it }
        config.portHoppingInterval?.nullIfBlank()?.let { params["mportHopInt"] = it }
        (config.pinnedCA256?.nullIfBlank() ?: config.pinSHA256?.nullIfBlank())
            ?.let { params["pinSHA256"] = it }

        return toUri(config, config.password, params)
    }
}
