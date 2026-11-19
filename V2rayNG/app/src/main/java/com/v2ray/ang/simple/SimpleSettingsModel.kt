package com.v2ray.ang.simple

import android.content.Context
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.core.CoreServiceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A local Android draft. Rotation retains it; Cancel never writes it to storage. */
class SimpleSettingsModel : ViewModel() {
    val changed = MutableLiveData(0)
    private lateinit var store: SimpleStore
    private var loadingStarted = false
    private var revision = 0
    private var searchJob: Job? = null
    val loaded: Boolean get() = ::store.isInitialized
    var busy = true; private set
    var autoCaptcha = true
    var autoConnectOnRestart = false
    var amneziaSplitEnabled = false
    var fullTunnelMinutes = TemporaryFullTunnelPolicy.DEFAULT_MINUTES
    var autoRecovery = true
    var excludedApplications: List<String> = emptyList(); private set
    fun setExcludedApplications(value: List<String>) {
        if(busy) return
        excludedApplications=AppVpnExclusions.normalized(value)
        notifyChange()
    }
    var ipv6Enabled = false
    var recoveryApplies = false; private set
    var turnCheckInterval = 30
    var turnSessionCount = com.v2ray.ang.core.TurnSessionCount.DEFAULT
    var split = SplitTunnelSettings(); private set
    var newAddress = ""
    var search = ""; private set
    var expanded = false; private set
    var matches: List<SplitTunnelEntry> = emptyList(); private set
    var tab = 0
    var message = ""; private set
    var error: String? = null
    var pendingBackup: ApplicationBackup? = null
    var pendingExport: String? = null; private set
    var requestExport = false
    var fileKind = "addresses"
    var finished = false; private set
    var routingChanged = false; private set
    var restored = false; private set
    val displayedEntries: List<SplitTunnelEntry> get() = if (expanded) matches else matches.take(5)

