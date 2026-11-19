package com.v2ray.ang.simple

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.util.UUID

const val DEFAULT_WRAP_KEY = ""

data class SimpleConfiguration(
    val id: String = UUID.randomUUID().toString(),
    var mode: String = "turn",
    var callLink: String = "",
    var server: String = "",
    var vlessID: String = "",
    var wrapKey: String = DEFAULT_WRAP_KEY,
    var domain: String = "",
    var publicKey: String = "",
    var tunnelType: String = "socks5",
    var sessionCount: Int = 4,
    var resolver: String = "1.1.1.1:53",
    var autoDetectResolver: Boolean = true,
    var savedResolvers: List<String> = emptyList(),
    var useWiFiResolver: Boolean = false,
    var wifiResolver: String = "8.8.8.8",
    var sshUsername: String = "",
    var sshPassword: String = "",
    var sshPrivateKey: String = "",
    var sshHostKey: String = "",
    var amneziaKey: String = "",
    var amneziaName: String = "",
    var amneziaProtocol: String = "",
    val managed: ManagedProfile? = null,
) {
    override fun toString() = "SimpleConfiguration(id=$id, mode=$mode)"
    val title: String get() = if(id==BundledDnsTunnel.ID) "Встроенный DNS" else managed?.title ?: if (mode == "amnezia") amneziaName else if (mode == "turn") server else domain
    val detail: String get() = managed?.location?.takeIf { it.isNotBlank() } ?: if (mode == "amnezia") amneziaProtocol else if (mode == "turn") "VK Calls" else "DNSTT · ${tunnelType.uppercase()}"
    fun validated(forConnection: Boolean = false): SimpleConfiguration = copy().apply {
        require(mode in listOf("amnezia", "turn", "dns")) { "Выберите Amnezia, TURN или DNS." }
        if (mode == "turn") {
            callLink = callLink.trim()
            val uri = runCatching { URI(callLink) }.getOrNull()
            require(uri != null && uri.scheme.equals("https", true) &&
                uri.host?.lowercase() in setOf("vk.com", "www.vk.com", "m.vk.com", "vk.ru", "www.vk.ru", "m.vk.ru") &&
                uri.path.startsWith("/call/join/") && uri.path.removePrefix("/call/join/").substringBefore('/').isNotBlank()) {
                "Введите ссылку звонка VK: https://vk.com/call/join/…"
            }
            server = normalizeEndpoint(server, 56010)
            vlessID = vlessID.trim()
            if (forConnection || vlessID.isNotEmpty()) require(UUID_REGEX.matches(vlessID)) {
                "Для подключения нужен UUID VLESS вашего сервера."
            }
            wrapKey = wrapKey.trim().lowercase()
            require(wrapKey.isEmpty() || HEX_KEY.matches(wrapKey)) { "Ключ транспорта должен содержать 64 шестнадцатеричных символа или быть пустым." }
        } else if (mode == "amnezia") {
            val connection = AmneziaImporter.parse(amneziaKey)
            amneziaKey = amneziaKey.trim()
            amneziaName = connection.title
            amneziaProtocol = connection.protocol
        } else {
            domain = domain.trim().lowercase().trimEnd('.')
            require(domain.length <= 253 && domain.contains('.') && domain.split('.').all {
                it.length in 1..63 && Regex("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?").matches(it)
            }) { "Введите домен DNS-туннеля, например dns.example.invalid." }
            publicKey = publicKey.trim().lowercase()
            require(HEX_KEY.matches(publicKey)) { "Публичный ключ должен содержать 64 шестнадцатеричных символа." }
            require(tunnelType in listOf("socks5", "ssh")) { "Выберите режим SOCKS5 или SSH." }
            require(sessionCount in listOf(1, 2, 4, 8)) { "Выберите 1, 2, 4 или 8 параллельных потоков." }
            resolver = normalizeTunnelResolver(resolver)
            savedResolvers = (listOf(resolver) + savedResolvers).map { normalizeTunnelResolver(it) }.distinct()
            if (useWiFiResolver) wifiResolver = normalizeTunnelResolver(wifiResolver)
            if (tunnelType == "ssh") {
                sshUsername = sshUsername.trim()
                require(sshUsername.isNotEmpty()) { "Укажите пользователя SSH." }
                require(sshPassword.isNotEmpty() || sshPrivateKey.isNotEmpty()) { "Укажите пароль или приватный ключ SSH." }
                require(Regex("SHA256:[A-Za-z0-9+/]{43}=?").matches(sshHostKey.trim())) { "Укажите SHA256-отпечаток SSH-сервера." }
                sshHostKey = sshHostKey.trim().trimEnd('=')
            }
        }
    }
    companion object {
        private val HEX_KEY = Regex("[0-9a-f]{64}")
        private val UUID_REGEX = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        fun normalizeTunnelResolver(raw: String): String {
            if(!raw.trim().startsWith("https://",true)) return normalizeEndpoint(raw,53,true)
            val uri=runCatching { URI(raw.trim()) }.getOrNull()
            require(uri!=null && uri.scheme.equals("https",true) && !uri.host.isNullOrBlank() && uri.rawUserInfo==null &&
                uri.rawQuery==null && uri.rawFragment==null && (uri.port==-1 || uri.port in 1..65535)) { "Проверьте HTTPS-адрес DNS." }
            return "https://${uri.rawAuthority}${uri.rawPath.orEmpty().ifEmpty { "/dns-query" }}"
        }
        fun normalizeEndpoint(raw: String, defaultPort: Int, ipOnly: Boolean = false): String {
            val value = raw.trim()
            require(value.isNotEmpty() && value.none { it.isWhitespace() }) { "Укажите адрес сервера." }
            val endpoint = when {
                value.startsWith('[') -> if (value.endsWith(']')) "$value:$defaultPort" else value
                value.count { it == ':' } > 1 -> "[$value]:$defaultPort"
                ':' !in value -> "$value:$defaultPort"
                else -> value
            }
            val uri = runCatching { URI("udp://$endpoint") }.getOrNull()
            val host = uri?.host?.removeSurrounding("[", "]").orEmpty()
            require(uri != null && host.isNotEmpty() && uri.port in 1..65535 && uri.rawUserInfo == null &&
                uri.path.isNullOrEmpty() && uri.query == null && uri.fragment == null) { "Проверьте адрес и порт сервера." }
            val ipv4 = host.split('.').let { parts -> parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 && it.all(Char::isDigit) } }
            require(!ipOnly || ipv4 || host.contains(':')) { "Для DNS нужен IP-адрес, например 1.1.1.1:53." }
            return if (host.contains(':')) "[$host]:${uri.port}" else "$host:${uri.port}"
        }
    }
}

