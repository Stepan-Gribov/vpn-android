package com.v2ray.ang.simple

import com.google.crypto.tink.subtle.Ed25519Verify
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.SecureRandom
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64

/** Wire DTOs are separate from backups. Secret-bearing types have redacted toString. */
class PanelTrust(val api: String, val keyID: String, val publicKey: String, val dns: PanelDns) {
    override fun toString() = "PanelTrust"
    fun sameSource(other: PanelTrust) = api == other.api && keyID == other.keyID && publicKey == other.publicKey
}
data class PanelDns(val domain: String, val publicKey: String, val resolver: String,
    val sessionCount: Int = 4, val autoDetectResolver: Boolean = true,
    val savedResolvers: List<String> = emptyList(), val useWiFiResolver: Boolean = false,
    val wifiResolver: String = "8.8.8.8:53") {
    fun validated(): PanelDns {
        val c = SimpleConfiguration(mode="dns",domain=domain,publicKey=publicKey,resolver=SimpleConfiguration.normalizeEndpoint(resolver,53,true),
            sessionCount=sessionCount,autoDetectResolver=autoDetectResolver,savedResolvers=savedResolvers.map { SimpleConfiguration.normalizeEndpoint(it,53,true) },
            useWiFiResolver=useWiFiResolver,wifiResolver=wifiResolver).validated()
        return copy(domain=c.domain,publicKey=c.publicKey,resolver=c.resolver,savedResolvers=c.savedResolvers,
            wifiResolver=SimpleConfiguration.normalizeEndpoint(wifiResolver,53,true))
    }
}
class ActivationInput(val code: String, val trust: PanelTrust) { override fun toString() = "ActivationInput" }
data class ManagedProfile(val subscriptionID: String, val inboundID: String, val nodeID: String,
    val generation: Long, val title: String, val location: String, val credentialScope: String = "device")
data class PanelSettings(val autoCaptcha: Boolean, val autoRecovery: Boolean, val turnCheckInterval: Int,
    val dnsServers: List<String>, val dnsTunnel: PanelDns?, val splitTunneling: SplitTunnelSettings)
