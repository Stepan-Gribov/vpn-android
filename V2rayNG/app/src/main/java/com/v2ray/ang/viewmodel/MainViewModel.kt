package com.v2ray.ang.viewmodel

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.AssetManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.ManagedDnsTunnelManager
import com.v2ray.ang.core.VkTurnManager
import com.v2ray.ang.dto.GroupMapItem
import com.v2ray.ang.dto.SubscriptionUpdateResult
import com.v2ray.ang.dto.TestServiceMessage
import com.v2ray.ang.dto.entities.ServersCache
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.extension.matchesPattern
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.MessageUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.regex.PatternSyntaxException

data class RecoveryUiState(
    val phase: String,
    val attempt: Int,
    val maximum: Int,
) {
    val isInProgress: Boolean
        get() = phase !in setOf("DISCONNECTED", "CONNECTED", "FAILED")

    val isFailed: Boolean
        get() = phase == "FAILED"

    companion object {
        fun fromPayload(payload: String?): RecoveryUiState? {
            val parts = payload?.split('|') ?: return null
            val phase = parts.getOrNull(0)?.takeIf(String::isNotBlank) ?: return null
            return RecoveryUiState(
                phase = phase,
                attempt = parts.getOrNull(1)?.toIntOrNull() ?: 0,
                maximum = parts.getOrNull(2)?.toIntOrNull() ?: 0,
            )
        }
    }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private var serverList = mutableListOf<String>() // MmkvManager.decodeServerList()
    var subscriptionId: String = MmkvManager.decodeSettingsString(AppConfig.CACHE_SUBSCRIPTION_ID, "").orEmpty()
    var keywordFilter = ""
    val serversCache = mutableListOf<ServersCache>()

    /** How many profiles the last [updateCache] pass dropped because hide-failed is enabled. */
    var hiddenFailedCount = 0
        private set
    val isRunning by lazy { MutableLiveData<Boolean>() }
    val updateListAction by lazy { MutableLiveData<Int>() }
    val updateTestResultAction by lazy { MutableLiveData<String>() }
    val vkTurnCaptchaAction by lazy { MutableLiveData<String>() }
    val serviceStartedAction by lazy { MutableLiveData<Unit>() }
    val serviceStartFailureAction by lazy { MutableLiveData<String?>() }
    val serviceStoppedAction by lazy { MutableLiveData<Unit>() }
    val connectionDroppedAction by lazy { MutableLiveData<String>() }
    val connectionRestoredAction by lazy { MutableLiveData<Long>() }
    val reconnectFailedAction by lazy { MutableLiveData<String>() }
    val recoveryState: MutableLiveData<RecoveryUiState?> = MutableLiveData()
    val currentDelayAction by lazy { MutableLiveData<Long>() }
    val connectionTestResultAction by lazy { MutableLiveData<Boolean>() }

    /**
     * Refer to the official documentation for [registerReceiver](https://developer.android.com/reference/androidx/core/content/ContextCompat#registerReceiver(android.content.Context,android.content.BroadcastReceiver,android.content.IntentFilter,int):
     * `registerReceiver(Context, BroadcastReceiver, IntentFilter, int)`.
     */
    fun startListenBroadcast() {
        if (SettingsManager.isConnectionDesired()) {
            recoveryState.value = RecoveryUiState(
                phase = "RECONNECT_SCHEDULED",
                attempt = SettingsManager.getAutoReconnectAttempt(),
                maximum = SettingsManager.getAutoReconnectMaxRetries(),
            )
        }
        isRunning.value = false
        val mFilter = IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY)
        ContextCompat.registerReceiver(getApplication(), mMsgReceiver, mFilter, Utils.receiverFlags())
        MessageUtil.sendMsg2Service(getApplication(), AppConfig.MSG_REGISTER_CLIENT, "")
    }

    fun isRecoveryInProgress(): Boolean = recoveryState.value?.isInProgress == true

    /**
     * Called when the ViewModel is cleared.
     */
    override fun onCleared() {
        getApplication<AngApplication>().unregisterReceiver(mMsgReceiver)
        LogUtil.i(AppConfig.TAG, "Main ViewModel is cleared")
        super.onCleared()
    }

    /**
     * Reloads the server list based on current subscription filter.
     */
    fun reloadServerList() {
        serverList = if (subscriptionId.isEmpty()) {
            MmkvManager.decodeAllServerList()
        } else {
            MmkvManager.decodeServerList(subscriptionId)
        }

        updateCache()
        updateListAction.value = -1
    }

    /**
     * Removes a server by its GUID.
     * @param guid The GUID of the server to remove.
     */
    fun removeServer(guid: String) {
        serverList.remove(guid)
        MmkvManager.removeServer(guid)
        val index = getPosition(guid)
        if (index >= 0) {
            serversCache.removeAt(index)
        }
    }

    /**
     * Swaps the positions of two servers.
     * @param fromPosition The initial position of the server.
     * @param toPosition The target position of the server.
     */
    fun swapServer(fromPosition: Int, toPosition: Int) {
        if (subscriptionId.isEmpty()) {
            return
        }
        // A filtered view (search or hide-failed) has different indices than the stored
        // list; swapping by display position would reorder the wrong profiles.
        if (serversCache.size != serverList.size) {
            return
        }

        val fromGuid = serversCache.getOrNull(fromPosition)?.guid ?: return
        val toGuid = serversCache.getOrNull(toPosition)?.guid ?: return
        if (
            VkTurnManager.isTurnProfile(MmkvManager.decodeServerConfig(fromGuid)) ||
            VkTurnManager.isTurnProfile(MmkvManager.decodeServerConfig(toGuid)) ||
            ManagedDnsTunnelManager.isManagedDnsTunnelProfile(MmkvManager.decodeServerConfig(fromGuid)) ||
            ManagedDnsTunnelManager.isManagedDnsTunnelProfile(MmkvManager.decodeServerConfig(toGuid))
        ) {
            return
        }
        val storedFrom = serverList.indexOf(fromGuid)
        val storedTo = serverList.indexOf(toGuid)
        if (storedFrom < 0 || storedTo < 0) return

        Collections.swap(serverList, storedFrom, storedTo)
        updateCache()

        MmkvManager.encodeServerList(serverList, subscriptionId)
    }

    /**
     * Updates the cache of servers.
     */
    @Synchronized
    fun updateCache() {
        serversCache.clear()
        hiddenFailedCount = 0
        val hideFailed = isHideFailedEnabled()
        val kw = keywordFilter.trim()
        val searchRegex = try {
            if (kw.isNotEmpty()) Regex(kw, setOf(RegexOption.IGNORE_CASE)) else null
        } catch (e: PatternSyntaxException) {
            null // Fallback to literal search if regex is invalid
        }
        for (guid in serverList) {
            val profile = MmkvManager.decodeServerConfig(guid) ?: continue
            if (kw.isNotEmpty()) {
                val remarks = profile.remarks
                val description = profile.description.orEmpty()
                val server = profile.server.orEmpty()
                val protocol = profile.configType.name
                val matches = remarks.matchesPattern(searchRegex, kw)
                        || description.matchesPattern(searchRegex, kw)
                        || server.matchesPattern(searchRegex, kw)
                        || protocol.matchesPattern(searchRegex, kw)
                if (!matches) {
                    continue
                }
            }

            if (hideFailed &&
                (MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: 0L) < 0L &&
                !VkTurnManager.isTurnProfile(profile) &&
                !ManagedDnsTunnelManager.isManagedDnsTunnelProfile(profile)
            ) {
                hiddenFailedCount++
                continue
            }

            serversCache.add(ServersCache(guid, profile))
        }
        // Keep the two managed experimental transports together above ordinary profiles.
        serversCache.sortWith(
            compareByDescending<ServersCache> {
                when {
                    VkTurnManager.isTurnProfile(it.profile) -> 2
                    ManagedDnsTunnelManager.isManagedDnsTunnelProfile(it.profile) -> 1
                    else -> 0
                }
            },
        )
    }

    /** Whether profiles that failed their last connectivity check are filtered out of the list. */
    fun isHideFailedEnabled(): Boolean =
        MmkvManager.decodeSettingsBool(AppConfig.PREF_HIDE_FAILED_PROFILES, false)

    /**
     * Toggles hiding of failed profiles and reloads the list.
     * @return The new state: true when failed profiles are now hidden.
     */
    fun toggleHideFailed(): Boolean {
        val hide = !isHideFailedEnabled()
        MmkvManager.encodeSettings(AppConfig.PREF_HIDE_FAILED_PROFILES, hide)
        reloadServerList()
        return hide
    }

    /**
     * Updates the configuration via subscription for all servers.
     * @return Detailed result of the subscription update operation.
     */
    fun updateConfigViaSubAll(): SubscriptionUpdateResult {
        if (subscriptionId.isEmpty()) {
            return AngConfigManager.updateConfigViaSubAll()
        } else {
            val subItem = MmkvManager.decodeSubscription(subscriptionId) ?: return SubscriptionUpdateResult()
            return AngConfigManager.updateConfigViaSub(SubscriptionCache(subscriptionId, subItem))
        }
    }

    /**
     * Exports all servers.
     * @return The number of exported servers.
     */
    fun exportAllServer(): Int {
        val serverListCopy =
            if (subscriptionId.isEmpty() && keywordFilter.isEmpty()) {
                serverList
            } else {
                serversCache.map { it.guid }.toList()
            }

        val ret = AngConfigManager.shareNonCustomConfigsToClipboard(
            getApplication<AngApplication>(),
            serverListCopy
        )
        return ret
    }

    /**
     * Tests the real ping for all servers.
     */
    fun testAllRealPing() {
        MessageUtil.sendMsg2TestService(
            getApplication(),
            TestServiceMessage(key = AppConfig.MSG_MEASURE_CONFIG_CANCEL)
        )
        MmkvManager.clearAllTestDelayResults(serversCache.map { it.guid }.toList())
        updateListAction.value = -1

        viewModelScope.launch(Dispatchers.Default) {
            if (serversCache.isEmpty()) {
                return@launch
            }
            MessageUtil.sendMsg2TestService(
                getApplication(),
                TestServiceMessage(
                    key = AppConfig.MSG_MEASURE_CONFIG_START,
                    subscriptionId = subscriptionId,
                    serverGuids = if (keywordFilter.isNotEmpty()) serversCache.map { it.guid } else emptyList()
                )
            )
        }
    }

    /**
     * Tests the real ping for one profile without touching other results.
     * @param guid The profile to measure.
     */
    fun testSingleRealPing(guid: String) {
        MmkvManager.clearAllTestDelayResults(listOf(guid))
        updateListAction.value = getPosition(guid)
        MessageUtil.sendMsg2TestService(
            getApplication(),
            TestServiceMessage(
                key = AppConfig.MSG_MEASURE_CONFIG_START,
                subscriptionId = subscriptionId,
                serverGuids = listOf(guid)
            )
        )
    }

    /**
     * Tests the real ping for the current server.
     */
    fun testCurrentServerRealPing() {
        MessageUtil.sendMsg2Service(getApplication(), AppConfig.MSG_MEASURE_DELAY, "")
    }

    /** Returns the profiles exactly as they are currently displayed to the user. */
    fun orderedServerGuids(): List<String> = serversCache.map { it.guid }

    /**
     * Changes the subscription ID.
     * @param id The new subscription ID.
     */
    fun subscriptionIdChanged(id: String) {
        if (subscriptionId != id) {
            subscriptionId = id
            MmkvManager.encodeSettings(AppConfig.CACHE_SUBSCRIPTION_ID, subscriptionId)
        }
        reloadServerList()
    }

    /**
     * Gets the subscriptions.
     * @param context The context.
     * @return A pair of lists containing the subscription IDs and remarks.
     */
    fun getSubscriptions(context: Context): List<GroupMapItem> {
        val subscriptions = MmkvManager.decodeSubscriptions()
        if (subscriptionId.isNotEmpty()
            && !subscriptions.map { it.guid }.contains(subscriptionId)
        ) {
            subscriptionIdChanged("")
        }

        val groups = mutableListOf<GroupMapItem>()
        groups.add(
            GroupMapItem(
                id = "",
                remarks = context.getString(R.string.filter_config_all)
            )
        )
        subscriptions.forEach { sub ->
            groups.add(
                GroupMapItem(
                    id = sub.guid,
                    remarks = sub.subscription.remarks
                )
            )
        }
        return groups
    }

    /**
     * Gets the position of a server by its GUID.
     * @param guid The GUID of the server.
     * @return The position of the server.
     */
    fun getPosition(guid: String): Int {
        serversCache.forEachIndexed { index, it ->
            if (it.guid == guid)
                return index
        }
        return -1
    }

    /**
     * Removes duplicate servers.
     * Excludes servers with complex types (Custom, PolicyGroup, or ProxyChain) from duplicate comparison.
     * @return The number of removed servers.
     */
    fun removeDuplicateServer(): Int {
        val serversCacheCopy = serversCache.toList().toMutableList()
        val deleteServer = mutableListOf<String>()

        serversCacheCopy.forEachIndexed { index, sc ->
            val profile = sc.profile
            // Skip if this profile has a complex config type
            if (profile.configType.isComplexType()) {
                return@forEachIndexed
            }

            serversCacheCopy.forEachIndexed { index2, sc2 ->
                if (index2 > index) {
                    val profile2 = sc2.profile
                    // Skip if the second profile has a complex config type
                    if (profile2.configType.isComplexType()) {
                        return@forEachIndexed
                    }

                    if (profile == profile2 && !deleteServer.contains(sc2.guid)) {
                        deleteServer.add(sc2.guid)
                    }
                }
            }
        }
        for (it in deleteServer) {
            MmkvManager.removeServer(it)
        }

        return deleteServer.count()
    }

    /**
     * Removes all servers.
     * @return The number of removed servers.
     */
    fun removeAllServer(): Int {
        val count =
            if (subscriptionId.isEmpty() && keywordFilter.isEmpty()) {
                MmkvManager.removeAllServer()
            } else {
                val serversCopy = serversCache.toList()
                for (item in serversCopy) {
                    MmkvManager.removeServer(item.guid)
                }
                serversCache.toList().count()
            }
        return count
    }

    /**
     * Removes invalid servers.
     * @return The number of removed servers.
     */
    fun removeInvalidServer(): Int {
        var count = 0
        if (subscriptionId.isEmpty() && keywordFilter.isEmpty()) {
            count += MmkvManager.removeInvalidServer("")
        } else {
            val serversCopy = serversCache.toList()
            for (item in serversCopy) {
                count += MmkvManager.removeInvalidServer(item.guid)
            }
        }
        return count
    }

    /**
     * Sorts servers by their test results.
     */
    fun sortByTestResults() {
        if (subscriptionId.isEmpty()) {
            MmkvManager.decodeSubsList().forEach { guid ->
                sortByTestResultsForSub(guid)
            }
        } else {
            sortByTestResultsForSub(subscriptionId)
        }
    }

    /**
     * Sorts servers by their test results for a specific subscription.
     * @param subId The subscription ID to sort servers for.
     */
    private fun sortByTestResultsForSub(subId: String) {
        data class ServerDelay(
            var guid: String,
            var testDelayMillis: Long,
            var managedRank: Int,
        )

        val serverDelays = mutableListOf<ServerDelay>()
        val serverListToSort = MmkvManager.decodeServerList(subId)

        serverListToSort.forEach { key ->
            val delay = MmkvManager.decodeServerAffiliationInfo(key)?.testDelayMillis ?: 0L
            serverDelays.add(
                ServerDelay(
                    key,
                    if (delay <= 0L) 999999 else delay,
                    MmkvManager.decodeServerConfig(key)?.let { profile ->
                        when {
                            VkTurnManager.isTurnProfile(profile) -> 2
                            ManagedDnsTunnelManager.isManagedDnsTunnelProfile(profile) -> 1
                            else -> 0
                        }
                    } ?: 0,
                ),
            )
        }
        serverDelays.sortWith(
            compareByDescending<ServerDelay> { it.managedRank }
                .thenBy { it.testDelayMillis },
        )

        val sortedServerList = serverDelays.map { it.guid }.toMutableList()

        // Save the sorted list for this subscription
        MmkvManager.encodeServerList(sortedServerList, subId)
    }


    /**
     * Initializes assets.
     * @param assets The asset manager.
     */
    fun initAssets(assets: AssetManager) {
        viewModelScope.launch(Dispatchers.Default) {
            SettingsManager.initAssets(getApplication<AngApplication>(), assets)
        }
    }

    /**
     * Filters the configuration by a keyword.
     * @param keyword The keyword to filter by.
     */
    fun filterConfig(keyword: String) {
        if (keyword == keywordFilter) {
            return
        }
        keywordFilter = keyword
        reloadServerList()
    }

    fun findSubscriptionIdBySelect(): String? {
        // Get the selected server GUID
        val selectedGuid = MmkvManager.getSelectServer()
        if (selectedGuid.isNullOrEmpty()) {
            return null
        }

        val config = MmkvManager.decodeServerConfig(selectedGuid)
        return config?.subscriptionId
    }

    fun onTestsFinished() {
        viewModelScope.launch(Dispatchers.Default) {
            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_AUTO_REMOVE_INVALID_AFTER_TEST)) {
                removeInvalidServer()
            }

            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_AUTO_SORT_AFTER_TEST)) {
                sortByTestResults()
            }

            withContext(Dispatchers.Main) {
                reloadServerList()
            }
        }
    }

    private val mMsgReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_STATE_RUNNING -> {
                    recoveryState.value = null
                    isRunning.value = true
                }

                AppConfig.MSG_STATE_NOT_RUNNING -> {
                    isRunning.value = false
                }

                AppConfig.MSG_STATE_START_SUCCESS -> {
                    getApplication<AngApplication>().toastSuccess(R.string.toast_services_success)
                    recoveryState.value = null
                    isRunning.value = true
                    serviceStartedAction.value = Unit
                }

                AppConfig.MSG_STATE_START_FAILURE -> {
                    val errorMessage = intent.getStringExtra("content")
                    if (!errorMessage.isNullOrBlank()) {
                        getApplication<AngApplication>().toastError(errorMessage)
                    } else {
                        getApplication<AngApplication>().toastError(R.string.toast_services_failure)
                    }
                    recoveryState.value = null
                    isRunning.value = false
                    serviceStartFailureAction.value = errorMessage
                }

                AppConfig.MSG_STATE_CONNECTION_DROPPED -> {
                    isRunning.value = false
                    connectionDroppedAction.value = intent.getStringExtra("content")
                        ?: getApplication<AngApplication>().getString(
                            R.string.vpn_connection_dropped_default,
                        )
                }

                AppConfig.MSG_STATE_CONNECTION_RESTORED -> {
                    recoveryState.value = null
                    isRunning.value = true
                    intent.getStringExtra("content")?.toLongOrNull()?.let { delayMillis ->
                        connectionRestoredAction.value = delayMillis
                    }
                }

                AppConfig.MSG_STATE_RECONNECT_FAILED -> {
                    if (recoveryState.value?.isFailed != true) {
                        recoveryState.value = RecoveryUiState("FAILED", 0, 0)
                    }
                    isRunning.value = false
                    reconnectFailedAction.value = intent.getStringExtra("content")
                        ?: getApplication<AngApplication>().getString(
                            R.string.reconnect_notification_failed_title,
                        )
                }

                AppConfig.MSG_STATE_STOP_SUCCESS -> {
                    if (!isRecoveryInProgress()) {
                        recoveryState.value = null
                    }
                    isRunning.value = false
                    serviceStoppedAction.value = Unit
                }

                AppConfig.MSG_STATE_RECOVERY -> {
                    val state = RecoveryUiState.fromPayload(intent.getStringExtra("content"))
                        ?: return
                    when (state.phase) {
                        "CONNECTED" -> {
                            recoveryState.value = null
                            isRunning.value = true
                        }

                        "DISCONNECTED" -> {
                            recoveryState.value = null
                            isRunning.value = false
                        }

                        else -> {
                            recoveryState.value = state
                            isRunning.value = false
                        }
                    }
                }

                AppConfig.MSG_MEASURE_DELAY_SUCCESS -> {
                    // A probe can finish after the user has stopped the VPN. Do not let that
                    // stale result replace the disconnected state on the connection card.
                    if (isRunning.value == true) {
                        updateTestResultAction.value = intent.getStringExtra("content")
                    }
                }

                AppConfig.MSG_MEASURE_DELAY_RESULT -> {
                    connectionTestResultAction.value =
                        intent.getStringExtra("content")?.toBooleanStrictOrNull() ?: false
                }

                AppConfig.MSG_MEASURE_DELAY_VALUE -> {
                    intent.getStringExtra("content")?.toLongOrNull()?.let { delayMillis ->
                        currentDelayAction.value = delayMillis
                    }
                }

                AppConfig.MSG_MEASURE_CONFIG_SUCCESS -> {
                    val content = intent.getStringExtra("content")
                    updateListAction.value = getPosition(content ?: "")
                }

                AppConfig.MSG_MEASURE_CONFIG_NOTIFY -> {
                    val content = intent.getStringExtra("content")
                    updateTestResultAction.value =
                        getApplication<AngApplication>().getString(R.string.connection_runing_task_left, content)
                }

                AppConfig.MSG_MEASURE_CONFIG_FINISH -> {
                    val content = intent.getStringExtra("content")
                    if (content == "0") {
                        onTestsFinished()
                    }
                }

                AppConfig.MSG_VK_TURN_CAPTCHA_REQUIRED -> {
                    updateTestResultAction.value =
                        getApplication<AngApplication>().getString(
                            if (com.v2ray.ang.core.VkTurnManager.isAutoCaptchaEnabled()) {
                                R.string.vk_turn_captcha_required_status
                            } else {
                                R.string.vk_turn_captcha_manual_status
                            },
                        )
                    vkTurnCaptchaAction.value = intent.getStringExtra("content")
                        ?: com.v2ray.ang.core.VkTurnManager.CAPTCHA_URL
                }

                AppConfig.MSG_VK_TURN_CAPTCHA_COMPLETED,
                AppConfig.MSG_VK_TURN_READY,
                AppConfig.MSG_VK_TURN_FAILURE -> {
                    updateTestResultAction.value = intent.getStringExtra("content")
                }
            }
        }
    }
}
