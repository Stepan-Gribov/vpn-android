package com.v2ray.ang.simple

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Atomic, encrypted profile storage. Client keys and subscription tokens stay on device. */
class SimpleStore(context: Context) {
    private val bundledDns = BundledDnsTunnel.profile(context)
    private val file = File(context.noBackupFilesDir, "simple-configurations.enc")
    private val prefs = context.getSharedPreferences("simple-ui", Context.MODE_PRIVATE)
    private val disk = AtomicStateFile(file)
    @Volatile private var state: SimpleState = disk.locked { loadState() }
    @Synchronized fun reload() { state = disk.locked { loadState() } }
    private fun loadState(): SimpleState {
        val saved=if(file.exists()) read() else legacyState()
        return if(saved.configurations.any(BundledDnsTunnel::isBuiltIn)) saved.copy(
            configurations=saved.configurations.filterNot(BundledDnsTunnel::isBuiltIn)) else saved
    }
    internal fun canonicalConfiguration(value: SimpleConfiguration): SimpleConfiguration =
        if(BundledDnsTunnel.isBuiltIn(value)) bundledDns.copy(savedResolvers=bundledDns.savedResolvers.toList()) else value
    private fun legacyState(profiles: List<SimpleConfiguration> = emptyList()) = SimpleState(
        configurations = profiles,
        mode = prefs.getString("mode", "turn") ?: "turn",
        autoCaptcha = prefs.getBoolean("autoCaptcha", true),
        autoRecovery = prefs.getBoolean("autoRecovery", true),
        savedResolvers = Gson().fromJson(prefs.getString("resolvers", "[]"), Array<String>::class.java)?.toList().orEmpty(),
    )
    var mode: String
        get() = state.mode
        @Synchronized set(value) { mutate { current -> current.copy(mode = value) } }
    var autoCaptcha: Boolean
        get() = state.autoCaptcha
        @Synchronized set(value) { mutate { current -> current.copy(autoCaptcha = value) } }
    var autoRecovery: Boolean
        get() = state.autoRecovery
        @Synchronized set(value) { mutate { current -> current.copy(autoRecovery = value) } }
    val autoConnectOnRestart: Boolean get() = state.autoConnectOnRestart
    var savedResolvers: List<String>
        get() = state.savedResolvers
        @Synchronized set(value) { mutate { current -> current.copy(savedResolvers = value.distinct()) } }
    val excludedApplications: List<String> get() = state.excludedApplications.orEmpty()
    val ipv6Enabled: Boolean get() = state.ipv6Enabled
    val turnCheckInterval: Int get() = state.turnCheckInterval
    val turnSessionCount: Int get() = com.v2ray.ang.core.TurnSessionCount.normalize(state.turnSessionCount)
    val splitTunneling: SplitTunnelSettings get() = state.splitTunneling
    val amneziaSplitEnabled: Boolean get() = state.amneziaSplitEnabled
    val fullTunnelMinutes: Int get() = state.fullTunnelMinutes.takeIf { it in TemporaryFullTunnelPolicy.durations } ?: TemporaryFullTunnelPolicy.DEFAULT_MINUTES
    // The built-in row comes from app assets, never from erasable user storage.
    val configurations: List<SimpleConfiguration> get() = listOf(canonicalConfiguration(bundledDns))+state.configurations
    @Synchronized fun saveSettings(captcha: Boolean, recovery: Boolean, split: SplitTunnelSettings, interval: Int = turnCheckInterval, ipv6: Boolean = ipv6Enabled, excludedApps: List<String> = excludedApplications, autoConnect: Boolean = autoConnectOnRestart, amneziaSplit: Boolean = amneziaSplitEnabled, fullMinutes: Int = fullTunnelMinutes, turnSessions: Int = turnSessionCount) {
        require(interval in 15..300 && fullMinutes in TemporaryFullTunnelPolicy.durations && turnSessions in com.v2ray.ang.core.TurnSessionCount.choices)
        mutate { current -> current.copy(turnSessionCount=turnSessions,amneziaSplitEnabled=amneziaSplit, fullTunnelMinutes=fullMinutes, autoCaptcha = captcha, autoRecovery = recovery, autoConnectOnRestart = autoConnect, splitTunneling = split.validated(), turnCheckInterval = interval, ipv6Enabled = ipv6, excludedApplications = AppVpnExclusions.normalized(excludedApps)) }
    }
    @Synchronized fun backup(captcha: Boolean = autoCaptcha, recovery: Boolean = autoRecovery, split: SplitTunnelSettings = splitTunneling,
               interval: Int = turnCheckInterval, selected: String? = com.v2ray.ang.handler.MmkvManager.decodeSettingsString(SimpleRuntime.ACTIVE_ID), ipv6: Boolean = ipv6Enabled, excludedApps: List<String> = excludedApplications, autoConnect: Boolean = autoConnectOnRestart, amneziaSplit: Boolean = amneziaSplitEnabled, fullMinutes: Int = fullTunnelMinutes, turnSessions: Int = turnSessionCount): ApplicationBackup =
        ApplicationBackup(profiles = configurations.map { it.copy(managed = null) }, mode = mode, autoCaptcha = captcha, autoRecovery = recovery,
            savedDNSServers = savedResolvers, splitTunneling = split,
            turnCheckInterval = interval, selectedProfileID = (selected ?: state.selectedProfileID)?.takeIf { id -> configurations.any { it.id == id } },
            dnsServers=state.dnsServers,dnsTunnel=state.dnsTunnel,ipv6Enabled=ipv6,excludedApplications=excludedApps,autoConnectOnRestart=autoConnect,amneziaSplitEnabled=amneziaSplit,fullTunnelMinutes=fullMinutes,turnSessionCount=turnSessions)
    @Synchronized fun restore(backup: ApplicationBackup) {
        val value = backup.validated()
        val portable=value.copy(profiles=value.profiles.filterNot(BundledDnsTunnel::isBuiltIn),
            selectedProfileID=value.selectedProfileID?.takeUnless { it==BundledDnsTunnel.ID })
        mutate { current -> PanelStateReducer.restoreBackup(current,portable).let {
            if(value.selectedProfileID==BundledDnsTunnel.ID) it.copy(selectedProfileID=BundledDnsTunnel.ID) else it
        } }
    }
    @Synchronized fun save(value: SimpleConfiguration) {
        require(!BundledDnsTunnel.isBuiltIn(value)) { "Встроенный DNS нельзя изменить. Добавьте отдельный профиль." }
        val normalized = value.validated()
        mutate { current ->
            val next = if(current.configurations.any { it.id==value.id }) current.configurations.map { if(it.id==value.id) normalized else it } else current.configurations+normalized
            current.copy(configurations=next,savedResolvers=if(value.mode=="dns") (current.savedResolvers+normalized.resolver).distinct() else current.savedResolvers)
        }
    }
    @Synchronized fun remove(id: String) {
        require(id!=BundledDnsTunnel.ID) { "Встроенный DNS нельзя удалить." }
        mutate { current -> current.copy(configurations=current.configurations.filterNot { it.id==id },selectedProfileID=current.selectedProfileID?.takeIf { it!=id }) }
    }
    val subscriptions: List<PanelSubscription> get() = state.subscriptions
    @Synchronized fun removeSubscription(id: String) {
        mutate { current -> PanelStateReducer.removeSubscription(current,id) }
    }
    val activeSubscription: PanelSubscription? get() = state.subscriptions.firstOrNull { it.id==state.activeSubscriptionID } ?: state.subscriptions.lastOrNull()
    val dnsTunnel: PanelDns? get() = state.dnsTunnel
    val dnsServers: List<String> get() = state.dnsServers
    val selectedProfileID: String? get() = state.selectedProfileID
    @Synchronized fun installationID(): String {
        mutate { current -> if(current.installationID.isBlank()) current.copy(installationID=java.util.UUID.randomUUID().toString()) else current }
        return state.installationID
    }
    @Synchronized fun selectProfile(id: String) { mutate { it.copy(selectedProfileID=id) } }
    @Synchronized fun accept(packet: PanelPackage, trust: PanelTrust, token: String, now: Long, activation: Boolean=false, activationCode: String?=null) {
        require(packet.profiles.none(BundledDnsTunnel::isBuiltIn)) { "Идентификатор встроенного DNS зарезервирован." }
        mutate { current -> PanelStateReducer.apply(current,packet,trust,token,now,
            com.v2ray.ang.handler.MmkvManager.decodeSettingsString(SimpleRuntime.ACTIVE_ID) ?: current.selectedProfileID,activation,activationCode) }
    }
    @Synchronized fun requireReactivation(id: String) {
        mutate { current -> current.copy(subscriptions=current.subscriptions.map { sub -> if(sub.id!=id) sub else
            sub.withSyncState(needsActivation = true, nextSyncAt = 0) }) }
    }
    @Synchronized internal fun deferAutomaticSync(expected: PanelSubscription, until: Long) {
        mutate { current ->
            val sub = current.subscriptions.firstOrNull { it.id == expected.id }
            if (sub?.automaticSyncAt == null || sub.nextSyncAt != expected.nextSyncAt ||
                sub.lastSync != expected.lastSync || sub.revision != expected.revision) current
            else current.copy(subscriptions = current.subscriptions.map {
                if (it.id == sub.id) it.withSyncState(nextSyncAt = until) else it
            })
        }
    }
    fun subscriptionFor(profile: SimpleConfiguration) = profile.managed?.subscriptionID?.let { id -> subscriptions.firstOrNull { it.id==id } }
    fun requireAccess(profile: SimpleConfiguration) {
        if(profile.managed!=null) check(subscriptionFor(profile)?.accessAllowed(System.currentTimeMillis()/1000)==true) { "Управляемый доступ истёк или отозван." }
    }
    internal fun connectionSettings(profile: SimpleConfiguration): PanelSettings {
        val owner=subscriptionFor(profile)
        if(owner!=null && owner.id!=state.activeSubscriptionID) owner.settings?.let { return it.copy(splitTunneling=state.splitTunneling) }
        return PanelSettings(autoCaptcha,autoRecovery,turnCheckInterval,dnsServers,dnsTunnel,splitTunneling)
    }
    fun rememberResolver(raw: String): String {
        val value = SimpleConfiguration.normalizeTunnelResolver(raw)
        savedResolvers = savedResolvers + value
        return value
    }
    private fun mutate(transform: (SimpleState) -> SimpleState) = disk.locked {
        val current = loadState()
        val next = transform(current)
        if(next !== current) write(next)
        state = next
    }
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("simpleblt-profiles-v1", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("simpleblt-profiles-v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun read(): SimpleState {
        val encoded = file.readLines()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(encoded[0], Base64.NO_WRAP)))
        val text = String(cipher.doFinal(Base64.decode(encoded[1], Base64.NO_WRAP)), Charsets.UTF_8)
        val root = com.google.gson.JsonParser.parseString(text)
        return if (root.isJsonArray) legacyState(Gson().fromJson(text, object : TypeToken<List<SimpleConfiguration>>() {}.type))
        else Gson().fromJson(root, SimpleState::class.java)
    }
    private fun write(values: SimpleState) {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,key())
        val encrypted=cipher.doFinal(Gson().toJson(values).toByteArray(Charsets.UTF_8))
        disk.replace((Base64.encodeToString(cipher.iv,Base64.NO_WRAP)+"\n"+Base64.encodeToString(encrypted,Base64.NO_WRAP)).toByteArray(Charsets.UTF_8))
    }
}
