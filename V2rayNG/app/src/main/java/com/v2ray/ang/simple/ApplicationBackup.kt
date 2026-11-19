package com.v2ray.ang.simple

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import java.time.Instant

/** Only portable user data. No VPN permission, live connection or daemon state. */
data class ApplicationBackup(
    val format: String = FORMAT,
    val schemaVersion: Int = 1,
    val createdAt: String = Instant.now().toString(),
    val profiles: List<SimpleConfiguration>,
    val mode: String,
    val autoCaptcha: Boolean,
    val autoRecovery: Boolean,
    val savedDNSServers: List<String>,
    val splitTunneling: SplitTunnelSettings,
    val selectedProfileID: String? = null,
    val turnCheckInterval: Int = 30,
    val dnsServers: List<String> = emptyList(),
    val dnsTunnel: PanelDns? = null,
    val ipv6Enabled: Boolean = false,
    val excludedApplications: List<String> = emptyList(),
    val autoConnectOnRestart: Boolean = false,
    val amneziaSplitEnabled: Boolean = false,
    val fullTunnelMinutes: Int = TemporaryFullTunnelPolicy.DEFAULT_MINUTES,
    val turnSessionCount: Int = com.v2ray.ang.core.TurnSessionCount.DEFAULT,
) {
    fun validated(): ApplicationBackup {
        require(format == FORMAT && schemaVersion == 1) { "Версия формата резервной копии не поддерживается." }
        require(mode in listOf("amnezia", "turn", "dns")) { "Неверный режим приложения." }
        require(fullTunnelMinutes in TemporaryFullTunnelPolicy.durations) { "Неверный срок режима «Всё в туннель»." }
        require(turnCheckInterval in 15..300) { "Неверный интервал проверки TURN." }
        require(turnSessionCount in com.v2ray.ang.core.TurnSessionCount.choices) { "Неверное число каналов TURN." }
        require(profiles.size <= 1000 && savedDNSServers.size <= 1000) { "В копии слишком много профилей или DNS-серверов." }
        require(dnsServers.size<=100) { "В копии слишком много DNS-серверов приложения." }
        require(profiles.all { it.id.isNotBlank() && it.id.length <= 200 } && profiles.map { it.id }.distinct().size == profiles.size) { "В копии есть повторяющиеся или неверные идентификаторы профилей." }
        require(selectedProfileID == null || profiles.any { it.id == selectedProfileID }) { "Выбранный профиль отсутствует в копии." }
        return copy(excludedApplications=AppVpnExclusions.normalized(excludedApplications), profiles = profiles.map { it.validated() }, savedDNSServers = savedDNSServers.map {
            SimpleConfiguration.normalizeTunnelResolver(it)
        }.distinct(), splitTunneling = splitTunneling.validated(),dnsServers=dnsServers.map {
            SimpleConfiguration.normalizeEndpoint(it,53,ipOnly=true)
        }.distinct(),dnsTunnel=dnsTunnel?.validated())
    }
    fun encoded(): String = gson.toJson(validated().let { it.copy(profiles=it.profiles.map { p->p.copy(managed=null) }) }).also { require(it.toByteArray(Charsets.UTF_8).size <= MAXIMUM_SIZE) { "Резервная копия слишком большая. Максимум — 20 МБ." } }
    companion object {
        const val FORMAT = "TnDtun-android-backup"
        const val MAXIMUM_SIZE = 20 * 1024 * 1024
        private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
        fun decode(text: String): ApplicationBackup {
            require(text.toByteArray(Charsets.UTF_8).size <= MAXIMUM_SIZE) { "Резервная копия слишком большая. Максимум — 20 МБ." }
            return try {
                val root = JsonParser.parseString(text).asJsonObject
                val incomingFormat = root.get("format")?.asString
                require(incomingFormat in listOf(FORMAT, "SimpleBLT-backup", "TnDtun-backup")) { "Выберите JSON, созданный кнопкой «Экспортировать всё» в TnDtun." }
                if (incomingFormat == "TnDtun-backup") convertPortableProfiles(root)
                root.addProperty("format", FORMAT)
                if (!root.has("fullTunnelMinutes")) root.addProperty("fullTunnelMinutes", TemporaryFullTunnelPolicy.DEFAULT_MINUTES)
                require(root.get("fullTunnelMinutes").toString() in TemporaryFullTunnelPolicy.durations.map(Int::toString))
                if(root.has("amneziaSplitEnabled")) requireBoolean(root,"amneziaSplitEnabled")
                if (!root.has("turnCheckInterval")) root.addProperty("turnCheckInterval", 30)
                if (!root.has("turnSessionCount")) root.addProperty("turnSessionCount", com.v2ray.ang.core.TurnSessionCount.DEFAULT)
                require(root.get("turnSessionCount").toString() in com.v2ray.ang.core.TurnSessionCount.choices.map(Int::toString)) { "Неверное число каналов TURN." }
                if (!root.has("excludedApplications")) root.add("excludedApplications",JsonArray())
                require(root.get("excludedApplications")?.isJsonArray==true && root.getAsJsonArray("excludedApplications").all { it.isJsonPrimitive && it.asJsonPrimitive.isString })
                if (!root.has("dnsServers")) root.add("dnsServers",JsonArray())
                require(root.get("dnsServers")?.isJsonArray==true && root.getAsJsonArray("dnsServers").all { it.isJsonPrimitive && it.asJsonPrimitive.isString })
                root.get("dnsTunnel")?.takeUnless { it.isJsonNull }?.let { PanelContract.dns(PanelJson.obj(it)) }
                require(root.get("turnCheckInterval").toString().matches(Regex("[0-9]+")) && root.get("turnCheckInterval").asLong in 15L..300L) { "Неверный интервал проверки TURN." }
                require(root.get("schemaVersion")?.toString() == "1") { "Версия формата резервной копии не поддерживается." }
                requireString(root, "createdAt"); requireString(root, "mode")
                requireBoolean(root, "autoCaptcha"); requireBoolean(root, "autoRecovery")
                if(root.has("ipv6Enabled")) requireBoolean(root,"ipv6Enabled")
                if(root.has("autoConnectOnRestart")) requireBoolean(root,"autoConnectOnRestart")
                require(root.get("profiles")?.isJsonArray == true && root.get("savedDNSServers")?.isJsonArray == true)
                root.getAsJsonArray("profiles").forEach { raw ->
                    val profile = raw.asJsonObject
                    listOf("id", "mode", "callLink", "server", "vlessID", "wrapKey", "domain", "publicKey", "tunnelType", "resolver", "wifiResolver", "sshUsername", "sshPassword", "sshPrivateKey", "sshHostKey").forEach { requireString(profile, it) }
                    if (profile.get("mode").asString == "amnezia") requireString(profile, "amneziaKey")
                    requireBoolean(profile, "useWiFiResolver")
                    if (profile.has("autoDetectResolver")) requireBoolean(profile, "autoDetectResolver")
                    if (profile.has("savedResolvers")) require(profile.get("savedResolvers").isJsonArray && profile.getAsJsonArray("savedResolvers").all { it.isJsonPrimitive && it.asJsonPrimitive.isString })
                    require(profile.get("sessionCount")?.toString() in listOf("1", "2", "4", "8"))
                }
                root.getAsJsonArray("savedDNSServers").forEach { require(it.isJsonPrimitive && it.asJsonPrimitive.isString) }
                validateSplitJson(root.getAsJsonObject("splitTunneling"))
                root.get("selectedProfileID")?.let { require(it.isJsonNull || (it.isJsonPrimitive && it.asJsonPrimitive.isString)) }
                gson.fromJson(root, ApplicationBackup::class.java).validated()
            } catch (e: IllegalArgumentException) { throw e }
            catch (_: Exception) { error("Копия повреждена или содержит не все настройки. Текущие данные не изменены.") }
        }
        // Read the portable JSON schema as data; Android remains an independent implementation.
        private fun convertPortableProfiles(root: JsonObject) {
            val profiles = root.getAsJsonArray("profiles") ?: error("В копии отсутствуют профили.")
            val converted = JsonArray()
            profiles.forEach { raw ->
                val source = raw.asJsonObject
                val profile = JsonObject()
                listOf("id", "mode", "callLink", "server", "vlessID", "wrapKey").forEach { key ->
                    requireString(source, key); profile.add(key, source.get(key))
                }
                listOf("amneziaKey", "amneziaName", "amneziaProtocol").forEach { key ->
                    if (source.has(key)) { requireString(source, key); profile.add(key, source.get(key)) }
                }
                val dns = source.getAsJsonObject("dns") ?: error("В профиле отсутствуют настройки DNS.")
                for ((key, fallback) in mapOf("domain" to "", "publicKey" to "", "tunnelType" to "socks5", "resolver" to "1.1.1.1:53",
                    "wifiResolver" to "8.8.8.8", "sshUsername" to "", "sshPassword" to "", "sshPrivateKey" to "", "sshHostKey" to "")) {
                    if (dns.has(key)) { requireString(dns, key); profile.add(key, dns.get(key)) }
                    else profile.addProperty(key, fallback)
                }
                for ((key, fallback) in mapOf("useWiFiResolver" to false, "autoDetectResolver" to true)) {
                    if (dns.has(key)) { requireBoolean(dns, key); profile.add(key, dns.get(key)) }
                    else profile.addProperty(key, fallback)
                }
                profile.add("sessionCount", dns.get("sessionCount") ?: com.google.gson.JsonPrimitive(4))
                profile.add("savedResolvers", dns.get("savedResolvers") ?: JsonArray())
                converted.add(profile)
            }
            root.add("profiles", converted)
        }
        internal fun validateSplitJson(root: JsonObject) {
            requireString(root, "mode")
            if(root.has("pushNotifications") && !root.get("pushNotifications").isJsonNull) {
                val push=PanelJson.obj(root.get("pushNotifications"))
                require(push.keySet()==setOf("apple","google")) { "Неверные настройки push." }
                requireBoolean(push,"apple"); requireBoolean(push,"google")
            }
            listOf("bypassEntries", "tunnelEntries").forEach { field ->
                require(root.get(field)?.isJsonArray == true)
                root.getAsJsonArray(field).forEach { raw ->
                    val entry = raw.asJsonObject
                    requireString(entry, "address"); requireBoolean(entry, "isNetworkGroup")
                    require(entry.get("networks")?.isJsonArray == true)
                    entry.getAsJsonArray("networks").forEach { require(it.isJsonPrimitive && it.asJsonPrimitive.isString) }
                }
            }
        }
        private fun requireString(root: JsonObject, key: String) { require(root.get(key)?.let { it.isJsonPrimitive && it.asJsonPrimitive.isString } == true) { "В копии отсутствует текстовое поле $key." } }
        private fun requireBoolean(root: JsonObject, key: String) { require(root.get(key)?.let { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean } == true) { "В копии отсутствует переключатель $key." } }
    }
}
