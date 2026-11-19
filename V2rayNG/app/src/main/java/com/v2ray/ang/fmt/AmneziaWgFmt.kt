package com.v2ray.ang.fmt

import android.util.Base64
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.Utils
import org.amnezia.awg.config.BadConfigException
import org.amnezia.awg.config.Config
import java.net.URI
import java.nio.charset.StandardCharsets

/** Parses and serializes complete AmneziaWG configuration files using the official parser. */
object AmneziaWgFmt : FmtBase() {
    private val obfuscationKeys = setOf(
        "jc", "jmin", "jmax",
        "s1", "s2", "s3", "s4",
        "h1", "h2", "h3", "h4",
        "i1", "i2", "i3", "i4", "i5",
        "headerprotectionkey", "contentpaddingaddition",
        "rekeyaftertime", "rekeytimeout", "rejectaftertime",
        "keepalivetimeout", "maxhandshakeattempts", "randomtrailers", "disablecookies",
    )

    fun hasAmneziaSettings(raw: String): Boolean {
        var inInterface = false
        return raw.lineSequence().any { rawLine ->
            val line = rawLine.substringBefore('#').trim()
            if (line.startsWith("[")) {
                inInterface = line.equals("[Interface]", ignoreCase = true)
                false
            } else if (inInterface) {
                line.substringBefore('=', "").trim().lowercase() in obfuscationKeys
            } else {
                false
            }
        }
    }

    @Throws(BadConfigException::class)
    fun parseConfig(raw: String): Config = Config.parse(raw.reader().buffered())

    @Throws(BadConfigException::class)
    fun parseConfig(raw: String, name: String?): ProfileItem {
        val parsed = parseConfig(raw)
        val interfaceConfig = parsed.getInterface()
        val endpoint = parsed.getPeers().asSequence()
            .mapNotNull { it.getEndpoint().orElse(null) }
            .firstOrNull()
            ?: throw IllegalArgumentException("AmneziaWG config must contain a peer Endpoint")
        require(interfaceConfig.getAddresses().isNotEmpty()) {
            "AmneziaWG config must contain an Interface Address"
        }
        require(parsed.getPeers().isNotEmpty()) {
            "AmneziaWG config must contain at least one Peer"
        }

        return ProfileItem.create(EConfigType.AMNEZIAWG).apply {
            remarks = name?.trim()?.removeSuffix(".conf")?.takeIf(String::isNotBlank)
                ?: "AmneziaWG · ${endpoint.getHost()}"
            server = endpoint.getHost()
            serverPort = endpoint.getPort().toString()
            localAddress = interfaceConfig.getAddresses().joinToString(",")
            mtu = interfaceConfig.getMtu().orElse(1280)
            amneziaWgConfig = parsed.toAwgQuickString()
        }
    }

    fun parse(str: String): ProfileItem? = runCatching {
        val uri = URI(Utils.fixIllegalUrl(str))
        val params = getQueryParam(uri)
        params["config"]?.let { encoded ->
            val raw = String(
                Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
                StandardCharsets.UTF_8,
            )
            parseConfig(raw, params["name"])
        }
    }.getOrNull()

    fun toUri(profile: ProfileItem): String {
        val raw = profile.amneziaWgConfig ?: return ""
        val encoded = Base64.encodeToString(
            raw.toByteArray(StandardCharsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        val name = Utils.encodeURIComponent(profile.remarks)
        return "import?config=$encoded&name=$name"
    }
}
