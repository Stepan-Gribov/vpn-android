package com.v2ray.ang.simple

import com.google.gson.JsonParser
import java.net.IDN
import java.net.InetAddress
import java.net.URI

/** The two policies deliberately keep independent lists, including when disabled. */
data class SplitTunnelEntry(val address: String, val networks: List<String> = emptyList(), val isNetworkGroup: Boolean = false) {
    val id: String get() = (if (isNetworkGroup) "group:" else "") + address
    val isIP: Boolean get() = !isNetworkGroup && SplitTunnelAddress.isIP(address)
}

data class SplitTunnelSettings(
    val mode: String = DISABLED,
    val bypassEntries: List<SplitTunnelEntry> = emptyList(),
    val tunnelEntries: List<SplitTunnelEntry> = emptyList(),
    val pushNotifications: PushNotificationSettings? = null,
) {
    val activeEntries: List<SplitTunnelEntry> get() = entries(mode)
    fun entries(policy: String): List<SplitTunnelEntry> = when (policy) {
        BYPASS -> bypassEntries
        TUNNEL -> tunnelEntries
        else -> emptyList()
    }
    fun withEntries(policy: String, entries: List<SplitTunnelEntry>): SplitTunnelSettings = when (policy) {
        BYPASS -> copy(bypassEntries = entries)
        TUNNEL -> copy(tunnelEntries = entries)
        else -> this
    }
    val domains: List<String> get() = activeEntries.filter { !it.isIP && !it.isNetworkGroup && it.networks.isEmpty() }.map { "domain:${it.address}" }
    // JSON names guide DNS only; packets are classified by the imported CIDRs.
    val dnsDomains: List<String> get() = activeEntries.filter { !it.isIP && !it.isNetworkGroup }.map { "domain:${it.address}" }
    val networks: List<String> get() = activeEntries.flatMap { (if (it.isIP) listOf(it.address) else emptyList()) + it.networks }.distinct().sorted()
    fun validated(): SplitTunnelSettings {
        require(mode in MODES) { "Неизвестный режим сплит-туннеля." }
        return copy(bypassEntries = SplitTunnelImport.normalized(bypassEntries), tunnelEntries = SplitTunnelImport.normalized(tunnelEntries))
    }
    companion object {
        const val DISABLED = "disabled"
        const val BYPASS = "bypassSelected"
        const val TUNNEL = "tunnelSelected"
        val MODES = listOf(DISABLED, BYPASS, TUNNEL)
        fun title(mode: String) = when (mode) {
            BYPASS -> "Выбранные — напрямую"
            TUNNEL -> "Выбранные — через VPN"
            else -> "Сплит-туннель выключен"
        }
        fun detail(mode: String) = when (mode) {
            BYPASS -> "Адреса из списка идут без VPN. Всё остальное — через туннель."
            TUNNEL -> "Только адреса из списка идут через туннель. Всё остальное — напрямую."
            else -> "Весь трафик идёт через выбранный туннель. Оба списка сохранены."
        }
    }
}

