package com.v2ray.ang.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.navigation.NavigationView
import com.google.android.material.tabs.TabLayoutMediator
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.ConnectionFallbackPlan
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.core.ManagedDnsTunnelManager
import com.v2ray.ang.core.VkTurnManager
import com.v2ray.ang.databinding.ActivityMainBinding
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SubscriptionUpdater
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel
import com.v2ray.ang.viewmodel.RecoveryUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : HelperBaseActivity(), NavigationView.OnNavigationItemSelectedListener {
    companion object {
        private const val FALLBACK_PROBE_DELAY_MS = 1_200L
        private const val FALLBACK_STOP_TIMEOUT_MS = 1_500L
        private const val FALLBACK_RESTART_DELAY_MS = 500L
    }

    private enum class ConnectionUiState { IDLE, CONNECTING, STOPPING, RECONNECTING, CONNECTED }

    private val binding by lazy {
        ActivityMainBinding.inflate(layoutInflater)
    }

    private var connectionUiState = ConnectionUiState.IDLE

    val mainViewModel: MainViewModel by viewModels()
    private lateinit var groupPagerAdapter: GroupPagerAdapter
    private var tabMediator: TabLayoutMediator? = null
    private var fallbackPlan: ConnectionFallbackPlan? = null
    private var fallbackProbeJob: Job? = null
    private var fallbackAwaitingProbe = false
    private var fallbackWaitingForStop = false
    private var pendingDirectGuid: String? = null
    private var manualCaptchaOpen = false
    private var connectionStatusDialog: AlertDialog? = null

    private val captchaActivityLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            manualCaptchaOpen = false
        }

    private val requestVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            startSelectedProfile()
        } else {
            cancelFallback()
            pendingDirectGuid = null
            applyRunningState(isLoading = false, isRunning = false)
        }
    }
    private val requestActivityLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val targetSubscriptionId = result.data
            ?.getStringExtra(VkTurnSetupActivity.EXTRA_OPEN_SUBSCRIPTION_ID)
            ?: result.data?.getStringExtra(ManagedDnsTunnelSetupActivity.EXTRA_OPEN_SUBSCRIPTION_ID)
        val selectedTurnGuid = result.data
            ?.getStringExtra(VkTurnSetupActivity.EXTRA_SELECTED_TURN_GUID)
        val dnsGuid = result.data?.getStringExtra(DnsTunnelSetupActivity.EXTRA_CONNECTION_GUID)
        val selectedDnsGuid = result.data
            ?.getStringExtra(ManagedDnsTunnelSetupActivity.EXTRA_SELECTED_DNS_GUID)
        val selectedSpecialGuid = selectedTurnGuid ?: selectedDnsGuid

        if (SettingsChangeManager.consumeRestartService() && mainViewModel.isRunning.value == true) {
            restartV2Ray()
        }
        if (!targetSubscriptionId.isNullOrBlank()) {
            mainViewModel.subscriptionIdChanged(targetSubscriptionId)
        }
        if (SettingsChangeManager.consumeSetupGroupTab()) {
            setupGroupTab()
        }
        if (!selectedSpecialGuid.isNullOrBlank()) {
            mainViewModel.reloadServerList()
            if (!selectedTurnGuid.isNullOrBlank()) {
                toast(R.string.vk_turn_profile_added_selected)
            }
            binding.viewPager.postDelayed({ locateSelectedServer() }, 350)
        }
        if (!dnsGuid.isNullOrBlank()) {
            val idle = mainViewModel.isRunning.value != true && !mainViewModel.isRecoveryInProgress()
            if (idle) MmkvManager.setSelectServer(dnsGuid)
            MmkvManager.decodeServerConfig(dnsGuid)?.let { mainViewModel.subscriptionIdChanged(it.subscriptionId) }
            setupGroupTab()
            mainViewModel.reloadServerList()
            toast(R.string.dns_tunnel_saved)
            if (idle) binding.viewPager.postDelayed({ locateSelectedServer() }, 350)
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupToolbar(binding.toolbar, false, getString(R.string.app_name))

        // setup viewpager and tablayout
        groupPagerAdapter = GroupPagerAdapter(this, emptyList())
        binding.viewPager.adapter = groupPagerAdapter
        binding.viewPager.isUserInputEnabled = true

        // setup navigation drawer
        setupNavigationDrawer()

        binding.fab.setOnClickListener { handleFabAction() }
        binding.layoutTest.setOnClickListener { handleLayoutTestClick() }
        binding.btnPingAll.setOnClickListener { pingAllProfiles() }
        binding.btnSortFastest.setOnClickListener { sortByTestResults() }

        setupGroupTab()
        setupViewModel()
        SubscriptionUpdater.sync()
        mainViewModel.reloadServerList()

        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {
        }
    }

    private fun setupNavigationDrawer() {
        val toggle = ActionBarDrawerToggle(
            this,
            binding.drawerLayout,
            binding.toolbar,
            R.string.navigation_drawer_open,
            R.string.navigation_drawer_close
        )
        binding.drawerLayout.addDrawerListener(toggle)
        toggle.syncState()
        binding.navView.setNavigationItemSelectedListener(this)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    private fun setupViewModel() {
        mainViewModel.updateTestResultAction.observe(this) { setTestState(it) }
        mainViewModel.currentDelayAction.observe(this) { delayMillis ->
            if (mainViewModel.isRunning.value == true) renderConnectionLatency(delayMillis)
        }
        mainViewModel.connectionDroppedAction.observe(this) { reason ->
            showConnectionDroppedDialog(reason)
        }
        mainViewModel.connectionRestoredAction.observe(this) { delayMillis ->
            showConnectionStatusDialog(
                R.string.reconnect_notification_restored_title,
                getString(R.string.reconnect_popup_restored_text, delayMillis),
            )
        }
        mainViewModel.reconnectFailedAction.observe(this) { message ->
            showConnectionStatusDialog(
                R.string.reconnect_notification_failed_title,
                message,
            )
        }
        mainViewModel.vkTurnCaptchaAction.observe(this) { captchaUrl ->
            handleVkCaptcha(captchaUrl)
        }
        mainViewModel.recoveryState.observe(this) { state ->
            if (state != null) renderRecoveryState(state)
        }
        mainViewModel.isRunning.observe(this) { isRunning ->
            val recovery = mainViewModel.recoveryState.value
            if (recovery != null) {
                renderRecoveryState(recovery)
            } else {
                applyRunningState(false, isRunning)
            }
        }
        mainViewModel.serviceStartedAction.observe(this) { handleFallbackServiceStarted() }
        mainViewModel.serviceStartFailureAction.observe(this) { error ->
            if (fallbackPlan != null) {
                failCurrentFallbackCandidate(error)
            }
        }
        mainViewModel.serviceStoppedAction.observe(this) {
            if (fallbackWaitingForStop) {
                continueFallbackAfterStop()
            } else {
                pendingDirectGuid?.let { guid ->
                    pendingDirectGuid = null
                    lifecycleScope.launch {
                        delay(FALLBACK_RESTART_DELAY_MS)
                        startSpecificProfile(guid)
                    }
                }
            }
        }
        mainViewModel.connectionTestResultAction.observe(this) { succeeded ->
            handleFallbackProbeResult(succeeded)
        }
        mainViewModel.startListenBroadcast()
        mainViewModel.initAssets(assets)
    }

    private fun handleVkCaptcha(captchaUrl: String?) {
        if (VkTurnManager.isAutoCaptchaEnabled()) {
            // The VPN daemon owns automatic verification so it also works with no Activity.
            return
        } else {
            if (manualCaptchaOpen) return
            manualCaptchaOpen = true
            captchaActivityLauncher.launch(
                Intent(this, VkCaptchaActivity::class.java)
                    .putExtra(VkCaptchaActivity.EXTRA_CAPTCHA_URL, captchaUrl),
            )
        }
    }

    private fun showConnectionDroppedDialog(reason: String) {
        if (
            !SettingsManager.areAutoReconnectNotificationsEnabled() ||
            isFinishing ||
            isDestroyed
        ) return
        val message = getString(
            if (SettingsManager.isAutoReconnectOnDrop()) {
                R.string.vpn_connection_dropped_reconnecting
            } else {
                R.string.vpn_connection_dropped_message
            },
            reason,
        )
        showConnectionStatusDialog(
            R.string.vpn_connection_dropped_title,
            message,
            R.string.vpn_connection_reconnect_now,
        ) {
            if (mainViewModel.isRunning.value != true) {
                MmkvManager.getSelectServer()?.let { guid ->
                    if (mainViewModel.isRecoveryInProgress()) {
                        CoreServiceManager.restartVService(this, guid)
                    } else {
                        startSpecificProfile(guid)
                    }
                }
            }
        }
    }

    private fun showConnectionStatusDialog(
        title: Int,
        message: String,
        positiveActionLabel: Int = android.R.string.ok,
        positiveAction: (() -> Unit)? = null,
    ) {
        if (
            !SettingsManager.areAutoReconnectNotificationsEnabled() ||
            isFinishing ||
            isDestroyed
        ) return
        connectionStatusDialog?.dismiss()
        connectionStatusDialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positiveActionLabel) { _, _ -> positiveAction?.invoke() }
            .create()
            .also { dialog ->
                dialog.setOnDismissListener { connectionStatusDialog = null }
                dialog.show()
            }
    }

    private fun setupGroupTab() {
        val groups = mainViewModel.getSubscriptions(this)
        groupPagerAdapter.update(groups)

        tabMediator?.detach()
        tabMediator = TabLayoutMediator(binding.tabGroup, binding.viewPager) { tab, position ->
            groupPagerAdapter.groups.getOrNull(position)?.let {
                tab.text = it.remarks
                tab.tag = it.id
            }
        }.also { it.attach() }

        val targetIndex = groups.indexOfFirst { it.id == mainViewModel.subscriptionId }.takeIf { it >= 0 } ?: (groups.size - 1)
        binding.viewPager.setCurrentItem(targetIndex, false)

        binding.tabGroup.isVisible = groups.size > 1
        refreshGroupTabTitles(true)
    }

    fun refreshGroupTabTitles(refreshAll: Boolean = false) {
        val groupsToRefresh = if (refreshAll || mainViewModel.subscriptionId.isEmpty()) {
            groupPagerAdapter.groups
        } else {
            groupPagerAdapter.groups.filter { it.id == mainViewModel.subscriptionId }
        }

        groupsToRefresh.forEach { group ->
            val tabIndex = groupPagerAdapter.groups.indexOfFirst { it.id == group.id }
            if (tabIndex >= 0) {
                val count = if (group.id.isEmpty()) {
                    MmkvManager.decodeAllServerList().distinct().size
                } else {
                    MmkvManager.decodeServerList(group.id).size
                }
                binding.tabGroup.getTabAt(tabIndex)?.text = "${group.remarks} ($count)"
            }
        }
    }

    private fun handleFabAction() {
        when (connectionUiState) {
            ConnectionUiState.STOPPING -> return
            ConnectionUiState.CONNECTING,
            ConnectionUiState.RECONNECTING -> {
                cancelConnectionAttempt()
                return
            }
            else -> {}
        }

        if (mainViewModel.isRunning.value == true) {
            applyRunningState(isLoading = true, isRunning = false, stopping = true)
            cancelFallback()
            pendingDirectGuid = null
            CoreServiceManager.stopVService(this)
        } else {
            val selectedGuid = MmkvManager.getSelectServer()
            val selectedProfile = selectedGuid?.let(MmkvManager::decodeServerConfig)
            if (selectedGuid != null && (
                    VkTurnManager.isTurnProfile(selectedProfile) ||
                        ManagedDnsTunnelManager.isManagedDnsTunnelProfile(selectedProfile)
                )) {
                connectSpecificProfile(selectedGuid)
            } else {
                applyRunningState(isLoading = true, isRunning = false)
                startProfilesInOrder()
            }
        }
    }

    private fun cancelConnectionAttempt() {
        cancelFallback()
        pendingDirectGuid = null
        CoreServiceManager.stopVService(this)
        // The service may have died during an app update, so no DISCONNECTED broadcast
        // will arrive. A user cancellation must also clear the cached recovery state.
        mainViewModel.recoveryState.value = null
        applyRunningState(isLoading = false, isRunning = false)
    }

    private fun startProfilesInOrder() {
        val candidates = mainViewModel.orderedServerGuids().map { guid ->
            val profile = MmkvManager.decodeServerConfig(guid)
            ConnectionFallbackPlan.Candidate(
                guid = guid,
                delayMillis = MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: 0L,
                automaticEligible = !(
                    profile != null &&
                        VkTurnManager.isTurnProfile(profile) &&
                        !com.v2ray.ang.core.DnsTunnelProfiles.isDns(profile) &&
                        VkTurnManager.getProvider() == VkTurnManager.Provider.VK
                ),
            )
        }
        fallbackPlan = ConnectionFallbackPlan.createFastest(candidates)
        fallbackWaitingForStop = false
        fallbackAwaitingProbe = false
        pendingDirectGuid = null

        if (fallbackPlan == null) {
            applyRunningState(isLoading = false, isRunning = false)
            toast(R.string.toast_no_profiles)
            openVkTurnSetup()
            return
        }

        tryCurrentFallbackCandidate()
    }

    private fun tryCurrentFallbackCandidate() {
        val plan = fallbackPlan ?: return
        val guid = plan.currentGuid
        val profile = MmkvManager.decodeServerConfig(guid)
        if (profile == null) {
            advanceFallbackPlan()
            return
        }

        MmkvManager.setSelectServer(guid)
        mainViewModel.reloadServerList()
        setTestState(
            getString(
                R.string.connection_fallback_trying,
                plan.attemptNumber,
                plan.size,
                profile.remarks,
            ),
        )
        applyRunningState(isLoading = true, isRunning = false)

        if (VkTurnManager.isTurnProfile(profile) && !VkTurnManager.isConfigured(profile)) {
            failCurrentFallbackCandidate(getString(if (com.v2ray.ang.core.DnsTunnelProfiles.isDns(profile))
                R.string.dns_tunnel_refresh_required else R.string.vk_turn_setup_required), serviceMayBeRunning = false)
            return
        }
        if (ManagedDnsTunnelManager.isManagedDnsTunnelProfile(profile) && !ManagedDnsTunnelManager.isConfigured(profile)) {
            failCurrentFallbackCandidate(getString(R.string.managed_dns_tunnel_setup_required), serviceMayBeRunning = false)
            return
        }

        startSelectedProfileWithPermission()
    }

    fun connectSpecificProfile(guid: String) {
        val profile = MmkvManager.decodeServerConfig(guid) ?: return
        if (ManagedDnsTunnelManager.isManagedDnsTunnelProfile(profile) && !ManagedDnsTunnelManager.isConfigured(profile)) {
            MmkvManager.setSelectServer(guid)
            mainViewModel.reloadServerList()
            toast(R.string.managed_dns_tunnel_setup_required)
            openManagedDnsTunnelSetup()
            return
        }
        val isCurrentConnection =
            mainViewModel.isRunning.value == true && guid == MmkvManager.getSelectServer()

        cancelFallback()
        pendingDirectGuid = null
        if (mainViewModel.isRecoveryInProgress()) {
            MmkvManager.setSelectServer(guid)
            mainViewModel.reloadServerList()
            setTestState(getString(R.string.connection_direct_connecting, profile.remarks))
            CoreServiceManager.restartVService(this, guid)
            return
        }
        if (isCurrentConnection) {
            applyRunningState(isLoading = true, isRunning = false, stopping = true)
            CoreServiceManager.stopVService(this)
            return
        }

        if (mainViewModel.isRunning.value == true) {
            pendingDirectGuid = guid
            applyRunningState(isLoading = true, isRunning = false)
            setTestState(getString(R.string.connection_direct_connecting, profile.remarks))
            CoreServiceManager.stopVService(this)
        } else {
            startSpecificProfile(guid)
        }
    }

    private fun startSpecificProfile(guid: String) {
        val profile = MmkvManager.decodeServerConfig(guid) ?: return
        MmkvManager.setSelectServer(guid)
        mainViewModel.reloadServerList()
        setTestState(getString(R.string.connection_direct_connecting, profile.remarks))
        applyRunningState(isLoading = true, isRunning = false)
        startSelectedProfileWithPermission()
    }

    private fun startSelectedProfileWithPermission() {
        if (selectedProfileNeedsVpn()) {
            val intent = VpnService.prepare(this)
            if (intent == null) {
                startSelectedProfile()
            } else {
                requestVpnPermission.launch(intent)
            }
        } else {
            startSelectedProfile()
        }
    }

    private fun startSelectedProfile() {
        if (!startV2Ray() && fallbackPlan != null) {
            failCurrentFallbackCandidate(serviceMayBeRunning = false)
        }
    }

    /**
     * Managed TURN, DNS and AmneziaWG routes always use Android's VPN service even when an older
     * installation still has "Proxy only" selected globally.
     */
    private fun selectedProfileNeedsVpn(): Boolean {
        if (SettingsManager.isVpnMode()) return true
        val selectedGuid = MmkvManager.getSelectServer().orEmpty()
        val selected = MmkvManager.decodeServerConfig(selectedGuid)
        return VkTurnManager.isTurnProfile(selected) ||
            ManagedDnsTunnelManager.isManagedDnsTunnelProfile(selected) ||
            selected?.configType == EConfigType.AMNEZIAWG
    }

    private fun handleLayoutTestClick() {
        when {
            mainViewModel.isRunning.value == true -> {
                setTestState(getString(R.string.connection_test_testing))
                mainViewModel.testCurrentServerRealPing()
            }

            connectionUiState == ConnectionUiState.IDLE -> handleFabAction()

            else -> {} // connecting or stopping: the FAB handles cancel
        }
    }

    private fun pingAllProfiles() {
        toast(getString(R.string.connection_test_testing_count, mainViewModel.serversCache.count()))
        mainViewModel.testAllRealPing()
    }

    fun toggleHideFailedProfiles() {
        val hiding = mainViewModel.toggleHideFailed()
        invalidateOptionsMenu()
        when {
            hiding && mainViewModel.hiddenFailedCount > 0 ->
                toast(getString(R.string.toast_hidden_failed, mainViewModel.hiddenFailedCount))

            hiding -> toast(R.string.toast_hide_failed_armed)

            else -> toast(R.string.toast_showing_all)
        }
    }

    private fun applyHideFailedMenuState(menu: Menu?) {
        val item = menu?.findItem(R.id.hide_failed) ?: return
        val hiding = mainViewModel.isHideFailedEnabled()
        item.isChecked = hiding
        item.setTitle(if (hiding) R.string.action_show_failed else R.string.action_hide_failed)
    }

    private fun handleFallbackServiceStarted() {
        val plan = fallbackPlan ?: return
        val profile = MmkvManager.decodeServerConfig(plan.currentGuid) ?: return
        fallbackProbeJob?.cancel()
        fallbackProbeJob = lifecycleScope.launch {
            setTestState(getString(R.string.connection_fallback_checking, profile.remarks))
            delay(FALLBACK_PROBE_DELAY_MS)
            if (fallbackPlan === plan && mainViewModel.isRunning.value == true) {
                fallbackAwaitingProbe = true
                mainViewModel.testCurrentServerRealPing()
            }
        }
    }

    private fun handleFallbackProbeResult(succeeded: Boolean) {
        if (!fallbackAwaitingProbe) {
            return
        }
        fallbackAwaitingProbe = false

        val plan = fallbackPlan ?: return
        val profileName = MmkvManager.decodeServerConfig(plan.currentGuid)?.remarks.orEmpty()
        if (succeeded) {
            fallbackPlan = null
            fallbackProbeJob = null
            fallbackWaitingForStop = false
            setTestState(getString(R.string.connection_fallback_connected, profileName))
        } else {
            failCurrentFallbackCandidate()
        }
    }

    private fun failCurrentFallbackCandidate(
        error: String? = null,
        serviceMayBeRunning: Boolean = true,
    ) {
        val plan = fallbackPlan ?: return
        if (fallbackWaitingForStop) {
            return
        }

        fallbackAwaitingProbe = false
        fallbackProbeJob?.cancel()
        val profileName = MmkvManager.decodeServerConfig(plan.currentGuid)?.remarks.orEmpty()
        setTestState(getString(R.string.connection_fallback_switching, profileName))
        if (!error.isNullOrBlank()) {
            LogUtil.w(AppConfig.TAG, "Connection fallback: $profileName failed: $error")
        }

        if (serviceMayBeRunning) {
            fallbackWaitingForStop = true
            CoreServiceManager.stopVService(this)
            lifecycleScope.launch {
                delay(FALLBACK_STOP_TIMEOUT_MS)
                if (fallbackWaitingForStop && mainViewModel.isRunning.value != true) {
                    continueFallbackAfterStop()
                }
            }
        } else {
            advanceFallbackPlan()
        }
    }

    private fun continueFallbackAfterStop() {
        if (!fallbackWaitingForStop) {
            return
        }
        fallbackWaitingForStop = false
        lifecycleScope.launch {
            delay(FALLBACK_RESTART_DELAY_MS)
            advanceFallbackPlan()
        }
    }

    private fun advanceFallbackPlan() {
        val plan = fallbackPlan ?: return
        if (plan.advance() == null) {
            cancelFallback()
            applyRunningState(isLoading = false, isRunning = false)
            setTestState(getString(R.string.connection_fallback_all_failed))
            toastError(R.string.connection_fallback_all_failed)
            return
        }
        tryCurrentFallbackCandidate()
    }

    private fun cancelFallback() {
        fallbackProbeJob?.cancel()
        fallbackProbeJob = null
        fallbackPlan = null
        fallbackAwaitingProbe = false
        fallbackWaitingForStop = false
    }

    private fun startV2Ray(): Boolean {
        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            toast(R.string.toast_no_profiles)
            return false
        }

        val selected = MmkvManager.getSelectServer()?.let(MmkvManager::decodeServerConfig)
        if (VkTurnManager.isTurnProfile(selected) && !VkTurnManager.isConfigured(selected)) {
            applyRunningState(isLoading = false, isRunning = false)
            if (com.v2ray.ang.core.DnsTunnelProfiles.isDns(selected)) {
                toast(R.string.dns_tunnel_refresh_required)
                openDnsTunnelSetup()
                return false
            }
            toast(R.string.vk_turn_setup_required)
            requestActivityLauncher.launch(Intent(this, VkTurnSetupActivity::class.java))
            return false
        }
        if (ManagedDnsTunnelManager.isManagedDnsTunnelProfile(selected) && !ManagedDnsTunnelManager.isConfigured(selected)) {
            applyRunningState(isLoading = false, isRunning = false)
            toast(R.string.managed_dns_tunnel_setup_required)
            openManagedDnsTunnelSetup()
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN && MmkvManager.decodeSettingsBool(AppConfig.PREF_PROXY_SHARING)) {
            checkAndRequestPermission(PermissionType.ACCESS_LOCAL_NETWORK) {}
        }

        return CoreServiceManager.startVService(this)
    }

    fun openVkTurnSetup() {
        requestActivityLauncher.launch(Intent(this, VkTurnSetupActivity::class.java))
    }

    private fun openDnsTunnelSetup() {
        requestActivityLauncher.launch(Intent(this, DnsTunnelSetupActivity::class.java))
    }

    fun openManagedDnsTunnelSetup() {
        requestActivityLauncher.launch(Intent(this, ManagedDnsTunnelSetupActivity::class.java))
    }

    fun restartV2Ray() {
        if (mainViewModel.isRecoveryInProgress()) {
            CoreServiceManager.restartVService(this, MmkvManager.getSelectServer())
            return
        }
        if (mainViewModel.isRunning.value == true) {
            CoreServiceManager.stopVService(this)
        }
        lifecycleScope.launch {
            delay(500)
            startV2Ray()
        }
    }

    private fun setTestState(content: String?) {
        binding.tvTestState.text = content
    }

    private fun activeProfileName(): String? =
        MmkvManager.getSelectServer()?.let(MmkvManager::decodeServerConfig)?.remarks

    private fun renderConnectionLatency(delayMillis: Long?) {
        val measured = delayMillis?.takeIf { it >= 0L }
        binding.tvConnectionLatency.isVisible = measured != null
        if (measured != null) {
            binding.tvConnectionLatency.text = getString(R.string.connection_real_delay, measured)
        }
    }

    private fun renderRecoveryState(state: RecoveryUiState) {
        if (state.isFailed) {
            applyRunningState(isLoading = false, isRunning = false)
            setTestState(getString(R.string.reconnect_notification_failed_title) + "\n" + getString(R.string.connection_reconnect_switch_hint))
            binding.tvTestState.setTextColor(ContextCompat.getColor(this, R.color.md_theme_error))
            return
        }
        if (!state.isInProgress) return

        // Recovery is background work, not a modal UI operation. Keep the route list interactive.
        hideLoading()
        renderConnectionLatency(null)
        connectionUiState = ConnectionUiState.RECONNECTING
        binding.fab.setImageResource(R.drawable.ic_fab_check)
        binding.fab.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, R.color.md_theme_secondary),
        )
        binding.fab.contentDescription = getString(R.string.connection_state_reconnecting)
        binding.layoutTest.contentDescription = getString(R.string.connection_state_reconnecting)
        binding.tvTestState.setTextColor(
            ContextCompat.getColor(this, R.color.md_theme_onSurfaceVariant),
        )
        setTestState(
            when (state.phase) {
                "WAITING_FOR_NETWORK" -> getString(R.string.reconnect_notification_waiting_network)
                "TURN_CAPTCHA" -> getString(R.string.vk_turn_captcha_required_status)
                "TURN_CAPTCHA_MANUAL" -> getString(R.string.vk_turn_captcha_action_required)
                "DNS_RECONNECTING" -> getString(R.string.managed_dns_tunnel_reconnecting)
                "SWITCHING_PROFILE" -> getString(
                    R.string.connection_switching_profile,
                    activeProfileName().orEmpty(),
                )
                else -> if (state.attempt > 0 && state.maximum > 0) {
                    getString(R.string.reconnect_notification_attempt_text, state.attempt, state.maximum)
                } else {
                    getString(R.string.reconnect_notification_waiting_text)
                }
            },
        )
    }

    private fun applyRunningState(isLoading: Boolean, isRunning: Boolean, stopping: Boolean = false) {
        if (isLoading) {
            renderConnectionLatency(null)
            connectionUiState = if (stopping) ConnectionUiState.STOPPING else ConnectionUiState.CONNECTING
            val stateText = getString(
                if (stopping) R.string.connection_state_stopping else R.string.connection_state_connecting,
            )
            binding.fab.setImageResource(R.drawable.ic_fab_check)
            binding.fab.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.color_main_connect_blue),
            )
            binding.fab.contentDescription = stateText
            binding.layoutTest.contentDescription = stateText
            showLoading()
            return
        }

        hideLoading()
        if (isRunning) {
            connectionUiState = ConnectionUiState.CONNECTED
            binding.fab.setImageResource(R.drawable.ic_stop_24dp)
            binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_active))
            binding.fab.contentDescription = getString(R.string.action_stop_service)
            binding.layoutTest.contentDescription = getString(R.string.connection_connected)
            binding.tvTestState.setTextColor(ContextCompat.getColor(this, R.color.colorPing))
            setTestState(activeProfileName() ?: getString(R.string.connection_connected))
            val selectedDelay = MmkvManager.getSelectServer()
                ?.let(MmkvManager::decodeServerAffiliationInfo)
                ?.testDelayMillis
                ?.takeIf { it > 0L }
            renderConnectionLatency(selectedDelay)
        } else {
            renderConnectionLatency(null)
            connectionUiState = ConnectionUiState.IDLE
            binding.fab.setImageResource(R.drawable.ic_play_24dp)
            binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_main_connect_blue))
            binding.fab.contentDescription = getString(R.string.action_smart_connect)
            binding.layoutTest.contentDescription = getString(R.string.action_smart_connect)
            binding.tvTestState.setTextColor(ContextCompat.getColor(this, R.color.md_theme_onSurface))
            setTestState(getString(R.string.connection_not_connected))
        }
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)

        val searchItem = menu.findItem(R.id.search_view)
        if (searchItem != null) {
            val searchView = searchItem.actionView as SearchView
            searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean = false

                override fun onQueryTextChange(newText: String?): Boolean {
                    mainViewModel.filterConfig(newText.orEmpty())
                    return false
                }
            })

            searchView.setOnCloseListener {
                mainViewModel.filterConfig("")
                false
            }
        }
        return super.onCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        applyHideFailedMenuState(menu)
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.import_qrcode -> {
            importQRcode()
            true
        }

        R.id.import_clipboard -> {
            importClipboard()
            true
        }

        R.id.import_local -> {
            importConfigLocal()
            true
        }

        R.id.export_all -> {
            exportAll()
            true
        }

        R.id.real_ping_all -> {
            pingAllProfiles()
            true
        }

        R.id.service_restart -> {
            restartV2Ray()
            true
        }

        R.id.del_all_config -> {
            delAllConfig()
            true
        }

        R.id.del_duplicate_config -> {
            delDuplicateConfig()
            true
        }

        R.id.del_invalid_config -> {
            delInvalidConfig()
            true
        }

        R.id.hide_failed -> {
            toggleHideFailedProfiles()
            true
        }

        R.id.sort_by_test_results -> {
            sortByTestResults()
            true
        }

        R.id.sub_update -> {
            importConfigViaSub()
            true
        }

        R.id.locate_selected_config -> {
            locateSelectedServer()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    /**
     * import config from qrcode
     */
    private fun importQRcode(): Boolean {
        launchQRCodeScanner { scanResult ->
            if (scanResult != null) {
                importBatchConfig(scanResult)
            }
        }
        return true
    }

    /**
     * import config from clipboard
     */
    private fun importClipboard()
            : Boolean {
        try {
            val clipboard = Utils.getClipboard(this)
            importBatchConfig(clipboard)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to import config from clipboard", e)
            return false
        }
        return true
    }

    private fun importBatchConfig(server: String?) {
        showLoading()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val (count, countSub) = AngConfigManager.importBatchConfig(server, mainViewModel.subscriptionId, true)
                delay(500L)
                withContext(Dispatchers.Main) {
                    when {
                        count > 0 -> {
                            toast(getString(R.string.title_import_config_count, count))
                            mainViewModel.reloadServerList()
                            refreshGroupTabTitles()
                        }

                        countSub > 0 -> setupGroupTab()
                        else -> toastError(R.string.toast_failure)
                    }
                    hideLoading()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    toastError(R.string.toast_failure)
                    hideLoading()
                }
                LogUtil.e(AppConfig.TAG, "Failed to import batch config", e)
            }
        }
    }

    /**
     * import config from local config file
     */
    private fun importConfigLocal(): Boolean {
        try {
            showFileChooser()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to import config from local file", e)
            return false
        }
        return true
    }


    /**
     * import config from sub
     */
    fun importConfigViaSub(): Boolean {
        showLoading()

        lifecycleScope.launch(Dispatchers.IO) {
            val result = mainViewModel.updateConfigViaSubAll()
            delay(500L)
            launch(Dispatchers.Main) {
                if (result.successCount + result.failureCount + result.skipCount == 0) {
                    toast(R.string.title_update_subscription_no_subscription)
                } else if (result.successCount > 0 && result.failureCount + result.skipCount == 0) {
                    toast(getString(R.string.title_update_config_count, result.configCount))
                } else {
                    toast(
                        getString(
                            R.string.title_update_subscription_result,
                            result.configCount, result.successCount, result.failureCount, result.skipCount
                        )
                    )
                }
                if (result.configCount > 0) {
                    mainViewModel.reloadServerList()
                    refreshGroupTabTitles()
                }
                hideLoading()
            }
        }
        return true
    }

    private fun exportAll() {
        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            val ret = mainViewModel.exportAllServer()
            launch(Dispatchers.Main) {
                if (ret > 0)
                    toast(getString(R.string.title_export_config_count, ret))
                else
                    toastError(R.string.toast_failure)
                hideLoading()
            }
        }
    }

    private fun delAllConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                showLoading()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeAllServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        refreshGroupTabTitles()
                        toast(getString(R.string.title_del_config_count, ret))
                        hideLoading()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do noting
            }
            .show()
    }

    private fun delDuplicateConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                showLoading()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeDuplicateServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        refreshGroupTabTitles()
                        toast(getString(R.string.title_del_duplicate_config_count, ret))
                        hideLoading()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do noting
            }
            .show()
    }

    private fun delInvalidConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_invalid_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                showLoading()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeInvalidServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        refreshGroupTabTitles()
                        toast(getString(R.string.title_del_config_count, ret))
                        hideLoading()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do noting
            }
            .show()
    }

    private fun sortByTestResults() {
        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            mainViewModel.sortByTestResults()
            launch(Dispatchers.Main) {
                mainViewModel.reloadServerList()
                hideLoading()
            }
        }
    }

    /**
     * show file chooser
     */
    private fun showFileChooser() {
        launchFileChooser { uri ->
            if (uri == null) {
                return@launchFileChooser
            }

            readContentFromUri(uri)
        }
    }

    /**
     * read content from uri
     */
    private fun readContentFromUri(uri: Uri) {
        try {
            contentResolver.openInputStream(uri).use { input ->
                importBatchConfig(input?.bufferedReader()?.readText())
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to read content from URI", e)
        }
    }

    /**
     * Locates and scrolls to the currently selected server.
     * If the selected server is in a different group, automatically switches to that group first.
     */
    private fun locateSelectedServer() {
        val targetSubscriptionId = mainViewModel.findSubscriptionIdBySelect()
        if (targetSubscriptionId.isNullOrEmpty()) {
            toast(R.string.toast_no_profiles)
            return
        }

        val targetGroupIndex = groupPagerAdapter.groups.indexOfFirst { it.id == targetSubscriptionId }
        if (targetGroupIndex < 0) {
            toast(R.string.toast_server_not_found_in_group)
            return
        }

        // Switch to target group if needed, then scroll to the server
        if (binding.viewPager.currentItem != targetGroupIndex) {
            binding.viewPager.setCurrentItem(targetGroupIndex, true)
            binding.viewPager.postDelayed({ scrollToSelectedServer(targetGroupIndex) }, 1000)
        } else {
            scrollToSelectedServer(targetGroupIndex)
        }
    }

    /**
     * Scrolls to the selected server in the specified fragment.
     * @param groupIndex The index of the group/fragment to scroll in
     */
    private fun scrollToSelectedServer(groupIndex: Int) {
        val itemId = groupPagerAdapter.getItemId(groupIndex)
        val fragment = supportFragmentManager.findFragmentByTag("f$itemId") as? GroupServerFragment

        if (fragment?.isAdded == true && fragment.view != null) {
            fragment.scrollToSelectedServer()
        } else {
            toast(R.string.toast_fragment_not_available)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            moveTaskToBack(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }


    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        // Handle navigation view item clicks here.
        when (item.itemId) {
            R.id.purchase -> requestActivityLauncher.launch(Intent(this, PurchaseActivity::class.java))
            R.id.account -> startActivity(Intent(this, AccountActivity::class.java))
            R.id.vk_turn_setup -> openVkTurnSetup()
            R.id.managed_dns_tunnel_setup -> openManagedDnsTunnelSetup()
            R.id.hotspot_share -> {
                val serviceRunning = mainViewModel.isRunning.value == true
                val selected = MmkvManager.getSelectServer()?.let(MmkvManager::decodeServerConfig)
                requestActivityLauncher.launch(
                    Intent(this, HotspotShareActivity::class.java)
                        .putExtra(HotspotShareActivity.EXTRA_SERVICE_RUNNING, serviceRunning)
                        .putExtra(
                            HotspotShareActivity.EXTRA_RUNNING_TURN_PROFILE,
                            serviceRunning && VkTurnManager.isTurnProfile(selected),
                        ),
                )
            }
            R.id.sub_setting -> requestActivityLauncher.launch(Intent(this, SubSettingActivity::class.java))
            R.id.per_app_proxy_settings -> requestActivityLauncher.launch(Intent(this, PerAppProxyActivity::class.java))
            R.id.routing_setting -> requestActivityLauncher.launch(Intent(this, RoutingSettingActivity::class.java))
            R.id.user_asset_setting -> requestActivityLauncher.launch(Intent(this, UserAssetActivity::class.java))
            R.id.settings -> requestActivityLauncher.launch(Intent(this, SettingsActivity::class.java))
            R.id.logcat -> startActivity(Intent(this, LogcatActivity::class.java))
            R.id.backup_restore -> requestActivityLauncher.launch(Intent(this, BackupActivity::class.java))
            R.id.about -> startActivity(Intent(this, AboutActivity::class.java))
        }

        binding.drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    override fun onDestroy() {
        connectionStatusDialog?.dismiss()
        connectionStatusDialog = null
        fallbackProbeJob?.cancel()
        tabMediator?.detach()
        super.onDestroy()
    }
}
