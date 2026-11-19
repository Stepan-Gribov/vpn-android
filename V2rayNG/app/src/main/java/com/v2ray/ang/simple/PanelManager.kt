package com.v2ray.ang.simple

import android.content.Context
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.handler.MmkvManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.io.RandomAccessFile

/** One in-flight operation per process, plus a non-blocking cross-process request lease. */
object PanelManager {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val mutex=Mutex()
    private val mutableEvents=MutableStateFlow(0L)
    val events=mutableEvents.asStateFlow()
    @Volatile var busy=false; private set
    @Volatile var message=""; private set
    @Volatile internal var route: PanelRequestRoute?=null; private set
    private val queue=PanelOperationQueue(scope)
    fun bootstrap(context: Context) = context.assets.open("panel-bootstrap.json").bufferedReader().use { PanelContract.bootstrap(it.readText()) }
    fun activate(context: Context,input: ActivationInput,name: String): Deferred<Boolean> {
        val codeHash=java.security.MessageDigest.getInstance("SHA-256").digest(input.code.toByteArray()).joinToString("") { "%02x".format(it) }
        val key="activate:${input.trust.api}:${input.trust.keyID}:${input.trust.publicKey}:$codeHash"
        return launch(context,key) { client(context,it).activate(input,name) }
    }
    fun refreshManually(context: Context): Deferred<Boolean> = launch(context,"manual-sync") { store->
        for(sub in store.subscriptions) {
            if(!sub.needsActivation) client(context,store).sync(sub)
        }
    }
    private fun launch(context: Context,kind: String,action: suspend (SimpleStore)->Unit): Deferred<Boolean> {
        val app=context.applicationContext
        return queue.submit(kind) {
            mutex.lock()
            try {
                RandomAccessFile(File(app.noBackupFilesDir,"panel-request.lock"),"rw").channel.use { channel->
                    val lease=runCatching { channel.tryLock() }.getOrNull() ?: run {
                        message="Настройки уже обновляются другим процессом. Повторите после завершения."; notifyChanged(); return@submit false
                    }
                    lease.use {
                        busy=true; route=null; message="Обновляем настройки…"; notifyChanged()
                        try {
                            val store=SimpleStore(app)
                            action(store)
                            PanelAccess.enforce(app,store)
                            message=if(RoutingPolicyState.pending(store)) "Список обновлён. Переподключитесь, чтобы применить его." else "Настройки обновлены"
                            true
                        } catch(e:CancellationException) { message=""; throw e }
                        catch(e:Exception) {
                            message=if(e is PanelHttpFailure) e.message.orEmpty() else "Не удалось получить или проверить пакет. Сохранённые настройки оставлены."
                            false
                        } finally { route=null; busy=false; notifyChanged() }
                    }
                }
            } finally { mutex.unlock() }
        }
    }
    private fun notifyChanged() { mutableEvents.value=mutableEvents.value+1 }
    private fun client(context: Context,store: SimpleStore): PanelClient = PanelClient(object:PanelStorage {
        override fun installationID()=store.installationID()
        override fun subscriptions()=store.subscriptions
        override fun accept(packet: PanelPackage,trust: PanelTrust,token: String,now: Long,activation: Boolean,activationCode: String?)=store.accept(packet,trust,token,now,activation,activationCode)
        override fun reactivation(id: String)=store.requireReactivation(id)
    },PanelExchange { trust,dns,path,body,token->
        PanelTransport(context).post(trust,dns,path,body,token,onRoute={ selected->
            // The operation mutex owns this callback until post has returned.
            if(busy) {
                route=selected; message=selected?.message ?: "Обновляем настройки…"; notifyChanged()
            }
        })
    })
    fun cancelAutomaticSync(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork("tndtun-panel-sync")
    }
}
class PanelSyncWorker(context: Context,params: WorkerParameters): Worker(context,params) {
    // Compatibility with persisted jobs from older builds: never make a request.
    override fun doWork(): Result = Result.success()
}

/** Only a verified subscription expiry/revocation stops its currently running access. */
object PanelAccess {
    const val OWNER="simple.panel.active.owner"
    const val EXPIRES="simple.panel.active.expires"
    const val ALLOWED="simple.panel.active.allowed"
    fun stage(profile: SimpleConfiguration,store: SimpleStore) {
        store.requireAccess(profile)
        val sub=store.subscriptionFor(profile)
        MmkvManager.encodeSettings(OWNER,sub?.id.orEmpty())
        MmkvManager.encodeSettings(EXPIRES,(sub?.expiresAt ?: 0).toString())
        MmkvManager.encodeSettings(ALLOWED,true)
    }
    fun enforce(context: Context,store: SimpleStore=SimpleStore(context)): Boolean {
        val owner=MmkvManager.decodeSettingsString(OWNER).orEmpty()
        if(owner.isBlank()) return true
        val sub=store.subscriptions.firstOrNull { it.id==owner } ?: run {
            MmkvManager.encodeSettings(ALLOWED,false)
            return enforceCached(context)
        }
        val allowed=sub.accessAllowed(System.currentTimeMillis()/1000)
        MmkvManager.encodeSettings(EXPIRES,(sub.expiresAt ?: 0).toString())
        MmkvManager.encodeSettings(ALLOWED,allowed)
        if(!allowed && (context is android.app.Service || DaemonConnectionState.hasLiveService())) CoreServiceManager.stopVService(context)
        return allowed
    }
    fun stagedAllowed(): Boolean {
        if(MmkvManager.decodeSettingsString(OWNER).isNullOrBlank()) return true
        val expires=MmkvManager.decodeSettingsString(EXPIRES)?.toLongOrNull() ?: 0
        return MmkvManager.decodeSettingsBool(ALLOWED,false) && (expires==0L || System.currentTimeMillis()/1000<expires)
    }
    /** Admission to a new start/recovery also checks that the profile is still assigned. */
    fun preparedAllowed(context: Context,refreshRuntime: Boolean=false): Boolean {
        val owner=MmkvManager.decodeSettingsString(OWNER).orEmpty()
        if(owner.isBlank()) return true
        return runCatching {
            val store=SimpleStore(context)
            val id=MmkvManager.decodeSettingsString(SimpleRuntime.ACTIVE_ID)
            val profile=store.configurations.firstOrNull { it.id==id && it.managed?.subscriptionID==owner }
                ?: return@runCatching false
            store.requireAccess(profile)
            if(refreshRuntime) SimpleRuntime.stage(profile,store)
            enforce(context,store) && stagedAllowed()
        }.getOrDefault(false)
    }
    /** A storage read error must not crash the daemon or discard the accepted access. */
    fun enforceSafely(context: Context): Boolean = try { enforce(context) }
    catch(_:Exception) { enforceCached(context) }
    private fun enforceCached(context: Context): Boolean {
        val allowed=stagedAllowed()
        if(!allowed && (context is android.app.Service || DaemonConnectionState.hasLiveService())) CoreServiceManager.stopVService(context)
        return allowed
    }
}