object SplitTunnelAddress {
    // Never call a name resolver while validating user input.
    private fun ipBytes(host: String): ByteArray? {
        if (':' in host) {
            if (!Regex("[0-9a-fA-F:.]+").matches(host)) return null
            return runCatching { InetAddress.getByName(host).address }.getOrNull()
        }
        val pieces = host.split('.')
        if (pieces.size != 4 || pieces.any { !Regex("0|[1-9][0-9]{0,2}").matches(it) || it.toInt() !in 0..255 }) return null
        return pieces.map { it.toInt().toByte() }.toByteArray()
    }
    fun isIP(value: String) = ipBytes(value.substringBefore('/')) != null
    fun normalize(raw: String): String {
        fun invalid(): Nothing = throw IllegalArgumentException("Неверный адрес: ${raw.take(100)}. Укажите домен, IP или CIDR, например 192.168.1.0/24.")
        var value = raw.trim().lowercase()
        if ("://" in value) {
            val url = runCatching { URI(value) }.getOrNull() ?: invalid()
            if (url.scheme !in listOf("http", "https") || url.rawUserInfo != null) invalid()
            value = url.host ?: runCatching { java.net.URL(value).host }.getOrNull() ?: invalid()
        }
        value = value.removeSurrounding("[", "]")
        val parts = value.split('/')
        if (parts.size > 2 || value.isEmpty()) invalid()
        val bytes = ipBytes(parts[0])
        if (bytes == null) {
            if (parts.size != 1 || ':' in value || value.any(Char::isWhitespace)) invalid()
            val host = runCatching { IDN.toASCII(value.removePrefix("*.").trimEnd('.'), IDN.USE_STD3_ASCII_RULES).lowercase() }.getOrNull() ?: invalid()
            if (host.length > 253 || host.all { it.isDigit() || it == '.' }) invalid()
            val labels = host.split('.')
            if (labels.size < 2 || labels.any { it.length !in 1..63 || !Regex("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?").matches(it) }) invalid()
            return host
        }
        val prefix = if (parts.size == 2) {
            if (!Regex("[0-9]+").matches(parts[1])) invalid()
            parts[1].toIntOrNull()?.takeIf { it in 0..bytes.size * 8 } ?: invalid()
        } else null
        if (prefix != null) bytes.indices.forEach { i ->
            val bits = (prefix - i * 8).coerceIn(0, 8)
            bytes[i] = (bytes[i].toInt() and (if (bits == 0) 0 else (255 shl (8 - bits)))).toByte()
        }
        val host = if (bytes.size == 4) bytes.joinToString(".") { (it.toInt() and 255).toString() } else ipv6(bytes)
        return host + (prefix?.let { "/$it" } ?: "")
    }
    private fun ipv6(bytes: ByteArray): String {
        val words = (0 until 8).map { ((bytes[it * 2].toInt() and 255) shl 8) or (bytes[it * 2 + 1].toInt() and 255) }
        var start = -1; var length = 1; var i = 0
        while (i < 8) {
            if (words[i] != 0) { i++; continue }
            val begin = i
            while (i < 8 && words[i] == 0) i++
            if (i - begin > length) { start = begin; length = i - begin }
        }
        if (start < 0) return words.joinToString(":") { it.toString(16) }
        return words.take(start).joinToString(":") { it.toString(16) } + "::" + words.drop(start + length).joinToString(":") { it.toString(16) }
    }
}

object SplitTunnelImport {
    const val MAXIMUM_SIZE = 5 * 1024 * 1024
    fun json(text: String): List<SplitTunnelEntry> {
        require(text.toByteArray(Charsets.UTF_8).size <= MAXIMUM_SIZE) { "Файл слишком большой. Максимум — 5 МБ." }
        val root = runCatching { JsonParser.parseString(text) }.getOrNull() ?: error("Не удалось прочитать JSON списка адресов.")
        fun strings(value: com.google.gson.JsonElement): List<String> {
            require(value.isJsonArray && value.asJsonArray.all { it.isJsonPrimitive && it.asJsonPrimitive.isString }) { "Список адресов должен содержать только строки." }
            return value.asJsonArray.map { it.asString }
        }
        val entries = when {
            root.isJsonArray -> strings(root).map { SplitTunnelEntry(it) }
            root.isJsonObject -> root.asJsonObject.entrySet().map { (key, value) ->
                val networks=strings(value)
                val group=networks.isNotEmpty() && runCatching { SplitTunnelAddress.normalize(key) }.isFailure
                SplitTunnelEntry(key, networks, group)
            }
            else -> error("Нужен JSON вида {\"example.com\": [\"203.0.113.0/24\"]} или массив адресов.")
        }
        require(entries.isNotEmpty()) { "В JSON нет адресов." }
        return normalized(entries)
    }
    fun manual(text: String): List<SplitTunnelEntry> {
        val entries = text.split(Regex("[\\s,;]+")).filter { it.isNotEmpty() }.map { SplitTunnelEntry(it) }
        require(entries.isNotEmpty()) { "Введите хотя бы один адрес." }
        return normalized(entries)
    }
    fun normalized(entries: List<SplitTunnelEntry>): List<SplitTunnelEntry> {
        require(entries.size <= 20_000 && entries.sumOf { it.networks.size.toLong() } <= 100_000) { "В списке слишком много адресов." }
        val groups = linkedMapOf<String, SplitTunnelEntry>()
        for (entry in entries) {
            val address = if (entry.isNetworkGroup) entry.address.trim().lowercase().also { name ->
                require(name.isNotEmpty() && name.length <= 100 && entry.networks.isNotEmpty() && name.all { it.isLetterOrDigit() || it in " ._-@" }) { "Неверное название группы IP-сетей." }
            } else SplitTunnelAddress.normalize(entry.address)
            val id = (if (entry.isNetworkGroup) "group:" else "") + address
            val networks = (groups[id]?.networks.orEmpty() + entry.networks.map { raw ->
                SplitTunnelAddress.normalize(raw).also { require(SplitTunnelAddress.isIP(it)) { "Сети для $address должны содержать только IP или CIDR." } }
            }).distinct().sorted()
            groups[id] = SplitTunnelEntry(address, networks, entry.isNetworkGroup)
        }
        return groups.values.sortedBy { it.id }
    }
}
