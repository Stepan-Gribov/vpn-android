package com.v2ray.ang.fmt

import android.util.Base64
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.v2ray.ang.dto.entities.ProfileItem
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.zip.InflaterInputStream

/** Imports self-contained AmneziaVPN vpn:// shares without storing their other server credentials. */
object AmneziaVpnLinkFmt {
    private const val SCHEME = "vpn://"
    private const val MAX_CONFIG_BYTES = 2 * 1024 * 1024
    private val AWG_KEYS = listOf(
        "Jc", "Jmin", "Jmax", "S1", "S2", "S3", "S4",
        "H1", "H2", "H3", "H4", "I1", "I2", "I3", "I4", "I5",
        "HeaderProtectionKey", "ContentPaddingAddition", "RekeyAfterTime",
        "RekeyTimeout", "RejectAfterTime", "KeepaliveTimeout",
        "MaxHandshakeAttempts", "RandomTrailers", "DisableCookies",
    )

    fun parse(link: String): ProfileItem? = runCatching {
        val trimmed = link.trim()
        if (!trimmed.startsWith(SCHEME, ignoreCase = true)) return null
        val encoded = trimmed.substring(SCHEME.length)
        if (encoded.isEmpty() || encoded.length > MAX_CONFIG_BYTES * 2) return null
        val payload = Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val json = unpack(payload) ?: return null
        parseJson(json)
    }.getOrNull()

    internal fun unpack(payload: ByteArray): String? {
        if (payload.size > MAX_CONFIG_BYTES) return null
        if (payload.firstOrNull()?.toInt() == '{'.code) {
            return String(payload, StandardCharsets.UTF_8)
        }
        if (payload.size < 6) return null
        // Qt qCompress prepends a four-byte big-endian uncompressed length to zlib data.
        val expected = ByteBuffer.wrap(payload, 0, 4).order(ByteOrder.BIG_ENDIAN).int
        if (expected <= 0 || expected > MAX_CONFIG_BYTES) return null
        val output = ByteArrayOutputStream(expected)
        InflaterInputStream(ByteArrayInputStream(payload, 4, payload.size - 4)).use { input ->
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_CONFIG_BYTES) return null
                output.write(buffer, 0, count)
            }
        }
        if (output.size() != expected) return null
        return output.toString(StandardCharsets.UTF_8.name())
    }

    internal fun parseJson(json: String): ProfileItem? = runCatching {
        val root = JsonParser.parseString(json).asJsonObject
        val containers = root.get("containers")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        val awgContainers = containers.mapNotNull { it.takeIf { item -> item.isJsonObject }?.asJsonObject }
            .filter { container ->
                container.string("container")?.startsWith("amnezia-awg", ignoreCase = true) == true ||
                    container.objectValue("awg") != null || container.objectValue("awg2") != null
            }
        val selected = awgContainers.firstOrNull {
            it.string("container") == root.string("defaultContainer")
        } ?: awgContainers.lastOrNull() ?: return null
        val awg = selected.objectValue("awg") ?: selected.objectValue("awg2") ?: return null
        val lastConfig = awg.objectValue("last_config")
            ?: awg.string("last_config")?.let { JsonParser.parseString(it).asJsonObject }
            ?: return null

        val rawConfig = lastConfig.string("config").orEmpty()
        val lines = if (rawConfig.isBlank()) mutableListOf("[Interface]", "", "[Peer]")
            else rawConfig.replace("\r\n", "\n").lines().toMutableList()

        val primaryDns = root.string("dns1") ?: lastConfig.string("dns1")
        val secondaryDns = root.string("dns2") ?: lastConfig.string("dns2")
        if ((primaryDns == null && lines.any { it.contains("\$PRIMARY_DNS") }) ||
            (secondaryDns == null && lines.any { it.contains("\$SECONDARY_DNS") })) return null
        for (index in lines.indices) {
            lines[index] = lines[index]
                .replace("\$PRIMARY_DNS", primaryDns.orEmpty())
                .replace("\$SECONDARY_DNS", secondaryDns.orEmpty())
        }
        putIfMissing(lines, "Interface", "PrivateKey", lastConfig.string("client_priv_key"))
        putIfMissing(lines, "Interface", "Address", lastConfig.string("client_ip"))
        if (primaryDns != null || secondaryDns != null) {
            putIfMissing(lines, "Interface", "DNS", listOfNotNull(primaryDns, secondaryDns).joinToString(", "))
        }
        putValue(lines, "Interface", "MTU", lastConfig.string("mtu"))
        AWG_KEYS.forEach { key ->
            putValue(lines, "Interface", key, lastConfig.string(key) ?: awg.string(key))
        }

        putIfMissing(lines, "Peer", "PublicKey", lastConfig.string("server_pub_key"))
        putIfMissing(lines, "Peer", "PresharedKey", lastConfig.string("psk_key"))
        val allowedIps = lastConfig.get("allowed_ips")?.let { value ->
            if (value.isJsonArray) value.asJsonArray.mapNotNull { it.takeIf { v -> v.isJsonPrimitive }?.asString }
                .joinToString(", ")
            else if (value.isJsonPrimitive) value.asString else null
        }
        putIfMissing(lines, "Peer", "AllowedIPs", allowedIps)
        putIfMissing(lines, "Peer", "PersistentKeepalive", lastConfig.string("persistent_keep_alive"))
        val host = lastConfig.string("hostName") ?: root.string("hostName")
        val port = lastConfig.string("port") ?: awg.string("port")
        if (host != null && port != null) {
            val endpointHost = if (host.contains(':') && !host.startsWith('[')) "[$host]" else host
            putIfMissing(lines, "Peer", "Endpoint", "$endpointHost:$port")
        }

        val name = root.string("description") ?: root.string("name")
        AmneziaWgFmt.parseConfig(lines.joinToString("\n"), name)
    }.getOrNull()

    private fun putIfMissing(lines: MutableList<String>, section: String, key: String, value: String?) =
        put(lines, section, key, value, overwrite = false)

    private fun putValue(lines: MutableList<String>, section: String, key: String, value: String?) =
        put(lines, section, key, value, overwrite = true)

    private fun put(lines: MutableList<String>, section: String, key: String, value: String?, overwrite: Boolean) {
        if (value.isNullOrBlank()) return
        val sectionStart = lines.indexOfFirst { it.trim().equals("[$section]", ignoreCase = true) }
        if (sectionStart < 0) return
        val sectionEnd = (sectionStart + 1 until lines.size).firstOrNull {
            lines[it].trim().startsWith('[')
        } ?: lines.size
        val existing = (sectionStart + 1 until sectionEnd).firstOrNull {
            lines[it].substringBefore('=', "").trim().equals(key, ignoreCase = true)
        }
        if (existing != null) {
            if (overwrite) lines[existing] = "$key = $value"
        } else {
            lines.add(sectionEnd, "$key = $value")
        }
    }

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf(String::isNotEmpty)

    private fun JsonObject.objectValue(key: String): JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject
}
