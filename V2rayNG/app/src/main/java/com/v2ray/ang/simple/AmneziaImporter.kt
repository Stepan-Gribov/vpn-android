package com.v2ray.ang.simple

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.Base64
import java.util.zip.InflaterInputStream

/** Client keys only. Imported DNS/routes never override this app's split settings. */
object AmneziaImporter {
    const val MAXIMUM_SIZE = 2 * 1024 * 1024
    const val SOCKS_PORT = 9004
    data class Runtime(val addresses: List<String>, val dns: List<String>, val mtu: Int, val uapi: String)
    data class Connection(val title: String, val protocol: String, val runtime: Runtime? = null, val outbound: JsonObject? = null)
    private val mappings = mapOf(
        "jc" to "jc", "jmin" to "jmin", "jmax" to "jmax",
        "s1" to "s1", "s2" to "s2", "s3" to "s3", "s4" to "s4",
        "h1" to "h1", "h2" to "h2", "h3" to "h3", "h4" to "h4",
        "i1" to "i1", "i2" to "i2", "i3" to "i3", "i4" to "i4", "i5" to "i5",
        "contentpaddingaddition" to "content_padding_addition", "rekeyaftertime" to "rekey_after_time",
        "rekeytimeout" to "rekey_timeout", "rejectaftertime" to "reject_after_time",
        "keepalivetimeout" to "keepalive_timeout", "maxhandshakeattempts" to "max_handshake_attempts",
        "randomtrailers" to "random_trailers", "disablecookies" to "disable_cookies",
    )
    fun parse(input: String, depth: Int = 0): Connection {
        require(depth < 4) { "Слишком много вложенных конфигураций Amnezia." }
        val text = input.trim()
        require(text.isNotEmpty() && text.toByteArray().size <= MAXIMUM_SIZE) { "Вставьте полный ключ Amnezia (до 2 МБ)." }
        if (text.startsWith("[Interface]", true)) return wireguard(text, null)
        val json = if (text.startsWith("vpn://", true)) {
            val data = runCatching { Base64.getUrlDecoder().decode(text.substring(6)) }.getOrNull()
                ?: error("Ключ Amnezia повреждён. Скопируйте его целиком.")
            if (data.firstOrNull() == '{'.code.toByte()) String(data, Charsets.UTF_8) else unpack(data)
        } else text
        val root = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull()
            ?: error("Вставьте ключ vpn:// или конфигурацию AmneziaWG.")
        root.string("key")?.let { return parse(it, depth + 1) }
        val title = root.string("description")
        val containers = root.get("containers")?.takeIf { it.isJsonArray }?.asJsonArray
        if (containers != null) {
            val items = containers.map { it.asJsonObject }
            val container = items.firstOrNull { it.string("container") == root.string("defaultContainer") }
                ?: items.firstOrNull() ?: error("В ключе нет конфигурации подключения.")
            for (name in listOf("awg", "awg2", "wireguard", "xray")) {
                val section = container.get(name)?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                val raw = section.get("last_config") ?: error("Это ключ управления сервером. В Amnezia экспортируйте ключ подключения через «Поделиться».")
                if (raw.isJsonPrimitive && raw.asString.trim().startsWith("[Interface]", true)) return wireguard(raw.asString, title)
                val config = runCatching { if (raw.isJsonObject) raw.asJsonObject else JsonParser.parseString(raw.asString).asJsonObject }.getOrNull()
                    ?: error("В ключе отсутствует клиентская конфигурация.")
                config.string("config")?.let {
                    return if (name == "xray") xray(JsonParser.parseString(it).asJsonObject, title) else wireguard(it, title)
                }
                if (name == "xray") return xray(config, title)
                error("Экспортируйте полную клиентскую конфигурацию AmneziaWG через «Поделиться».")
            }
            error("Протокол ключа не поддерживается. Нужен AmneziaWG, WireGuard или Xray.")
        }
        if (root.has("outbounds")) return xray(root, title)
        error("Для этого ключа требуется сервис подписки Amnezia. Экспортируйте конфигурацию подключения.")
    }
    private fun unpack(data: ByteArray): String {
        require(data.size >= 6) { "Ключ Amnezia неполный." }
        val expected = java.nio.ByteBuffer.wrap(data, 0, 4).int
        require(expected in 1..MAXIMUM_SIZE) { "Ключ Amnezia слишком большой или повреждён." }
        val output = ByteArrayOutputStream(expected)
        InflaterInputStream(ByteArrayInputStream(data, 4, data.size - 4)).use { source ->
            val buffer = ByteArray(4096)
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                require(output.size() + count <= expected) { "Ключ Amnezia повреждён." }
                output.write(buffer, 0, count)
            }
        }
        require(output.size() == expected) { "Ключ Amnezia повреждён." }
        return output.toString(Charsets.UTF_8.name())
    }
    private fun xray(root: JsonObject, title: String?): Connection {
        val outbound = root.getAsJsonArray("outbounds")?.map { it.asJsonObject }?.firstOrNull {
            it.string("protocol") in listOf("vless", "vmess", "trojan", "shadowsocks", "wireguard")
        }?.deepCopy() ?: error("В ключе нет поддерживаемого подключения Xray.")
        require(!outbound.has("proxySettings")) { "Связки прокси в ключе Amnezia не поддерживаются." }
        outbound.addProperty("tag", "proxy")
        return Connection(title?.trim()?.take(100)?.takeIf { it.isNotEmpty() } ?: "Amnezia Xray", "Xray", outbound = outbound)
    }
    private fun wireguard(text: String, title: String?): Connection {
        val fields = linkedMapOf<String, String>(); val peer = linkedMapOf<String, String>()
        var section = ""; var peers = 0
        text.lineSequence().forEach { raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) return@forEach
            if (line.startsWith('[')) {
                section = line.lowercase()
                require(section in listOf("[interface]", "[peer]")) { "Неподдерживаемый раздел Amnezia: $line" }
                if (section == "[peer]") peers++
            } else {
                require('=' in line && section.isNotEmpty()) { "Конфигурация Amnezia неполная." }
                val name = line.substringBefore('=').trim().lowercase()
                val value = line.substringAfter('=').trim()
                val map = if (section == "[interface]") fields else peer
                require(map[name] == null || map[name] == value) { "Разные значения параметра Amnezia $name." }
                map[name] = value
            }
        }
        require(peers == 1) { "Экспортируйте конфигурацию с одним сервером." }
        val endpoint = SimpleConfiguration.normalizeEndpoint(peer["endpoint"].orEmpty(), 51820)
        val addresses = list(fields["address"]).map {
            val parts = it.split('/')
            require(parts.size == 2 && validIP(parts[0]) && parts[1].toIntOrNull() in 0..(if (':' in parts[0]) 128 else 32)) { "Неверный адрес интерфейса Amnezia." }
            parts[0]
        }
        require(addresses.isNotEmpty() && addresses.size <= 8) { "Не указан адрес интерфейса Amnezia." }
        val dns = list(fields["dns"]).filter(::validIP).ifEmpty { listOf("1.1.1.1") }
        val mtu = fields["mtu"]?.toIntOrNull() ?: if (fields.containsKey("mtu")) 0 else 1280
        require(mtu in 576..1500 && dns.size <= 8) { "Неверный MTU или DNS Amnezia." }
        val ignored = setOf("address", "dns", "privatekey", "mtu", "listenport", "headerprotectionkey")
        require(fields.keys.all { it in mappings || it in ignored }) { "В конфигурации есть неподдерживаемые параметры Amnezia." }
        require(peer.keys.all { it in setOf("publickey", "presharedkey", "endpoint", "allowedips", "persistentkeepalive") }) { "Неподдерживаемый параметр сервера Amnezia." }
        val uapi = StringBuilder("private_key=${key(fields["privatekey"], "PrivateKey")}\n")
        mappings.toSortedMap().forEach { (field, name) -> fields[field]?.let { original ->
            var value = original
            if (field in setOf("randomtrailers", "disablecookies")) value = when (value.lowercase()) {
                "on", "yes", "true", "1" -> "true"
                "off", "no", "false", "0" -> "false"
                else -> error("Неверный переключатель Amnezia $field.")
            }
            require(value.none { it == '\n' || it == '\r' || it == '\u0000' }) { "Неверный параметр Amnezia." }
            uapi.append("$name=$value\n")
        } }
        fields["headerprotectionkey"]?.let { uapi.append("header_protection_key=${key(it, "HeaderProtectionKey")}\n") }
        uapi.append("replace_peers=true\npublic_key=${key(peer["publickey"], "PublicKey")}\n")
        peer["presharedkey"]?.let { uapi.append("preshared_key=${key(it, "PresharedKey")}\n") }
        val keepalive = peer["persistentkeepalive"] ?: "25"
        require(Regex("[0-9]+(-[0-9]+)?").matches(keepalive)) { "Неверный keepalive Amnezia." }
        uapi.append("endpoint=$endpoint\nreplace_allowed_ips=true\nallowed_ip=0.0.0.0/0\nallowed_ip=::/0\npersistent_keepalive_interval=$keepalive\n")
        val awg = fields.keys.any { it in mappings || it == "headerprotectionkey" }
        return Connection(title?.trim()?.take(100)?.takeIf { it.isNotEmpty() } ?: endpoint,
            if (awg) "AmneziaWG" else "WireGuard", Runtime(addresses, dns, mtu, uapi.toString()))
    }
    private fun key(value: String?, name: String): String {
        val bytes = runCatching { Base64.getDecoder().decode(value) }.getOrNull()
        require(bytes != null && bytes.size == 32 && bytes.any { it.toInt() != 0 }) { "Неверный или отсутствующий ключ $name." }
        return bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun validIP(raw: String): Boolean = '/' !in raw && SplitTunnelAddress.isIP(raw)
    private fun list(value: String?) = value.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
    private fun JsonObject.string(key: String) = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
}
