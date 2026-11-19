package com.v2ray.ang.simple

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.v2ray.ang.core.ManagedDnsTunnelManager
import com.v2ray.ang.core.ManagedDnsTunnelResolverDiscovery
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

class PanelHttpFailure(val status: Int,val code: String): Exception(messageFor(status,code)) {
    val retryable get() = status==429 || status==503 && code!="package_too_large"
    companion object {
        private fun messageFor(status: Int,code: String) = when {
            status==401 -> "Нужна повторная активация. Сохранённые подключения оставлены."
            code=="device_limit" -> "Достигнут лимит устройств. Обратитесь к администратору."
            code=="invalid_activation" -> "Код недействителен или его срок истёк."
            code=="provisioning_pending" -> "Сервер ещё готовит подключения. Повторяем запрос…"
            code=="access_disabled" || code=="device_revoked" -> "Сервер не разрешил запрос. Сохранённые настройки оставлены."
            status==429 -> "Слишком много запросов. Попробуйте позже."
            else -> "Сервис временно недоступен. Сохранённые настройки оставлены."
        }
    }
}
/** Default TLS/hostname verification, transparent gzip, no redirects, bounded decompressed body. */
internal object PanelHttp {
    fun request(client: OkHttpClient,api: String,path: String,body: String,token: String?): ByteArray {
        require(path in listOf("activate","sync"))
        return execute(client,"${PanelContract.api(api)}/$path",body,token,PanelContract.ENVELOPE_LIMIT)
    }
    suspend fun requestCancellable(client: OkHttpClient,api: String,path: String,body: String,token: String?): ByteArray {
        require(path in listOf("activate","sync"))
        return executeCancellable(client,"${PanelContract.api(api)}/$path",body,token,PanelContract.ENVELOPE_LIMIT)
    }
    fun manifest(client: OkHttpClient,trust: PanelTrust) {
        val bytes=execute(client,"${trust.api}/manifest",null,null,16*1024)
        verifyManifest(bytes,trust)
    }
    suspend fun manifestCancellable(client: OkHttpClient,trust: PanelTrust) {
        verifyManifest(executeCancellable(client,"${trust.api}/manifest",null,null,16*1024),trust)
    }
    private fun verifyManifest(bytes: ByteArray,trust: PanelTrust) {
        val o=PanelJson.obj(PanelJson.parse(PanelContract.utf8(bytes)))
        require(PanelJson.str(o,"algorithm")=="Ed25519" && PanelJson.str(o,"key_id")==trust.keyID &&
            PanelJson.str(o,"public_key")==trust.publicKey && PanelContract.api(PanelJson.str(o,"api_base",2048))==trust.api) {
            "Источник не соответствует закреплённому ключу."
        }
    }
    private fun call(client: OkHttpClient,url: String,body: String?,token: String?): Call {
        val request=Request.Builder().url(url).header("Accept","application/json")
        if(body!=null) request.post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
        if(token!=null) request.header("Authorization","Bearer $token")
        return client.newCall(request.build())
    }
    private fun execute(client: OkHttpClient,url: String,body: String?,token: String?,limit: Int): ByteArray =
        call(client,url,body,token).execute().use { response-> responseBytes(response,url,limit) }
    private suspend fun executeCancellable(client: OkHttpClient,url: String,body: String?,token: String?,limit: Int): ByteArray =
        suspendCancellableCoroutine { continuation->
            val call=call(client,url,body,token)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object: Callback {
                override fun onFailure(call: Call,e: java.io.IOException) { continuation.resumeWithException(e) }
                override fun onResponse(call: Call,response: Response) {
                    val result=runCatching { response.use { responseBytes(it,url,limit) } }
                    continuation.resumeWith(result)
                }
            })
        }
    private fun responseBytes(response: Response,url: String,limit: Int): ByteArray {
            require(response.request.url.toString()==url) { "Перенаправление запроса запрещено." }
            if(!response.isSuccessful) {
                val error=read(response.body?.byteStream(),16*1024)
                val code=runCatching { JsonParser.parseString(String(error,Charsets.UTF_8)).asJsonObject.get("code")?.asString }
                    .getOrNull()?.takeIf { it.matches(Regex("[a-z_]{1,64}")) }.orEmpty()
                throw PanelHttpFailure(response.code,code)
            }
            return read(response.body?.byteStream(),limit)
    }
    fun client(timeout: Long) = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(20,TimeUnit.SECONDS).readTimeout(timeout,TimeUnit.SECONDS).writeTimeout(timeout,TimeUnit.SECONDS)
        .callTimeout(timeout,TimeUnit.SECONDS).retryOnConnectionFailure(false)
    private fun read(input: java.io.InputStream?,limit: Int): ByteArray {
        require(input!=null) { "Пустой ответ сервиса." }
        val out=ByteArrayOutputStream(); val buffer=ByteArray(8192)
        input.use { while(true) { val n=it.read(buffer); if(n<0) break; require(out.size().toLong()+n<=limit) { "Ответ сервиса слишком большой." }; out.write(buffer,0,n) } }
        return out.toByteArray()
    }
}