object ConfigurationJSONImporter {
    fun apply(text: String, original: SimpleConfiguration): SimpleConfiguration {
        if (original.mode == "amnezia") {
            val key = runCatching { JsonParser.parseString(text).asJsonObject.get("amneziaKey")?.asString }.getOrNull() ?: text
            return original.copy(amneziaKey = key).validated(forConnection = true)
        }
        require(text.toByteArray(Charsets.UTF_8).size <= 65_536) { "JSON слишком большой. Максимальный размер — 64 КБ." }
        val root = runCatching { JsonParser.parseString(text) }.getOrNull()
        require(root?.isJsonObject == true) { "Скопируйте один корректный JSON-объект с настройками." }
        val top = root.asJsonObject
        fun field(obj: JsonObject, vararg keys: String, required: Boolean = false, trim: Boolean = true): String? {
            val values = keys.filter { obj.has(it) }.map { key ->
                val raw = obj.get(key)
                require(raw.isJsonPrimitive && raw.asJsonPrimitive.isString) { "Поле $key должно быть строкой." }
                raw.asString.let { if (trim) it.trim() else it }
            }
            require(values.distinct().size <= 1) { "В JSON указаны разные значения поля ${keys[0]}." }
            val value = values.firstOrNull()
            require(!required || !value.isNullOrEmpty()) { "В JSON не хватает поля ${keys[0]}." }
            return value
        }
        field(top, "mode")?.lowercase()?.let {
            require(it in listOf("amnezia", "turn", "dns")) { "Поле mode должно быть turn или dns." }
            require(it == original.mode) { "Это настройки другого режима. Откройте его и вставьте JSON там." }
        }
        val result = original.copy()
        if (original.mode == "turn") {
            result.callLink = field(top, "call_link", "callLink", "vk_link", required = true)!!
            result.server = field(top, "server", "peer_addr", required = true)!!
            result.vlessID = field(top, "vless_id", "vlessID", "uuid", required = true)!!
            result.wrapKey = field(top, "wrap_key", "wrapKey") ?: ""
        } else {
            val nested = top.get("dns") ?: top.get("managed_dns")
            require(nested == null || nested.isJsonObject) { "Поле dns должно содержать JSON-объект." }
            val fields = nested?.asJsonObject ?: top
            result.domain = field(fields, "domain", required = true)!!
            result.publicKey = field(fields, "public_key", "publicKey", required = true)!!
            result.tunnelType = (field(fields, "tunnel_type", "tunnelType") ?: "socks5").lowercase()
            field(fields, "resolver")?.let { result.resolver = it }
            for ((key, apply) in listOf<Pair<String, (Boolean) -> Unit>>(
                "autoDetectResolver" to { result.autoDetectResolver = it }, "useWiFiResolver" to { result.useWiFiResolver = it },
            )) {
                fields.get(key)?.let { raw ->
                    require(raw.isJsonPrimitive && raw.asJsonPrimitive.isBoolean) { "Поле $key должно быть переключателем." }
                    apply(raw.asBoolean)
                }
            }
            field(fields, "wifiResolver")?.let { result.wifiResolver = it }
            fields.get("sessionCount")?.let { raw ->
                require(raw.toString() in listOf("1", "2", "4", "8")) { "Выберите 1, 2, 4 или 8 потоков." }
                result.sessionCount = raw.asInt
            }
            result.sshUsername = field(fields, "ssh_username", "sshUsername") ?: ""
            result.sshPassword = field(fields, "ssh_password", "sshPassword", trim = false) ?: ""
            result.sshPrivateKey = field(fields, "ssh_private_key", "sshPrivateKey", trim = false) ?: ""
            result.sshHostKey = field(fields, "ssh_host_key", "sshHostKey") ?: ""
        }
        return result.validated(forConnection = true)
    }
}

object TrafficDisplay {
    fun bytes(value: Double): String {
        val n = value.coerceAtLeast(0.0)
        return when {
            n < 1_000 -> "${n.toLong()} B"
            n < 1_000_000 -> String.format(java.util.Locale.US, "%.1f KB", n / 1_000)
            n < 1_000_000_000 -> String.format(java.util.Locale.US, "%.1f MB", n / 1_000_000)
            else -> String.format(java.util.Locale.US, "%.2f GB", n / 1_000_000_000)
        }
    }
    fun speed(value: Double) = bytes(value) + "/s"
}