    fun load(context: Context) {
        if (loadingStarted) return
        loadingStarted = true
        operation {
            store = withContext(Dispatchers.IO) { SimpleStore(context.applicationContext) }
            autoCaptcha = store.autoCaptcha
            autoRecovery = store.autoRecovery
            autoConnectOnRestart = store.autoConnectOnRestart
            amneziaSplitEnabled = store.amneziaSplitEnabled
            fullTunnelMinutes = store.fullTunnelMinutes
            val active=com.v2ray.ang.handler.MmkvManager.decodeSettingsString(SimpleRuntime.ACTIVE_ID)
            recoveryApplies=(store.configurations.firstOrNull { it.id==active }?.mode ?: store.mode)=="turn"
            ipv6Enabled = store.ipv6Enabled
            excludedApplications = store.excludedApplications
            turnCheckInterval = store.turnCheckInterval
            turnSessionCount = store.turnSessionCount
            split = store.splitTunneling
            refreshSearch()
        }
    }
    private fun notifyChange() { changed.value = (changed.value ?: 0) + 1 }
    private fun operation(block: suspend () -> Unit) {
        busy = true
        notifyChange()
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Could not complete action." }
            finally { busy = false; notifyChange() }
        }
    }
    fun setMode(mode: String) {
        if (busy || mode == split.mode) return
        split = split.copy(mode = mode)
        search = ""; expanded = false; message = ""
        refreshSearch()
        notifyChange()
    }
    fun setPush(apple: Boolean, google: Boolean) {
        if(busy || !loaded) return
        val push=PushNotificationSettings(apple,google)
        if(split.pushNotifications==push) return
        split=split.copy(pushNotifications=push); notifyChange()
    }
    fun setSearch(value: String) {
        if (value == search) return
        search = value; expanded = false
        refreshSearch()
    }
    private fun refreshSearch() {
        searchJob?.cancel()
        val entries = split.activeEntries
        val query = search.trim()
        val capturedRevision = ++revision
        searchJob = viewModelScope.launch {
            if (query.isNotEmpty()) delay(200)
            val result = withContext(Dispatchers.Default) {
                if (query.isEmpty()) entries else entries.filter {
                    it.address.contains(query, ignoreCase = true) || it.networks.any { network -> network.contains(query, ignoreCase = true) }
                }
            }
            if (capturedRevision == revision) { matches = result; notifyChange() }
        }
    }
    fun toggleExpanded() { expanded = !expanded; notifyChange() }
    fun addManual() {
        if (busy || split.mode == SplitTunnelSettings.DISABLED) return
        val text = newAddress
        val mode = split.mode
        val previous = split.entries(mode)
        operation {
            val merged = withContext(Dispatchers.Default) {
                SplitTunnelImport.normalized(previous + SplitTunnelImport.manual(text))
            }
            split = split.withEntries(mode, merged)
            newAddress = ""; message = "Addresses in list: ${merged.size}."
            refreshSearch()
        }
    }
    fun importAddresses(read: () -> String) {
        if (busy || split.mode == SplitTunnelSettings.DISABLED) return
        val mode = split.mode
        val previous = split.entries(mode)
        operation {
            val merged = withContext(Dispatchers.IO) { SplitTunnelImport.normalized(previous + SplitTunnelImport.json(read())) }
            split = split.withEntries(mode, merged)
            message = "Addresses in list: ${merged.size}."
            refreshSearch()
        }
    }
    fun remove(entry: SplitTunnelEntry) {
        if (busy) return
        split = split.withEntries(split.mode, split.activeEntries.filterNot { it.id == entry.id })
        refreshSearch(); notifyChange()
    }
    fun clearList() {
        if (busy) return
        split = split.withEntries(split.mode, emptyList())
        message = ""; expanded = false
        refreshSearch(); notifyChange()
    }
    fun save() {
        if (busy || !::store.isInitialized) return
        val excluded=excludedApplications.toList()
        val ipv6=ipv6Enabled
        val autoConnect = autoConnectOnRestart
        val amneziaSplit = amneziaSplitEnabled; val fullMinutes = fullTunnelMinutes
        val captcha = autoCaptcha; val recovery = autoRecovery; val interval = turnCheckInterval; val sessions = turnSessionCount
        val text = newAddress
        val draft = split
        operation {
            val saved = withContext(Dispatchers.IO) {
                val value = if (draft.mode != SplitTunnelSettings.DISABLED && text.isNotBlank()) {
                    draft.withEntries(draft.mode, SplitTunnelImport.normalized(draft.activeEntries + SplitTunnelImport.manual(text)))
                } else draft
                val previous = store.splitTunneling
                val previousApps=store.excludedApplications
                val previousIPv6=store.ipv6Enabled
                val previousAmneziaSplit=store.amneziaSplitEnabled
                store.saveSettings(captcha, recovery, value, interval, ipv6, excluded, autoConnect, amneziaSplit, fullMinutes, sessions)
                SimpleRuntime.applyAutomation(store)
                previousAmneziaSplit != store.amneziaSplitEnabled || previous != store.splitTunneling || previousIPv6!=store.ipv6Enabled || previousApps!=store.excludedApplications
            }
            routingChanged = saved
            finished = true
        }
    }
    fun prepareBackup() {
        if (busy || !::store.isInitialized || pendingExport != null) return
        val excluded=excludedApplications.toList()
        val ipv6=ipv6Enabled
        val autoConnect = autoConnectOnRestart
        val amneziaSplit = amneziaSplitEnabled; val fullMinutes = fullTunnelMinutes
        val captcha = autoCaptcha; val recovery = autoRecovery; val interval = turnCheckInterval; val sessions = turnSessionCount; val draft = split
        operation {
            pendingExport = withContext(Dispatchers.IO) { store.backup(captcha, recovery, draft, interval, ipv6=ipv6, excludedApps=excluded, autoConnect=autoConnect, amneziaSplit=amneziaSplit, fullMinutes=fullMinutes, turnSessions=sessions).encoded() }
            requestExport = true
        }
    }
    fun writeBackup(write: (String) -> Unit) {
        val data = pendingExport ?: return
        if (busy) return
        operation {
            withContext(Dispatchers.IO) { write(data) }
            pendingExport = null
            message = "Backup saved."
        }
    }
    fun cancelExport() { pendingExport = null; requestExport = false }
    fun readBackup(read: () -> String) {
        if (busy) return
        operation { pendingBackup = withContext(Dispatchers.IO) { ApplicationBackup.decode(read()) } }
    }
    fun restoreBackup(context: Context) {
        val backup = pendingBackup ?: return
        if (busy) return
        pendingBackup = null
        operation {
            CoreServiceManager.stopVService(context.applicationContext)
            val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
            while (withContext(Dispatchers.IO) { DaemonConnectionState.hasLiveService() }) {
                check(android.os.SystemClock.elapsedRealtime() < deadline) { "VPN is still stopping. Try importing again in a few seconds." }
                delay(100)
            }
            withContext(Dispatchers.IO) {
                store.restore(backup)
                SimpleRuntime.clearPreparedConfiguration(backup.selectedProfileID)
                SimpleRuntime.applyAutomation(store)
            }
            restored = true
            finished = true
        }
    }
}