/** A private, short-lived DNSTT helper; never touches VPN services, MMKV profiles or their ports. */
internal class PanelTransport(private val context: Context) {
    companion object { private val lastResolver=java.util.concurrent.ConcurrentHashMap<Long,String>() }
    private fun physicalNetwork(): android.net.Network? {
        val connectivity=context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return connectivity.activeNetwork?.takeIf {
            connectivity.getNetworkCapabilities(it)?.let { c->c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }==true
        } ?: connectivity.allNetworks.firstOrNull {
            connectivity.getNetworkCapabilities(it)?.let { c->c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }==true
        }
    }
    suspend fun post(trust: PanelTrust,dns: PanelDns,path: String,body: JsonObject,token: String?,dnsOnly: Boolean=false,
                     onRoute: (PanelRequestRoute?)->Unit = {}): ByteArray = withContext(Dispatchers.IO) {
        PanelRouteFallback.post(dnsOnly,onRoute,
            // Ordinary HTTPS follows the route allowed for this UID (existing app exclusions remain).
            // Forcing the physical network can make a reachable API needlessly use DNSTT.
            direct={ PanelHttp.requestCancellable(PanelHttp.client(12).proxy(Proxy.NO_PROXY).build(),trust.api,path,body.toString(),token) },
            dns={ fallbackDns(trust,dns,path,body,token) })
    }
    private suspend fun fallbackDns(trust: PanelTrust,dns: PanelDns,path: String,body: JsonObject,token: String?): ByteArray {
        currentCoroutineContext().ensureActive()
        val bundled=runCatching { PanelManager.bootstrap(context) }.getOrNull()?.takeIf { it.sameSource(trust) }
        val fallbacks=trust.dns.savedResolvers+bundled?.dns?.savedResolvers.orEmpty()
        val selected=dns.validated().let { if(it.autoDetectResolver) it.copy(savedResolvers=(it.savedResolvers+fallbacks).distinct()) else it }
        val discovered=if(selected.autoDetectResolver) ManagedDnsTunnelResolverDiscovery.discover(context) else emptyList()
        val available=PanelResolvers.candidates(selected,discovered,isWiFi())
        val network=physicalNetwork()?.networkHandle ?: 0L
        val remembered=lastResolver[network]?.takeIf { it in available }
        val candidates=(listOfNotNull(remembered)+available).distinct()
        fun working(resolver: String) { if(lastResolver.size>=8) lastResolver.clear(); lastResolver[network]=resolver }
        var last: Exception?=null
        val deadline=android.os.SystemClock.elapsedRealtime()+180_000
        for(resolver in candidates) {
            currentCoroutineContext().ensureActive()
            try { return throughDns(trust,selected,resolver,path,body.toString(),token,deadline).also { working(resolver) } }
            catch(e:PanelHttpFailure) { working(resolver); throw e }
            catch(e:java.io.IOException) { last=e }
        }
        throw java.io.IOException("DNS-канал недоступен",last)
    }
    private fun isWiFi(): Boolean {
        val cm=context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return physicalNetwork()?.let { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }==true
    }
    private suspend fun throughDns(trust: PanelTrust,dns: PanelDns,resolver: String,path: String,body: String,token: String?,requestDeadline: Long): ByteArray {
        if(requestDeadline-android.os.SystemClock.elapsedRealtime()<90_000) throw java.io.IOException("DNS-канал не успел установить соединение")
        val executable=File(context.applicationInfo.nativeLibraryDir,"libdnstt.so")
        check(executable.canExecute()) { "Модуль DNS-туннеля отсутствует." }
        val port=java.net.ServerSocket(0,0,java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val process=ProcessBuilder(ManagedDnsTunnelManager.dnsttCommand(executable.absolutePath,resolver,dns.publicKey,dns.domain,port,dns.sessionCount))
            .redirectErrorStream(true).start()
        // Provider output may contain connection data. Drain and discard it.
        Thread({ runCatching { process.inputStream.use { input-> val buffer=ByteArray(2048); while(input.read(buffer)>=0) {} } } },"TnDtun-panel-dns-output").apply { isDaemon=true; start() }
        try {
            // The pooled native helper opens its listener only after authenticated
            // DNS handshake (20s + grace); a 5s TCP wait kills healthy slow starts.
            val deadline=minOf(android.os.SystemClock.elapsedRealtime()+30_000,requestDeadline-90_000)
            var listening=false
            while(android.os.SystemClock.elapsedRealtime()<deadline) {
                if(runCatching { process.exitValue(); true }.getOrDefault(false)) throw java.io.IOException("DNS-транспорт остановился")
                if(runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1",port),100) }; true }.getOrDefault(false)) { listening=true; break }
                delay(50)
            }
            if(!listening) throw java.io.IOException("DNS-транспорт не запустился")
            val proxy=Proxy(Proxy.Type.SOCKS,InetSocketAddress("127.0.0.1",port))
            // Probe without credentials before giving a resolver a long request.
            // This compares the manifest against the pin; it never adopts its key.
            PanelHttp.manifestCancellable(PanelHttp.client(15).proxy(proxy).build(),trust)
            val seconds=((requestDeadline-android.os.SystemClock.elapsedRealtime())/1000).coerceAtMost(150)
            if(seconds<90) throw java.io.IOException("DNS-канал не успел установить соединение")
            val client=PanelHttp.client(seconds).proxy(proxy).build()
            return PanelHttp.requestCancellable(client,trust.api,path,body,token)
        } finally {
            withContext(NonCancellable) {
                process.destroy()
                val end=android.os.SystemClock.elapsedRealtime()+2000
                while(runCatching { process.exitValue(); false }.getOrDefault(true) && android.os.SystemClock.elapsedRealtime()<end) delay(25)
                if(runCatching { process.exitValue(); false }.getOrDefault(true) && android.os.Build.VERSION.SDK_INT>=26) process.destroyForcibly()
            }
        }
    }
}