class PanelPackage(val revision: Long, val nonce: String, val issuedAt: String, val clientID: String,
    val clientName: String, val deviceID: String, val status: String, val expiresAt: Long?,
    val syncAfterSeconds: Long, val profiles: List<SimpleConfiguration>, val settings: PanelSettings?) {
    override fun toString() = "PanelPackage"
}
class PanelSubscription(val id: String, val trust: PanelTrust, val deviceID: String, val token: String,
    val clientID: String, val clientName: String, val revision: Long, val status: String,
    val expiresAt: Long?, val syncAfterSeconds: Long, val lastSync: Long,
    val settings: PanelSettings?, val needsActivation: Boolean = false, val activationCode: String? = null) {
    fun accessAllowed(now: Long) = status == "active" && (expiresAt == null || now < expiresAt)
    fun statusText(now: Long): String = when {
        status == "revoked" -> "Доступ отозван"
        !accessAllowed(now) -> "Срок доступа истёк"
        needsActivation -> "Нужна повторная активация"
        else -> "Подписка активна"
    }
    override fun toString() = "PanelSubscription"
}
internal object PanelJson {
    fun parse(text: String): JsonElement {
        var depth=0; var quoted=false; var escape=false
        for(c in text) {
            if(quoted) {
                if(escape) escape=false else if(c=='\\') escape=true else if(c=='"') quoted=false
            } else when(c) {
                '"' -> quoted=true
                '{','[' -> { depth++; require(depth<=32) { "Слишком сложная структура ответа." } }
                '}',']' -> { depth--; require(depth>=0) }
            }
        }
        require(!quoted && depth==0) { "Неполный ответ сервиса." }
        val validator=com.google.gson.stream.JsonReader(java.io.StringReader(text)).apply { strictness=com.google.gson.Strictness.STRICT }
        fun consume() {
            when(validator.peek()) {
                com.google.gson.stream.JsonToken.BEGIN_OBJECT -> {
                    validator.beginObject(); val names=HashSet<String>()
                    while(validator.hasNext()) { require(names.add(validator.nextName())) { "Повторяющееся поле ответа." }; consume() }
                    validator.endObject()
                }
                com.google.gson.stream.JsonToken.BEGIN_ARRAY -> {
                    validator.beginArray(); while(validator.hasNext()) consume(); validator.endArray()
                }
                com.google.gson.stream.JsonToken.STRING,com.google.gson.stream.JsonToken.NUMBER -> validator.nextString()
                com.google.gson.stream.JsonToken.BOOLEAN -> validator.nextBoolean()
                com.google.gson.stream.JsonToken.NULL -> validator.nextNull()
                else -> error("Неверная структура ответа.")
            }
        }
        consume(); require(validator.peek()==com.google.gson.stream.JsonToken.END_DOCUMENT); validator.close()
        val reader=com.google.gson.stream.JsonReader(java.io.StringReader(text))
        reader.strictness=com.google.gson.Strictness.STRICT
        val result=JsonParser.parseReader(reader)
        require(reader.peek()==com.google.gson.stream.JsonToken.END_DOCUMENT)
        return result
    }
    fun obj(e: JsonElement?): JsonObject { require(e?.isJsonObject == true) { "Неверная структура пакета." }; return e.asJsonObject }
    fun str(o: JsonObject,k: String,max: Int=200,allowLines: Boolean=false): String {
        val e=o.get(k); require(e?.isJsonPrimitive == true && e.asJsonPrimitive.isString) { "Неверное поле $k." }
        return e.asString.also { require(it.length<=max && it.none { c->c.isISOControl() && !(allowLines && c in "\n\r\t") }) { "Неверное поле $k." } }
    }
    fun number(o: JsonObject,k: String): Long {
        val e=o.get(k); require(e?.isJsonPrimitive == true && e.asJsonPrimitive.isNumber && Regex("-?(0|[1-9][0-9]*)").matches(e.toString())) { "Неверное число $k." }
        return e.toString().toLongOrNull() ?: error("Неверное число $k.")
    }
    fun bool(o: JsonObject,k: String): Boolean {
        val e=o.get(k); require(e?.isJsonPrimitive == true && e.asJsonPrimitive.isBoolean) { "Неверный переключатель $k." }; return e.asBoolean
    }
    fun strings(o: JsonObject,k: String,max: Int): List<String> {
        val e=o.get(k); require(e?.isJsonArray == true && e.asJsonArray.size()<=max) { "Неверный список $k." }
        return e.asJsonArray.map { require(it.isJsonPrimitive && it.asJsonPrimitive.isString); it.asString }
    }
    fun uuid(o: JsonObject,k: String): String = str(o,k).also {
        require(Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}").matches(it)) { "Неверный идентификатор $k." }
    }
}
object PanelContract {
    const val ENVELOPE_LIMIT=12*1024*1024
    const val PAYLOAD_LIMIT=8*1024*1024
    fun nonce(): String = ByteArray(24).also { SecureRandom().nextBytes(it) }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    fun code(raw: String): String = raw.filterNot { it.isWhitespace() || it=='-' }.also {
        require(Regex("(?:[A-Za-z]{4}|[A-Za-z2-7]{26})").matches(it)) {
            "Введите 4 английские буквы (A–Z) или старый код из 26 символов (A–Z, 2–7)."
        }
    }.uppercase(java.util.Locale.ROOT)
    fun api(raw: String): String {
        val u=runCatching { URI(raw) }.getOrNull()
        require(u!=null && u.scheme=="https" && !u.host.isNullOrBlank() && u.rawUserInfo==null && u.rawQuery==null && u.rawFragment==null &&
            u.rawPath in listOf("/v1","/v1/") && (u.port == -1 || u.port in 1..65535)) { "Нужен HTTPS-адрес сервиса с путём /v1." }
        return URI("https",null,u.host.lowercase(),if(u.port==443) -1 else u.port,"/v1",null,null).toASCIIString()
    }
    fun base64(value: String,limit: Int): ByteArray {
        require(value.length <= limit.toLong()*4/3+4 && Regex("[A-Za-z0-9_-]*").matches(value)) { "Неверное кодирование пакета." }
        val decoded=runCatching { Base64.getUrlDecoder().decode(value) }.getOrNull() ?: error("Неверное кодирование пакета.")
        require(decoded.size<=limit); return decoded
    }
    fun trust(api: String,kid: String,key: String,dns: PanelDns): PanelTrust {
        require(Regex("[a-f0-9]{16}").matches(kid) && base64(key,32).size==32) { "Неверный публичный ключ сервиса." }
        return PanelTrust(api(api),kid,key,dns.validated())
    }
    fun bootstrap(text: String): PanelTrust {
        val o=PanelJson.obj(PanelJson.parse(text))
        require(PanelJson.number(o,"api_version")==1L && PanelJson.number(o,"package_schema")==1L && PanelJson.str(o,"algorithm")=="Ed25519")
        return trust(PanelJson.str(o,"api_base",2048),PanelJson.str(o,"key_id"),PanelJson.str(o,"public_key"),dns(PanelJson.obj(o.get("dns_transport"))))
    }
    fun activation(raw: String,bootstrap: PanelTrust): ActivationInput {
        if(!raw.trim().startsWith("tndtun:",true)) return ActivationInput(code(raw),bootstrap)
        require(raw.length<=8192) { "Ссылка слишком длинная." }
        val u=runCatching { URI(raw.trim()) }.getOrNull()
        require(u!=null && u.scheme.equals("tndtun",true) && u.host=="activate" && u.rawUserInfo==null && u.port == -1 && u.path.isNullOrEmpty() && u.fragment==null) { "Неверная ссылка активации." }
        val fields=linkedMapOf<String,String>()
        u.rawQuery.orEmpty().split('&').forEach { part->
            val pair=part.split('=',limit=2); require(pair.size==2)
            val name=URLDecoder.decode(pair[0],"UTF-8")
            require(name in listOf("api","code","key","kid") && name !in fields) { "Неверные поля ссылки." }
            fields[name]=URLDecoder.decode(pair[1],"UTF-8")
        }
        require(fields.size==4) { "В ссылке отсутствуют параметры активации." }
        return ActivationInput(code(fields.getValue("code")),trust(fields.getValue("api"),fields.getValue("kid"),fields.getValue("key"),bootstrap.dns))
    }
    fun dns(o: JsonObject): PanelDns = PanelDns(PanelJson.str(o,"domain",253),PanelJson.str(o,"publicKey"),PanelJson.str(o,"resolver"),
        if(o.has("sessionCount")) PanelJson.number(o,"sessionCount").let { require(it in listOf(1L,2L,4L,8L)); it.toInt() } else 4,
        if(o.has("autoDetectResolver")) PanelJson.bool(o,"autoDetectResolver") else true,
        if(o.has("savedResolvers")) PanelJson.strings(o,"savedResolvers",100) else emptyList(),
        if(o.has("useWiFiResolver")) PanelJson.bool(o,"useWiFiResolver") else false,
        if(o.has("wifiResolver")) PanelJson.str(o,"wifiResolver") else "8.8.8.8:53").validated().also {
            if(o.has("tunnelType")) require(PanelJson.str(o,"tunnelType")=="socks5") { "Неподдерживаемый DNS-транспорт." }
        }
    fun verify(envelope: ByteArray,trust: PanelTrust,nonce: String,deviceID: String,previous: PanelSubscription?=null): PanelPackage {
        require(envelope.size<=ENVELOPE_LIMIT) { "Ответ сервиса слишком большой." }
        val o=PanelJson.obj(PanelJson.parse(utf8(envelope)))
        require(PanelJson.str(o,"algorithm")=="Ed25519" && PanelJson.str(o,"key_id")==trust.keyID) { "Неизвестный ключ подписи." }
        val payload=base64(PanelJson.str(o,"payload",ENVELOPE_LIMIT),PAYLOAD_LIMIT)
        val signature=base64(PanelJson.str(o,"signature"),64); require(signature.size==64) { "Неверная подпись пакета." }
        try { Ed25519Verify(base64(trust.publicKey,32)).verify(signature,payload) }
        catch(_:java.security.GeneralSecurityException) { error("Подпись пакета не подтверждена. Сохранённые настройки не изменены.") }
        val p=PanelJson.obj(PanelJson.parse(utf8(payload)))
        require(PanelJson.str(p,"format")=="TnDtun-package" && PanelJson.number(p,"schema_version")==1L) { "Неподдерживаемая схема. Обновите приложение." }
        require(PanelJson.str(p,"nonce")==nonce) { "Ответ относится к другому запросу." }
        val device=PanelJson.uuid(p,"device_id"); val client=PanelJson.uuid(p,"client_id")
        require(device==deviceID && (previous==null || client==previous.clientID)) { "Ответ относится к другому устройству или клиенту." }
        val revision=PanelJson.number(p,"revision"); require(revision>=0 && revision >= (previous?.revision ?: 0)) { "Сервис вернул устаревшую версию." }
        val status=PanelJson.str(p,"status"); require(status in listOf("active","revoked"))
        val expires=p.get("expires_at").let { require(it!=null); if(it.isJsonNull) null else PanelJson.number(p,"expires_at").also { n-> require(n>=0 && n<=Long.MAX_VALUE/1000) } }
        val sync=PanelJson.number(p,"sync_after_seconds"); require(sync in 1..86400)
        val issued=PanelJson.str(p,"issued_at"); issuedAt(issued)
        val sid=subscriptionID(trust.api,device)
        val rawProfiles=p.get("profiles"); require(rawProfiles?.isJsonArray==true && rawProfiles.asJsonArray.size()<=1000)
        val profiles=rawProfiles.asJsonArray.map { raw->
            val r=PanelJson.obj(raw); val id=PanelJson.uuid(r,"id"); val mode=PanelJson.str(r,"mode"); require(mode in listOf("amnezia","turn")) { "Протокол пакета не поддерживается." }
            val generation=PanelJson.number(r,"generation"); require(generation>=1)
            val title=PanelJson.str(r,"title"); require(title.isNotBlank())
            val meta=ManagedProfile(sid,PanelJson.str(r,"inbound_id"),PanelJson.str(r,"node_id"),generation,title,PanelJson.str(r,"location"),
                if(r.has("credential_scope")) PanelJson.str(r,"credential_scope").also { require(it in listOf("shared","device")) } else "device")
            require(meta.inboundID.isNotBlank() && meta.nodeID.isNotBlank()) { "Неверные идентификаторы ноды или инбаунда." }
            val c=if(mode=="amnezia") SimpleConfiguration(id=id,mode=mode,amneziaKey=PanelJson.str(r,"amneziaKey",AmneziaImporter.MAXIMUM_SIZE,true),managed=meta)
            else SimpleConfiguration(id=id,mode=mode,server=PanelJson.str(r,"server"),callLink=PanelJson.str(r,"callLink",4096),vlessID=PanelJson.str(r,"vlessID"),wrapKey=PanelJson.str(r,"wrapKey"),managed=meta)
            c.validated(true)
        }
        require(profiles.map { it.id }.distinct().size==profiles.size) { "Повторяющиеся идентификаторы профилей." }
        require(status!="revoked" || profiles.isEmpty()) { "Отозванный пакет содержит подключения." }
        val settings=if(status=="active") settings(PanelJson.obj(p.get("settings"))) else null
        if(p.has("bootstrap_dns")) require(dns(PanelJson.obj(p.get("bootstrap_dns")))==settings?.dnsTunnel) { "Противоречивые параметры DNS." }
        return PanelPackage(revision,nonce,issued,client,PanelJson.str(p,"client_name",100),device,status,expires,sync,profiles,settings)
    }
    fun subscriptionID(api: String,device: String) = "$api#$device"
    // Android's desugared ISO_INSTANT parser is less permissive than recent JDKs.
    internal fun issuedAt(value: String) = OffsetDateTime.parse(value,DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
    private fun settings(o: JsonObject): PanelSettings {
        require(o.toString().toByteArray().size<=5*1024*1024) { "Настройки слишком большие." }
        val interval=PanelJson.number(o,"turnCheckInterval"); require(interval in 15..300)
        val servers=PanelJson.strings(o,"dnsServers",100).also { require(it.isNotEmpty()) }.map { SimpleConfiguration.normalizeEndpoint(it,53,true) }.distinct()
        val tunnel=o.get("dnsTunnel").let { require(it!=null); if(it.isJsonNull) null else dns(PanelJson.obj(it)) }
        val split=PanelJson.obj(o.get("splitTunneling")); ApplicationBackup.validateSplitJson(split)
        val settings=com.google.gson.Gson().fromJson(split,SplitTunnelSettings::class.java).validated()
        return PanelSettings(PanelJson.bool(o,"autoCaptcha"),PanelJson.bool(o,"autoRecovery"),interval.toInt(),servers,tunnel,settings)
    }
    internal fun utf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
}
