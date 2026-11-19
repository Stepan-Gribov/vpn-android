package com.v2ray.ang.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.core.HotspotShareReadiness
import com.v2ray.ang.core.VkTurnManager
import com.v2ray.ang.databinding.ActivityHotspotShareBinding
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.HotspotShareManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.root.RootManager
import com.v2ray.ang.util.MessageUtil
import com.v2ray.ang.util.QRCodeDecoder
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class HotspotShareActivity : HelperBaseActivity() {

    companion object {
        const val EXTRA_SERVICE_RUNNING = "service_running"
        const val EXTRA_RUNNING_TURN_PROFILE = "running_turn_profile"
        private const val STATE_TRANSPARENT_ROUTING = "transparent_routing"
        private const val PREPARATION_TIMEOUT_MS = 35_000L
        private const val TURN_PREPARATION_TIMEOUT_MS = 120_000L
        private const val TURN_CAPTCHA_TIMEOUT_MS = 360_000L
    }

    private data class ShareRequest(
        val name: String?,
        val password: String?,
        val transparent: Boolean,
        val turn: Boolean,
    )

    private val binding by lazy { ActivityHotspotShareBinding.inflate(layoutInflater) }
    private var pendingShare: ShareRequest? = null
    private var serviceRunning = false
    private var runningTurnProfile = false
    private var preparingShare = false
    private var restartingService = false
    private var waitingForServiceStart = false
    private var waitingForRootRouting = false
    private var waitingForTurnReady = false
    private var rootRoutingReady = false
    private var turnReady = false
    private var transparentRouting = false
    private var preparationTimeout: Job? = null
    private var receiverRegistered = false
    private var manualCaptchaOpen = false

    private val captchaActivityLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            manualCaptchaOpen = false
        }

    private val requestVpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                startCoreForSharing()
            } else {
                abortPreparation(R.string.hotspot_share_vpn_permission_required)
            }
        }

    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_STATE_RUNNING -> {
                    serviceRunning = true
                    runningTurnProfile =
                        intent.getStringExtra("content") == AppConfig.SERVICE_STATE_VK_TURN
                    if (waitingForServiceStart) onCoreStarted()
                }

                AppConfig.MSG_STATE_NOT_RUNNING -> {
                    serviceRunning = false
                    runningTurnProfile = false
                }

                AppConfig.MSG_STATE_START_SUCCESS -> {
                    serviceRunning = true
                    runningTurnProfile =
                        intent.getStringExtra("content") == AppConfig.SERVICE_STATE_VK_TURN
                    if (waitingForServiceStart) onCoreStarted()
                }

                AppConfig.MSG_STATE_START_FAILURE ->
                    abortPreparation(R.string.hotspot_share_vpn_start_failed)

                AppConfig.MSG_STATE_STOP_SUCCESS -> {
                    serviceRunning = false
                    runningTurnProfile = false
                    if (restartingService && pendingShare != null) {
                        restartingService = false
                        lifecycleScope.launch {
                            // The core stop is asynchronous; give the old service process a
                            // moment to release its listener before starting it again.
                            delay(700)
                            startCoreWithPermission()
                        }
                    } else if (preparingShare) {
                        abortPreparation(R.string.hotspot_share_vpn_start_failed)
                    }
                }

                AppConfig.MSG_ROOT_LAN_SHARING_READY -> {
                    if (waitingForRootRouting && pendingShare?.transparent == true) {
                        waitingForRootRouting = false
                        rootRoutingReady = true
                        maybeStartHotspot()
                    }
                }

                AppConfig.MSG_ROOT_LAN_SHARING_FAILURE -> {
                    if (waitingForRootRouting && pendingShare?.transparent == true) {
                        fallBackToManualProxy()
                    }
                }

                AppConfig.MSG_VK_TURN_CAPTCHA_REQUIRED -> {
                    turnReady = false
                    if (preparingShare && pendingShare?.turn == true) {
                        waitingForTurnReady = true
                        armPreparationTimeout(TURN_CAPTCHA_TIMEOUT_MS)
                        val captchaUrl = intent.getStringExtra("content") ?: VkTurnManager.CAPTCHA_URL
                        if (VkTurnManager.isAutoCaptchaEnabled()) {
                            // The VPN daemon keeps automatic verification alive in background.
                        } else {
                            if (!manualCaptchaOpen) {
                                manualCaptchaOpen = true
                                captchaActivityLauncher.launch(
                                    Intent(this@HotspotShareActivity, VkCaptchaActivity::class.java)
                                        .putExtra(VkCaptchaActivity.EXTRA_CAPTCHA_URL, captchaUrl),
                                )
                            }
                        }
                        render()
                    }
                }

                AppConfig.MSG_VK_TURN_CAPTCHA_COMPLETED -> {
                    if (preparingShare && pendingShare?.turn == true) {
                        armPreparationTimeout(TURN_PREPARATION_TIMEOUT_MS)
                    }
                }

                AppConfig.MSG_VK_TURN_READY -> {
                    turnReady = true
                    if (waitingForTurnReady && pendingShare?.turn == true) {
                        waitingForTurnReady = false
                        maybeStartHotspot()
                    }
                }

                AppConfig.MSG_VK_TURN_FAILURE -> {
                    turnReady = false
                    if (preparingShare && pendingShare?.turn == true) {
                        waitingForTurnReady = true
                        render()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        serviceRunning = intent.getBooleanExtra(EXTRA_SERVICE_RUNNING, false)
        runningTurnProfile = intent.getBooleanExtra(EXTRA_RUNNING_TURN_PROFILE, false)
        transparentRouting = savedInstanceState?.getBoolean(STATE_TRANSPARENT_ROUTING)
            ?: (HotspotShareManager.isActive() &&
                MmkvManager.decodeSettingsBool(AppConfig.PREF_ROOT_LAN_SHARING) &&
                RootManager.cachedRoot())
        setContentViewWithToolbar(
            binding.root,
            showHomeAsUp = true,
            title = getString(R.string.hotspot_share_title),
        )

        if (HotspotShareManager.supportsCustomCredentials()) {
            migrateHotspotDefaults()
            binding.etHotspotName.setText(savedHotspotName())
            binding.etHotspotPassword.setText(savedHotspotPassword())
            if (HotspotShareManager.supportsExactCredentials()) {
                binding.tilHotspotName.prefixText = null
                binding.tilHotspotName.helperText =
                    getString(R.string.hotspot_share_name_helper_exact)
            }
        } else {
            binding.tilHotspotName.isVisible = false
            binding.tilHotspotPassword.isVisible = false
        }

        binding.btnToggle.setOnClickListener { toggleHotspot() }
        binding.tvSsid.setOnClickListener { copyValue(binding.tvSsid.text) }
        binding.tvPassword.setOnClickListener { copyValue(binding.tvPassword.text) }
        binding.tvProxy.setOnClickListener { copyValue(binding.tvProxy.text) }

        ContextCompat.registerReceiver(
            this,
            serviceReceiver,
            IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
        MessageUtil.sendMsg2Service(this, AppConfig.MSG_REGISTER_CLIENT, "")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_TRANSPARENT_ROUTING, transparentRouting)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        HotspotShareManager.onStateChanged = { onHotspotStateChanged() }
        render()
    }

    override fun onPause() {
        HotspotShareManager.onStateChanged = null
        super.onPause()
    }

    override fun onDestroy() {
        preparationTimeout?.cancel()
        if (receiverRegistered) {
            unregisterReceiver(serviceReceiver)
            receiverRegistered = false
        }
        super.onDestroy()
    }

    private fun toggleHotspot() {
        if (HotspotShareManager.isActive()) {
            HotspotShareManager.stop()
            stopTransparentRouting()
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            toast(R.string.hotspot_share_not_supported)
            return
        }

        if (HotspotShareManager.supportsCustomCredentials()) {
            val password = binding.etHotspotPassword.text?.toString().orEmpty()
            if (password.length !in 8..63) {
                binding.etHotspotPassword.error = getString(R.string.hotspot_share_password_error)
                return
            }
        }

        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PermissionType.NEARBY_WIFI_DEVICES
        } else {
            PermissionType.ACCESS_FINE_LOCATION
        }
        checkAndRequestPermission(permission) { prepareSharing() }
    }

    private fun prepareSharing() {
        val selected = MmkvManager.getSelectServer()?.let(MmkvManager::decodeServerConfig)
        if (selected == null) {
            toast(R.string.app_tile_first_use)
            return
        }

        val (name, password) = enteredCredentials()
        val isTurn = VkTurnManager.isTurnProfile(selected)
        preparingShare = true
        rootRoutingReady = false
        waitingForRootRouting = false
        waitingForTurnReady = false
        armPreparationTimeout(if (isTurn) TURN_PREPARATION_TIMEOUT_MS else PREPARATION_TIMEOUT_MS)
        render()

        lifecycleScope.launch {
            val hasRoot = RootManager.refresh()
            if (!preparingShare) return@launch

            if (hasRoot) {
                pendingShare = ShareRequest(name, password, transparent = true, turn = isTurn)
                MmkvManager.encodeSettings(AppConfig.PREF_ROOT_LAN_SHARING, true)
                if (serviceRunning && isTurn && runningTurnProfile) {
                    // TURN always runs in CoreVpnService. Install client forwarding in that
                    // process without tearing down its already-negotiated bonded sessions.
                    waitForRunningCorePrerequisites()
                    MessageUtil.sendMsg2Service(
                        this@HotspotShareActivity,
                        AppConfig.MSG_ROOT_LAN_SHARING_START,
                        "",
                    )
                } else if (serviceRunning) {
                    restartRunningService()
                } else {
                    startCoreWithPermission()
                }
            } else {
                pendingShare = ShareRequest(name, password, transparent = false, turn = isTurn)
                val rootSettingChanged =
                    MmkvManager.decodeSettingsBool(AppConfig.PREF_ROOT_LAN_SHARING)
                MmkvManager.encodeSettings(AppConfig.PREF_ROOT_LAN_SHARING, false)
                prepareManualProxy(restartRequired = rootSettingChanged)
            }
        }
    }

    private fun enteredCredentials(): Pair<String?, String?> {
        if (!HotspotShareManager.supportsCustomCredentials()) return null to null

        val name = binding.etHotspotName.text?.toString()?.trim().orEmpty()
            .ifEmpty { HotspotShareManager.DEFAULT_NETWORK_NAME }
        val password = binding.etHotspotPassword.text?.toString().orEmpty()
            .ifEmpty { HotspotShareManager.DEFAULT_PASSWORD }
        MmkvManager.encodeSettings(AppConfig.PREF_HOTSPOT_SSID, name)
        MmkvManager.encodeSettings(AppConfig.PREF_HOTSPOT_PASSWORD, password)
        return name to password
    }

    private fun savedHotspotName(): String = MmkvManager.decodeSettingsString(
        AppConfig.PREF_HOTSPOT_SSID,
        HotspotShareManager.DEFAULT_NETWORK_NAME,
    ).orEmpty().ifBlank { HotspotShareManager.DEFAULT_NETWORK_NAME }

    private fun savedHotspotPassword(): String = MmkvManager.decodeSettingsString(
        AppConfig.PREF_HOTSPOT_PASSWORD,
        HotspotShareManager.DEFAULT_PASSWORD,
    ).orEmpty().ifBlank { HotspotShareManager.DEFAULT_PASSWORD }

    /** Replace only the former app-generated defaults; preserve credentials users chose. */
    private fun migrateHotspotDefaults() {
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_HOTSPOT_DEFAULTS_V2, false)) return

        val previousName = MmkvManager.decodeSettingsString(AppConfig.PREF_HOTSPOT_SSID, null)
        val previousPassword = MmkvManager.decodeSettingsString(AppConfig.PREF_HOTSPOT_PASSWORD, null)
        if (previousName.isNullOrBlank() || previousName == "KindlyVPN") {
            MmkvManager.encodeSettings(
                AppConfig.PREF_HOTSPOT_SSID,
                HotspotShareManager.DEFAULT_NETWORK_NAME,
            )
            MmkvManager.encodeSettings(
                AppConfig.PREF_HOTSPOT_PASSWORD,
                HotspotShareManager.DEFAULT_PASSWORD,
            )
        } else if (previousPassword.isNullOrBlank()) {
            MmkvManager.encodeSettings(
                AppConfig.PREF_HOTSPOT_PASSWORD,
                HotspotShareManager.DEFAULT_PASSWORD,
            )
        }
        MmkvManager.encodeSettings(AppConfig.PREF_HOTSPOT_DEFAULTS_V2, true)
    }

    private fun prepareManualProxy(restartRequired: Boolean = false) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
            requestPermissionResult(PermissionType.ACCESS_LOCAL_NETWORK) { granted ->
                if (granted) configureManualProxy(restartRequired) else abortPreparation()
            }
        } else {
            configureManualProxy(restartRequired)
        }
    }

    private fun configureManualProxy(restartRequired: Boolean) {
        val settingChanged = restartRequired ||
            !MmkvManager.decodeSettingsBool(AppConfig.PREF_PROXY_SHARING)
        MmkvManager.encodeSettings(AppConfig.PREF_PROXY_SHARING, true)
        if (serviceRunning && settingChanged) {
            restartRunningService()
        } else if (serviceRunning) {
            waitForRunningCorePrerequisites()
        } else {
            startCoreWithPermission()
        }
    }

    private fun restartRunningService() {
        restartingService = true
        waitingForServiceStart = false
        CoreServiceManager.stopVService(this)
        render()
    }

    private fun startCoreWithPermission() {
        val selected = MmkvManager.getSelectServer()?.let(MmkvManager::decodeServerConfig)
        val needsVpnPermission = !SettingsManager.isRootMode() &&
            (SettingsManager.isVpnMode() || VkTurnManager.isTurnProfile(selected))
        val permissionIntent = if (needsVpnPermission) VpnService.prepare(this) else null
        if (permissionIntent != null) {
            requestVpnPermission.launch(permissionIntent)
        } else {
            startCoreForSharing()
        }
    }

    private fun startCoreForSharing() {
        if (pendingShare == null) return
        waitingForServiceStart = true
        if (!CoreServiceManager.startVService(this)) {
            abortPreparation(R.string.hotspot_share_vpn_start_failed)
        }
    }

    private fun onCoreStarted() {
        waitingForServiceStart = false
        val request = pendingShare ?: return
        waitingForTurnReady = request.turn && !turnReady
        if (request.transparent) {
            waitingForRootRouting = true
        }
        maybeStartHotspot()
    }

    private fun waitForRunningCorePrerequisites() {
        val request = pendingShare ?: return
        waitingForTurnReady = request.turn && !turnReady
        if (request.transparent) waitingForRootRouting = true
        maybeStartHotspot()
    }

    private fun maybeStartHotspot() {
        val request = pendingShare ?: return
        val serviceTransitioning = waitingForServiceStart || restartingService
        if (request.turn && !turnReady) {
            waitingForTurnReady = true
            render()
        }
        if (request.transparent && !rootRoutingReady) {
            waitingForRootRouting = true
            render()
        }
        if (!HotspotShareReadiness.canStart(
                serviceRunning = serviceRunning,
                serviceTransitioning = serviceTransitioning,
                requiresTurn = request.turn,
                turnReady = turnReady,
                requiresRootRouting = request.transparent,
                rootRoutingReady = rootRoutingReady,
            )
        ) return
        startHotspotNow()
    }

    private fun startHotspotNow() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            abortPreparation(R.string.hotspot_share_not_supported)
            return
        }
        val request = pendingShare ?: return
        transparentRouting = request.transparent
        pendingShare = null
        preparingShare = false
        restartingService = false
        waitingForServiceStart = false
        waitingForRootRouting = false
        waitingForTurnReady = false
        rootRoutingReady = request.transparent
        preparationTimeout?.cancel()
        HotspotShareManager.start(this, request.name, request.password)
        render()
    }

    private fun fallBackToManualProxy() {
        waitingForRootRouting = false
        if (SettingsManager.isRootMode()) {
            abortPreparation(R.string.hotspot_share_root_failed)
            return
        }

        toastError(R.string.hotspot_share_root_fallback)
        MmkvManager.encodeSettings(AppConfig.PREF_ROOT_LAN_SHARING, false)
        pendingShare = pendingShare?.copy(transparent = false)
        rootRoutingReady = false
        prepareManualProxy(restartRequired = true)
    }

    private fun armPreparationTimeout(timeoutMs: Long = PREPARATION_TIMEOUT_MS) {
        preparationTimeout?.cancel()
        preparationTimeout = lifecycleScope.launch {
            delay(timeoutMs)
            if (preparingShare) abortPreparation(R.string.hotspot_share_prepare_timeout)
        }
    }

    private fun abortPreparation(messageRes: Int? = null) {
        val stopRootSharing = pendingShare?.transparent == true && serviceRunning
        pendingShare = null
        preparingShare = false
        restartingService = false
        waitingForServiceStart = false
        waitingForRootRouting = false
        waitingForTurnReady = false
        rootRoutingReady = false
        preparationTimeout?.cancel()
        if (stopRootSharing) stopTransparentRouting(force = true)
        if (messageRes != null) toastError(messageRes)
        render()
    }

    private fun stopTransparentRouting(force: Boolean = false) {
        if (!force && !transparentRouting && !rootRoutingReady && pendingShare?.transparent != true) return
        transparentRouting = false
        rootRoutingReady = false
        MmkvManager.encodeSettings(AppConfig.PREF_ROOT_LAN_SHARING, false)
        if (serviceRunning) {
            MessageUtil.sendMsg2Service(this, AppConfig.MSG_ROOT_LAN_SHARING_STOP, "")
        }
    }

    private fun onHotspotStateChanged() {
        if (HotspotShareManager.state == HotspotShareManager.State.FAILED) {
            stopTransparentRouting()
        }
        render()
    }

    private fun render() {
        val state = HotspotShareManager.state
        val info = HotspotShareManager.info

        binding.tvHotspotState.text = getString(
            if (preparingShare) {
                R.string.hotspot_share_state_preparing
            } else {
                when (state) {
                    HotspotShareManager.State.STOPPED -> R.string.hotspot_share_state_off
                    HotspotShareManager.State.STARTING -> R.string.hotspot_share_state_starting
                    HotspotShareManager.State.RUNNING -> R.string.hotspot_share_state_running
                    HotspotShareManager.State.FAILED -> R.string.hotspot_share_state_failed
                }
            },
        )
        binding.tvHotspotNote.setText(
            when {
                preparingShare && waitingForTurnReady ->
                    R.string.hotspot_share_turn_preparing_note
                preparingShare -> R.string.hotspot_share_preparing_note
                state == HotspotShareManager.State.RUNNING && transparentRouting ->
                    R.string.hotspot_share_transparent_note
                state == HotspotShareManager.State.RUNNING -> R.string.hotspot_share_manual_note
                else -> R.string.hotspot_share_vpn_note
            },
        )
        binding.btnToggle.isEnabled = !preparingShare && state != HotspotShareManager.State.STARTING
        binding.btnToggle.setText(
            if (HotspotShareManager.isActive()) R.string.hotspot_share_stop else R.string.hotspot_share_start,
        )
        val formEnabled = !preparingShare && !HotspotShareManager.isActive()
        binding.tilHotspotName.isEnabled = formEnabled
        binding.tilHotspotPassword.isEnabled = formEnabled

        val running = state == HotspotShareManager.State.RUNNING && info != null
        binding.layoutCredentials.isVisible = running
        if (!running) return

        binding.tvSsid.text = info.ssid
        binding.tvPassword.text = info.password
        binding.tvProxyLabel.isVisible = !transparentRouting
        binding.tvProxy.isVisible = !transparentRouting
        binding.tvClientNote.setText(
            if (transparentRouting) {
                R.string.hotspot_share_client_note_transparent
            } else {
                R.string.hotspot_share_client_note_manual
            },
        )
        if (!transparentRouting) {
            val socksPort = SettingsManager.getSocksPort()
            binding.tvProxy.text = when {
                info.gatewayIp != null -> "${info.gatewayIp}:$socksPort"
                HotspotShareManager.gatewayResolved ->
                    getString(R.string.hotspot_share_proxy_fallback, socksPort)
                else -> getString(R.string.hotspot_share_proxy_pending)
            }
        }
        binding.tvOpenProxyWarning.isVisible =
            !transparentRouting && SettingsManager.getSocksUsername().isNullOrEmpty()

        val ssid = info.ssid
        val password = info.password
        if (ssid.isNotEmpty() && password.isNotEmpty()) {
            val wifiQr = "WIFI:T:WPA;S:${escapeQrValue(ssid)};P:${escapeQrValue(password)};;"
            binding.ivQr.setImageBitmap(QRCodeDecoder.createQRCode(wifiQr))
        }
    }

    private fun copyValue(value: CharSequence?) {
        val text = value?.toString().orEmpty()
        if (text.isEmpty()) return
        Utils.setClipboard(this, text)
        toastSuccess(R.string.toast_success)
    }

    /** Escape the reserved characters of the Wi-Fi QR payload format. */
    private fun escapeQrValue(value: String): String =
        value.replace("\\", "\\\\")
            .replace(";", "\\;")
            .replace(",", "\\,")
            .replace(":", "\\:")
            .replace("\"", "\\\"")
}
