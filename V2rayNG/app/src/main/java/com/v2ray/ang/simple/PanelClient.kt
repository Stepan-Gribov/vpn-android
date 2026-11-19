package com.v2ray.ang.simple

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.random.Random

internal interface PanelStorage {
    fun installationID(): String
    fun subscriptions(): List<PanelSubscription>
    fun accept(packet: PanelPackage,trust: PanelTrust,token: String,now: Long,activation: Boolean=false,activationCode: String?=null)
    fun reactivation(id: String)
}
internal fun interface PanelExchange {
    suspend fun post(trust: PanelTrust,dns: PanelDns,path: String,body: JsonObject,token: String?): ByteArray
}
/** Identity is committed before any request; failed verification never calls storage.accept. */
internal class PanelClient(private val storage: PanelStorage,private val exchange: PanelExchange,
    private val pause: suspend (Long)->Unit = { delay(it) },private val clock: ()->Long = { System.currentTimeMillis()/1000 },
    private val requestTimeoutMillis: Long = 192_000) {
    suspend fun activate(input: ActivationInput,name: String): PanelPackage {
        require(name.length in 1..100 && name.none { it.isISOControl() })
        val installation=storage.installationID()
        val nonce=PanelContract.nonce()
        val body=JsonObject().apply {
            addProperty("code",input.code); addProperty("installation_id",installation); addProperty("platform","android")
            addProperty("name",name); addProperty("nonce",nonce)
        }
        val raw=request(input.trust,input.trust.dns,"activate",body,null)
        require(raw.size<=PanelContract.ENVELOPE_LIMIT)
        val response=PanelJson.obj(PanelJson.parse(PanelContract.utf8(raw)))
        val device=PanelJson.uuid(response,"device_id"); val token=PanelJson.str(response,"token",4096)
        val existing=storage.subscriptions().firstOrNull { it.id==PanelContract.subscriptionID(input.trust.api,device) }
        val packageBytes=PanelJson.obj(response.get("package")).toString().toByteArray(Charsets.UTF_8)
        val packet=PanelContract.verify(packageBytes,input.trust,nonce,device,existing)
        currentCoroutineContext().ensureActive()
        storage.accept(packet,input.trust,token,clock(),true,input.code)
        return packet
    }
    suspend fun sync(sub: PanelSubscription): PanelPackage {
        val nonce=PanelContract.nonce()
        val body=JsonObject().apply { addProperty("nonce",nonce); addProperty("known_revision",sub.revision) }
        val raw=try { request(sub.trust,sub.settings?.dnsTunnel ?: sub.trust.dns,"sync",body,sub.token) }
        catch(e:PanelHttpFailure) { if(e.status==401) storage.reactivation(sub.id); throw e }
        val packet=PanelContract.verify(raw,sub.trust,nonce,sub.deviceID,sub)
        currentCoroutineContext().ensureActive()
        storage.accept(packet,sub.trust,sub.token,clock())
        return packet
    }
    private suspend fun request(trust: PanelTrust,dns: PanelDns,path: String,body: JsonObject,token: String?): ByteArray = withTimeoutOrNull(requestTimeoutMillis) {
        repeat(3) { attempt->
            try { return@withTimeoutOrNull exchange.post(trust,dns,path,body,token) }
            catch(e:Exception) {
                if(e !is IOException && (e !is PanelHttpFailure || !e.retryable) || attempt==2) throw e
                pause(((2000L shl attempt)+Random.nextLong(0,1500)).coerceAtMost(30_000))
            }
        }
        error("Недоступный сервис")
    } ?: throw IOException("Сервис не ответил вовремя. Сохранённые настройки оставлены.")
}
