package com.v2ray.ang.core

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.system.OsConstants
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.dto.OutboundTrafficStat
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SpeedtestManager
import com.v2ray.ang.root.RootManager
import com.v2ray.ang.simple.SimpleRuntime
import com.v2ray.ang.simple.SimpleStore
import com.v2ray.ang.simple.TemporaryFullTunnel
import com.v2ray.ang.simple.TemporaryFullTunnelPolicy
import com.v2ray.ang.service.AmneziaWgVpnService
import com.v2ray.ang.service.CoreProxyOnlyService
import com.v2ray.ang.service.CoreRootService
import com.v2ray.ang.service.CoreVpnService
import com.v2ray.ang.service.DialerNativeService
import com.v2ray.ang.service.DialerWebviewService
import com.v2ray.ang.service.IDialerService
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.MessageUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.ProcessFinder
import okhttp3.OkHttpClient
import okhttp3.Request
import java.lang.ref.SoftReference
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

object CoreServiceManager {

    // AmneziaWG uses its own Go runtime. Loading libgojni.so in the same VPN
    // process before libwg-go.so can make the two c-shared runtimes collide.
    private val coreControllerDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        CoreNativeManager.newCoreController(CoreCallback())
    }
    private val coreController: CoreController by coreControllerDelegate
    private val mMsgReceive = ReceiveMessageHandler()
    private var currentConfig: ProfileItem? = null
    private var processFinder: XrayProcessFinder? = null
    private var browserDialer: IDialerService? = null
    private val activeConnection = AtomicBoolean(false)
    private val amneziaWgRunning = AtomicBoolean(false)
    private val expectedShutdown = AtomicBoolean(false)
    private val preserveServiceForRecovery = AtomicBoolean(false)
    private val handlingConnectionDrop = AtomicBoolean(false)
    private val healthProbeRunning = AtomicBoolean(false)
    private val manualDelayRunning = AtomicBoolean(false)
    private val messageReceiverRegistered = AtomicBoolean(false)
    private val consecutiveHealthFailures = AtomicInteger(0)
    private val reconnectGeneration = AtomicLong(0L)
    private val turnRouteEpoch = AtomicLong(0L)
    private val turnNotReadySince = AtomicLong(0L)
    private val backgroundScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("vpn-background-recovery"),
    )
    private val mutableRecoveryState = MutableStateFlow(VpnRecoveryState())
    internal val recoveryState: StateFlow<VpnRecoveryState> = mutableRecoveryState.asStateFlow()
    private var healthMonitorJob: Job? = null
    private var physicalNetworkRefreshJob: Job? = null
    private var amneziaResumeJob: Job? = null
    private val amneziaWake = Channel<Unit>(Channel.CONFLATED)
    private val amneziaResumeGate = AmneziaResumeGate()
    private val amneziaMigration = AmneziaMigration()
    private val amneziaResumeMutex = kotlinx.coroutines.sync.Mutex()
    private val amneziaResumeEpoch = AtomicLong(0L)
    @Volatile private var lastPhysicalNetworkHandle: Long? = null
    @Volatile private var amneziaNetworkBlocked = false
    @Volatile private var amneziaCheckingOrRetrying = false
    private var managedAccessJob: Job? = null
    private var recoveryInitialProbeJob: Job? = null
    private var immediateHealthProbeJob: Job? = null
    private var networkLossJob: Job? = null
    private var reconnectJob: Job? = null
    @Volatile private var turnTransportRecoveryJob: Job? = null
    private var turnCaptchaWatchdogJob: Job? = null
    private var turnCaptchaWatchdogID = 0L
    private val turnCaptchaLock = Any()

    var serviceControl: SoftReference<ServiceControl>? = null
        set(value) {
            field = value
            val service = value?.get()?.getService()
            if (service !is AmneziaWgVpnService) {
                CoreNativeManager.initCoreEnv(service)
                if (service != null && processFinder == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    processFinder = XrayProcessFinder(service)
                    coreController.registerProcessFinder(processFinder)
                }
            }
        }

    private fun transitionRecovery(
        phase: VpnRecoveryPhase,
        attempt: Int = mutableRecoveryState.value.attempt,
        maximum: Int = mutableRecoveryState.value.maximumAttempts,
        generation: Long = reconnectGeneration.get(),
    ) {
        val next = VpnRecoveryState(phase, attempt, maximum, generation)
        com.v2ray.ang.simple.DaemonConnectionState.publish(phase.name, attempt, maximum)
        if (mutableRecoveryState.value == next) return
        mutableRecoveryState.value = next
        LogUtil.i(
            AppConfig.TAG,
            "[VPN-RECONNECT] ${phase.name} attempt=$attempt/$maximum generation=$generation",
        )
        getService()?.let { service ->
            if (phase == VpnRecoveryPhase.CONNECTED) {
                NotificationManager.showNotification(currentConfig)
                NotificationManager.cancelConnectionStatus(service)
            }
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_RECOVERY, next.toUiPayload())
        }
    }

    /**
     * Starts the V2Ray service from a toggle action.
     * @param context The context from which the service is started.
     * @return True if the service was started successfully, false otherwise.
     */
    fun startVServiceFromToggle(context: Context): Boolean {
        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            context.toast(R.string.app_tile_first_use)
            return false
        }
        cancelAutomaticRecovery(context)
        SettingsManager.setConnectionDesired(true)
        SettingsManager.clearAutoReconnectAttempt()
        try {
            startContextService(context)
        } catch (e: Exception) {
            SettingsManager.setConnectionDesired(false)
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: ${e.message}", e)
            context.toast(e.message ?: e.javaClass.simpleName)
            return false
        }
        return true
    }

    /**
     * Starts the V2Ray service.
     * @param context The context from which the service is started.
     * @param guid The GUID of the server configuration to use (optional).
     */
    fun startVService(context: Context, guid: String? = null): Boolean {
        LogUtil.i(AppConfig.TAG, "StartCore-Manager: startVService from ${context::class.java.simpleName}")

        cancelAutomaticRecovery(context)
        SettingsManager.setConnectionDesired(true)
        SettingsManager.clearAutoReconnectAttempt()

        if (guid != null) {
            MmkvManager.setSelectServer(guid)
        }

        try {
            startContextService(context)
            return true
        } catch (e: Exception) {
            SettingsManager.setConnectionDesired(false)
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: ${e.message}", e)
            context.toast(e.message ?: e.javaClass.simpleName)
            return false
        }
    }

    /**
     * Stops the V2Ray service.
     * @param context The context from which the service is stopped.
     */
    fun stopVService(context: Context) {
        //context.toast(R.string.toast_services_stop)
        SettingsManager.setConnectionDesired(false)
        cancelAutomaticRecovery(context)
        MessageUtil.sendMsg2Service(context, AppConfig.MSG_STATE_STOP, "")
    }

    /** Routes a user-requested profile switch through the daemon during recovery. */
    fun restartVService(context: Context, guid: String? = null) {
        guid?.takeIf(String::isNotBlank)?.let(MmkvManager::setSelectServer)
        SettingsManager.setConnectionDesired(true)
        SettingsManager.clearAutoReconnectAttempt()
        MessageUtil.sendMsg2Service(context, AppConfig.MSG_STATE_RESTART, guid.orEmpty())
    }

    /** Reports a transport failure that should be treated as an unexpected VPN drop. */
    fun reportConnectionDrop(context: Context, reason: String) {
        MessageUtil.sendMsg2Service(context, AppConfig.MSG_STATE_CONNECTION_DROPPED, reason)
    }

    /**
     * Checks if the V2Ray service is running.
     * @return True if the service is running, false otherwise.
     */
    fun isRunning() = isEngineRunning()

    private fun isEngineRunning() = amneziaWgRunning.get() ||
        (coreControllerDelegate.isInitialized() && coreController.isRunning)

    /**
     * Gets the name of the currently running server.
     * @return The name of the running server.
     */
    fun getRunningServerName() = currentConfig?.remarks.orEmpty()

    /**
     * Starts the context service for V2Ray.
     * Chooses between VPN service or Proxy-only service based on user settings.
     * @param context The context from which the service is started.
     * @throws IllegalStateException if the core is already running, no server is selected,
     *   server config cannot be decoded, or server configuration is invalid.
     * @throws Exception if the foreground service fails to start.
     */
    @Throws(Exception::class)
    private fun startContextService(context: Context) {
        if (isEngineRunning()) {
            LogUtil.w(AppConfig.TAG, "StartCore-Manager: A tunnel is already running")
            return
        }

        val guid = MmkvManager.getSelectServer()
            ?: run {
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: No server selected")
                error(context.getString(R.string.app_tile_first_use))
            }

        val config = MmkvManager.decodeServerConfig(guid)
            ?: run {
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to decode server config")
                error(context.getString(R.string.toast_config_file_invalid))
            }

        if (config.configType != EConfigType.AMNEZIAWG && !config.configType.isComplexType()
            && !Utils.isValidUrl(config.server)
            && !Utils.isPureIpAddress(config.server.orEmpty())
        ) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: Invalid server configuration")
            error(context.getString(R.string.toast_config_file_invalid))
        }

        // refresh socks port when enabled dynamic socks port
        SettingsManager.refreshRuntimeSocksPort()

//        val result = V2rayConfigUtil.getV2rayConfig(context, guid)
//        if (!result.status) error(result.errorMessage.ifBlank { "Failed to get V2Ray config" })

        if (config.insecure == true) {
            context.toastError(R.string.toast_allow_insecure_deprecated)
            context.toastError(R.string.toast_allow_insecure_deprecated)
        }

        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PROXY_SHARING)) {
            context.toast(R.string.toast_warning_pref_proxysharing_short)
        } else {
            context.toast(R.string.toast_services_start)
        }

        val isTurnProfile = VkTurnManager.isTurnProfile(config)
        val isManagedDnsTunnelProfile = ManagedDnsTunnelManager.isManagedDnsTunnelProfile(config)
        val isAmneziaWgProfile = config.configType == EConfigType.AMNEZIAWG
        val isManagedTransport = isTurnProfile || isManagedDnsTunnelProfile || isAmneziaWgProfile
        val isRootMode = !isManagedTransport && SettingsManager.isRootMode()
        if (isRootMode && !RootManager.isRootAvailable()) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: root mode requires root but none available")
            error(context.getString(R.string.toast_root_required))
        }

        val intent = if (isAmneziaWgProfile) {
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: Starting isolated AmneziaWG VPN service")
            Intent(context.applicationContext, AmneziaWgVpnService::class.java)
        } else if (isManagedTransport) {
            LogUtil.i(
                AppConfig.TAG,
                "StartCore-Manager: Forcing full VPN service for managed transport",
            )
            Intent(context.applicationContext, CoreVpnService::class.java)
        } else if (isRootMode) {
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: Starting Root service")
            Intent(context.applicationContext, CoreRootService::class.java)
        } else if (SettingsManager.isVpnMode()) {
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: Starting VPN service")
            Intent(context.applicationContext, CoreVpnService::class.java)
        } else {
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: Starting Proxy service")
            Intent(context.applicationContext, CoreProxyOnlyService::class.java)
        }

        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: SecurityException) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: Missing permission to start foreground service", e)
            throw IllegalStateException(e.message ?: e.javaClass.simpleName, e)
        } catch (e: RuntimeException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e.javaClass.name == "android.app.ForegroundServiceStartNotAllowedException"
            ) {
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: Foreground service start not allowed", e)
                throw IllegalStateException(e.message ?: e.javaClass.simpleName, e)
            }
            throw e
        }
    }

    /**
     * Refer to the official documentation for [registerReceiver](https://developer.android.com/reference/androidx/core/content/ContextCompat#registerReceiver(android.content.Context,android.content.BroadcastReceiver,android.content.IntentFilter,int):
     * `registerReceiver(Context, BroadcastReceiver, IntentFilter, int)`.
     * Starts the V2Ray core service.
     */
    fun startCoreLoop(vpnInterface: ParcelFileDescriptor?): Boolean {
        if (isEngineRunning()) {
            LogUtil.w(AppConfig.TAG, "StartCore-Manager: A tunnel is already running")
            return false
        }

        val service = getService()
        if (service == null) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: Service is null")
            return false
        }

        try {
            doStartCoreLoop(service, vpnInterface)
            return true
        } catch (e: Exception) {
            amneziaWgRunning.set(false)
            VkTurnManager.stop()
            ManagedDnsTunnelManager.stop()
            com.v2ray.ang.simple.AmneziaRuntime.stop()
            com.v2ray.ang.simple.PhysicalDnsBridge.stop()
            if (e is StartupCancelled || !SettingsManager.isConnectionDesired()) {
                LogUtil.i(AppConfig.TAG, "StartCore-Manager: Startup cancelled")
                transitionRecovery(VpnRecoveryPhase.DISCONNECTED)
                NotificationManager.cancelNotification()
                return false
            }
            val message = e.message?.takeUnless { it.isBlank() } ?: e.javaClass.simpleName
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: $message", e)
            if (handlingConnectionDrop.get() || isSwitchingTemporaryFullTunnel()) {
                NotificationManager.showRecoveryForeground(
                    service,
                    service.getString(R.string.reconnect_notification_waiting_text),
                )
            } else {
                transitionRecovery(VpnRecoveryPhase.DISCONNECTED)
                MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, message)
                NotificationManager.cancelNotification()
            }
            return false
        }
    }

    private class StartupCancelled : Exception()

    private fun requireStartupWanted() {
        if (!SettingsManager.isConnectionDesired()) throw StartupCancelled()
    }

    @Throws(Exception::class)
    private fun doStartCoreLoop(service: Service, vpnInterface: ParcelFileDescriptor?) {
        expectedShutdown.set(false)
        preserveServiceForRecovery.set(isSwitchingTemporaryFullTunnel())
        // A helper PID exists before ensureForProfile returns. Register first so
        // Disconnect during that startup window is queued, not lost; desired also
        // catches a stop sent just before registration from the UI process.
        val mFilter = IntentFilter(AppConfig.BROADCAST_ACTION_SERVICE)
        mFilter.addAction(Intent.ACTION_SCREEN_ON)
        mFilter.addAction(Intent.ACTION_SCREEN_OFF)
        mFilter.addAction(Intent.ACTION_USER_PRESENT)
        mFilter.addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
        if (messageReceiverRegistered.compareAndSet(false, true)) {
            try {
                ContextCompat.registerReceiver(service, mMsgReceive, mFilter, Utils.receiverFlags())
            } catch (e: Exception) {
                messageReceiverRegistered.set(false)
                throw e
            }
        }
        requireStartupWanted()
        val guid = MmkvManager.getSelectServer() ?: error("No server selected")
        if(guid==com.v2ray.ang.simple.SimpleRuntime.STAGED_GUID) {
            check(com.v2ray.ang.simple.PanelAccess.preparedAllowed(service,refreshRuntime=false)) { "Управляемый доступ истёк, отозван или больше не назначен." }
        }
        val config = MmkvManager.decodeServerConfig(guid) ?: error("Failed to decode server config")

        LogUtil.i(AppConfig.TAG, "StartCore-Manager: Starting core loop for ${config.remarks}")
        val isAmneziaWgProfile = config.configType == EConfigType.AMNEZIAWG
        if (!isAmneziaWgProfile) {
            VkTurnManager.ensureForProfile(service, config)
            requireStartupWanted()
            ManagedDnsTunnelManager.ensureForProfile(service, config)
            requireStartupWanted()
            com.v2ray.ang.simple.AmneziaRuntime.ensure(service, wanted = SettingsManager::isConnectionDesired)
        }
        requireStartupWanted()
        if(guid==com.v2ray.ang.simple.SimpleRuntime.STAGED_GUID && !isAmneziaWgProfile) {
            com.v2ray.ang.simple.PhysicalDnsBridge.start(service)
        }
        val result = if (isAmneziaWgProfile) null else CoreConfigManager.getV2rayConfig(service, guid)
        result?.let {
            // Runtime JSON contains client credentials; never dump it to logcat.
            if (!it.status) error(it.errorMessage.ifBlank { "Failed to get V2Ray config" })
        }

        requireStartupWanted()

        currentConfig = config
        if (!VkTurnManager.isVkTurnProfile(config)) {
            cancelAutomaticRecovery(service)
            SettingsManager.clearAutoReconnectAttempt()
        }
        if (isAmneziaWgProfile) {
            val vpnControl = serviceControl?.get()
                ?: error("VPN service is not available for AmneziaWG")
            val tun = vpnInterface ?: error("AmneziaWG requires VPN mode")
            val rawConfig = config.amneziaWgConfig
                ?: error("AmneziaWG profile has no configuration")
            if (!vpnControl.startAmneziaWgTunnel(tun, rawConfig)) {
                error("AmneziaWG engine failed to start")
            }
            amneziaWgRunning.set(true)
            activeConnection.set(true)
            startManagedAccessMonitor(service)
            consecutiveHealthFailures.set(0)
            NotificationManager.showNotification(currentConfig)
            if (handlingConnectionDrop.get()) {
                NotificationManager.showRecoveryForeground(
                    service,
                    service.getString(R.string.reconnect_notification_checking_text),
                )
            } else {
                transitionRecovery(
                    VpnRecoveryPhase.CONNECTED,
                    attempt = 0,
                    maximum = SettingsManager.getAutoReconnectMaxRetries(),
                )
                MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_START_SUCCESS, "")
                startHealthMonitor(service)
            }
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: AmneziaWG tunnel started")
            return
        }

        turnNotReadySince.set(
            if (VkTurnManager.isTurnProfile(config)) SystemClock.elapsedRealtime() else 0L,
        )
        var tunFd = vpnInterface?.fd ?: 0
        val dialerAddr = if (currentConfig?.browserDialerMode.isNullOrEmpty()) {
            ""
        } else {
            "127.0.0.1:${Utils.findRandomFreePort()}"
        }
        if (SettingsManager.isUsingHevTun() || VkTurnManager.isTurnProfile(config)) {
            tunFd = 0
        }

        NotificationManager.showNotification(currentConfig)
        CoreNativeManager.reconcileBrowserDialer(dialerAddr)
        coreController.startLoop(checkNotNull(result).content, tunFd)

        if (!coreController.isRunning) {
            error("Core failed to start")
        }
        result?.routingPolicy?.let(com.v2ray.ang.simple.RoutingPolicyState::recordApplied)
        TemporaryFullTunnel.started(service)

        if (browserDialer != null) {
            browserDialer!!.stop()
            browserDialer = null
        }
        if (config.browserDialerMode == "OkHttp") {
            browserDialer = DialerNativeService()
            browserDialer!!.start(service, dialerAddr)
        } else if (config.browserDialerMode == "WebView") {
            browserDialer = DialerWebviewService()
            browserDialer!!.start(service, dialerAddr)
        }

        val recovering = handlingConnectionDrop.get()
        val verifyingSimple = guid == com.v2ray.ang.simple.SimpleRuntime.STAGED_GUID
        val waitingForManagedDns = ManagedDnsTunnelManager.isManagedDnsTunnelProfile(config) &&
            !ManagedDnsTunnelManager.isReadyForTraffic()
        val waitingForTurn = VkTurnManager.isTurnProfile(config) && !DnsTunnelManager.active && !VkTurnManager.isReadyForTraffic()
        if (!recovering && !DnsTunnelManager.active && !waitingForManagedDns && !verifyingSimple && !waitingForTurn) {
            MessageUtil.sendMsg2UI(
                service,
                AppConfig.MSG_STATE_START_SUCCESS,
                if (VkTurnManager.isTurnProfile(currentConfig)) {
                    AppConfig.SERVICE_STATE_VK_TURN
                } else {
                    ""
                },
            )
        }
        activeConnection.set(true)
        startManagedAccessMonitor(service)
        consecutiveHealthFailures.set(0)
        if (
            !VkTurnManager.isTurnProfile(config) &&
            !ManagedDnsTunnelManager.isManagedDnsTunnelProfile(config)
        ) {
            SettingsManager.clearAutoReconnectAttempt()
        }
        if (recovering) {
            NotificationManager.showRecoveryForeground(
                service,
                service.getString(R.string.reconnect_notification_checking_text),
            )
        } else {
            transitionRecovery(
                when {
                    waitingForManagedDns -> VpnRecoveryPhase.DNS_RECONNECTING
                    waitingForTurn && VkTurnManager.isCaptchaPending() ->
                        if (VkTurnManager.captchaChallenge()?.needsUser == true) VpnRecoveryPhase.TURN_CAPTCHA_MANUAL
                        else VpnRecoveryPhase.TURN_CAPTCHA
                    waitingForTurn -> VpnRecoveryPhase.STARTING_TURN
                    DnsTunnelManager.active || verifyingSimple -> VpnRecoveryPhase.VERIFYING_CONNECTION
                    else -> VpnRecoveryPhase.CONNECTED
                },
                attempt = 0,
                maximum = SettingsManager.getAutoReconnectMaxRetries(),
            )
            NotificationManager.startSpeedNotification()
            startHealthMonitor(service)
            if (DnsTunnelManager.active || verifyingSimple) verifyInitialRecoveryRoute()
            if (ManagedDnsTunnelManager.isManagedDnsTunnelProfile(config)) {
                // Re-read after activating the core: the helper may become ready during startup.
                onManagedDnsTunnelReadinessChanged()
            }
        }
        LogUtil.i(AppConfig.TAG, "StartCore-Manager: Core started successfully")
    }

    /**
     * Stops the V2Ray core service.
     * Unregisters broadcast receivers, stops notifications, and shuts down plugins.
     * @return True if the core was stopped successfully, false otherwise.
     */
    fun stopCoreLoop(keepForegroundForRecovery: Boolean = false): Boolean {
        TemporaryFullTunnel.stopped()
        stopHealthMonitor()
        cancelTurnCaptchaWatchdog()
        turnNotReadySince.set(0L)
        activeConnection.set(false)
        expectedShutdown.set(true)
        preserveServiceForRecovery.set(keepForegroundForRecovery)
        VkTurnManager.stop()
        ManagedDnsTunnelManager.stop()
        com.v2ray.ang.simple.AmneziaRuntime.stop()
        com.v2ray.ang.simple.PhysicalDnsBridge.stop()
        val control = serviceControl?.get()
        if (amneziaWgRunning.getAndSet(false)) {
            control?.stopAmneziaWgTunnel()
        }
        val service = control?.getService() ?: return false

        if (coreControllerDelegate.isInitialized() && coreController.isRunning) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    coreController.stopLoop()
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to stop V2Ray loop", e)
                }
            }
        }

        // Close existing browser dialer
        if (coreControllerDelegate.isInitialized()) {
            CoreNativeManager.reconcileBrowserDialer("")
        }
        if (browserDialer != null) {
            browserDialer!!.stop()
            browserDialer = null
        }

        if (!keepForegroundForRecovery) {
            transitionRecovery(VpnRecoveryPhase.DISCONNECTED)
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_STOP_SUCCESS, "")
            NotificationManager.cancelNotification()
        }

        if (!keepForegroundForRecovery && messageReceiverRegistered.compareAndSet(true, false)) {
            try {
                service.unregisterReceiver(mMsgReceive)
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to unregister receiver", e)
            }
        }

        return true
    }

    /**
     * Queries and resets all outbound traffic counters in one core call.
     * Go side format: tag,direction,value;tag,direction,value;
     */
    fun queryAllOutboundTrafficStats(): List<OutboundTrafficStat> {
        if (!coreControllerDelegate.isInitialized()) return emptyList()
        val payload = coreController.queryAllOutboundTrafficStats()

        val result = ArrayList<OutboundTrafficStat>()

        payload.split(';').forEach { entry ->
            if (entry.isBlank()) return@forEach

            val parts = entry.split(',', limit = 3)
            if (parts.size != 3) return@forEach

            val value = parts[2].toLongOrNull() ?: return@forEach

            result.add(
                OutboundTrafficStat(
                    tag = parts[0],
                    direction = parts[1],
                    value = value,
                )
            )
        }
//        LogUtil.d(AppConfig.TAG, "Queried outbound traffic stats: $result")
        return result
    }

    /** Manual display only: no recovery, fallback, engine start, or health-state publication. */
    private fun measureSimpleRealDelay(requestID: String) {
        if (!manualDelayRunning.compareAndSet(false, true)) return
        val service = getService()
        if (service == null) { manualDelayRunning.set(false); return }
        val generation = reconnectGeneration.get()
        backgroundScope.launch {
            try {
                val elapsed = runCatching {
                    check(isEngineRunning() && MmkvManager.getSelectServer() == com.v2ray.ang.simple.SimpleRuntime.STAGED_GUID)
                    com.v2ray.ang.simple.ManualRealDelay.measure(
                        MmkvManager.decodeSettingsInt(com.v2ray.ang.simple.SimpleRuntime.PROBE_PORT, 0))
                }.getOrDefault(-1L)
                if (generation == reconnectGeneration.get()) {
                    MessageUtil.sendMsg2UI(service, com.v2ray.ang.simple.SimpleRuntime.MSG_REAL_DELAY, "$requestID|$elapsed")
                }
            } finally { manualDelayRunning.set(false) }
        }
    }

    private fun measureV2rayDelay() {
        if (!isEngineRunning()) {
            return
        }
        val generation = reconnectGeneration.get()
        val selected = MmkvManager.getSelectServer()
        fun currentSession() = generation == reconnectGeneration.get() &&
            selected == MmkvManager.getSelectServer() && isEngineRunning()

        CoroutineScope(Dispatchers.IO).launch {
            val service = getService() ?: return@launch

            if (currentConfig?.configType == EConfigType.AMNEZIAWG) {
                val result = measureAmneziaWgRoute(service, HEALTH_PROBE_TIMEOUT_MS)
                if (!currentSession()) return@launch
                MessageUtil.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_SUCCESS, result.message)
                if (result.success) publishMeasuredDelay(service, result.delayMillis)
                MessageUtil.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_RESULT, result.success.toString())
                return@launch
            }

            // TURN must keep this application's UID outside the TUN so its transport does not
            // loop back into itself. Verify that Android created the VPN, then make a real HTTPS
            // request through Xray's local SOCKS listener to exercise the entire TURN data path.
            if (VkTurnManager.isTurnProfile(currentConfig) ||
                MmkvManager.getSelectServer()==com.v2ray.ang.simple.SimpleRuntime.STAGED_GUID) {
                val result = measureProxyRoute(
                    service = service,
                    timeoutMillis = TURN_ROUTE_PROBE_TIMEOUT_MS,
                    requireVpnNetwork = true,
                )
                if (!currentSession()) return@launch
                MessageUtil.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_SUCCESS, result.message)
                if (result.success) publishMeasuredDelay(service, result.delayMillis)
                MessageUtil.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_RESULT, result.success.toString())
                return@launch
            }

            var time = -1L
            var errorStr = ""

            try {
                time = coreController.measureDelay(SettingsManager.getDelayTestUrl())
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to measure delay", e)
                errorStr = e.message?.substringAfter("\":") ?: "empty message"
            }
            if (time == -1L) {
                try {
                    time = coreController.measureDelay(SettingsManager.getDelayTestUrl(true))
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to measure delay", e)
                    errorStr = e.message?.substringAfter("\":") ?: "empty message"
                }
            }

            if (!currentSession()) return@launch
            val result = if (time >= 0) {
                service.getString(R.string.connection_test_available, time)
            } else {
                service.getString(R.string.connection_test_error, errorStr)
            }
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_SUCCESS, result)
            if (time >= 0L) publishMeasuredDelay(service, time)
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_RESULT, (time >= 0).toString())

            // Only fetch IP info if the delay test was successful
            if (time >= 0) {
                SpeedtestManager.getRemoteIPInfo()?.let { ip ->
                    MessageUtil.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_SUCCESS, "$result\n$ip")
                }
            }
        }
    }

    private data class ConnectionProbeResult(
        val success: Boolean,
        val message: String,
        val delayMillis: Long = -1L,
    )

    private fun measureProxyRoute(
        service: Service,
        timeoutMillis: Long,
        requireVpnNetwork: Boolean,
    ): ConnectionProbeResult {
        if (requireVpnNetwork) {
            val connectivity = service.getSystemService(ConnectivityManager::class.java)
                ?: return ConnectionProbeResult(
                    false,
                    service.getString(R.string.connection_test_route_error, "VPN network unavailable"),
                )
            val hasVpnNetwork = connectivity.allNetworks.any { network ->
                connectivity.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
            if (!hasVpnNetwork) {
                return ConnectionProbeResult(
                    false,
                    service.getString(R.string.connection_test_route_error, "VPN route missing"),
                )
            }
        }

        if(MmkvManager.getSelectServer()==com.v2ray.ang.simple.SimpleRuntime.STAGED_GUID) {
            val port=MmkvManager.decodeSettingsInt(com.v2ray.ang.simple.SimpleRuntime.PROBE_PORT,0)
            if(port !in 1..65535) return ConnectionProbeResult(false,"Health inlet missing")
            val rawResolvers=if(ManagedDnsTunnelManager.isManagedDnsTunnelProfile(currentConfig)) listOf("tcp://8.8.8.8:53")
                else SettingsManager.getRemoteDnsServers()
            val resolvers=rawResolvers.mapNotNull { raw -> runCatching {
                val uri=java.net.URI(if("://" in raw) raw else "tcp://$raw")
                val host=uri.host ?: return@runCatching null
                InetSocketAddress.createUnresolved(host.removeSurrounding("[","]"),if(uri.port>0) uri.port else 53)
            }.getOrNull() }
            val power=service.getSystemService(PowerManager::class.java)
            val lock=power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"${AppConfig.ANG_PACKAGE}:vpn-health")
            return try {
                lock?.acquire(timeoutMillis+2000)
                val result=TunnelHealthProbe.check(InetSocketAddress(AppConfig.LOOPBACK,port),
                    InetSocketAddress(AppConfig.LOOPBACK,SettingsManager.getSocksPort()),resolvers,timeoutMillis,verifyAppRoute=isSimpleAmnezia())
                ConnectionProbeResult(result.success,result.detail,result.elapsedMillis)
            } finally { if(lock?.isHeld==true) runCatching { lock.release() } }
        }

        val startedAt = SystemClock.elapsedRealtime()
        val socksProxy = Proxy(
            Proxy.Type.SOCKS,
            InetSocketAddress(AppConfig.LOOPBACK, if (MmkvManager.getSelectServer() == com.v2ray.ang.simple.SimpleRuntime.STAGED_GUID)
                MmkvManager.decodeSettingsInt(com.v2ray.ang.simple.SimpleRuntime.PROBE_PORT, SettingsManager.getSocksPort())
            else SettingsManager.getSocksPort()),
        )
        val client = OkHttpClient.Builder()
            .proxy(socksProxy)
            .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()

        return try {
            val request = Request.Builder()
                .url(PROXY_ROUTE_PROBE_URL)
                .header("User-Agent", "KindlyVPN route check")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use ConnectionProbeResult(
                        false,
                        service.getString(R.string.connection_test_route_error, "HTTP ${response.code}"),
                    )
                }

                // Reading the body proves that response data, not just a TCP handshake, crossed
                // the selected proxy transport.
                val hasResponseData = response.body.byteStream().use { input ->
                    input.read() != -1
                }
                if (!hasResponseData) {
                    return@use ConnectionProbeResult(
                        false,
                        service.getString(R.string.connection_test_route_error, "Empty response"),
                    )
                }
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                ConnectionProbeResult(
                    true,
                    service.getString(R.string.connection_test_route_available, elapsed),
                    elapsed,
                )
            }
        } catch (e: Exception) {
            val detail = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            ConnectionProbeResult(
                false,
                service.getString(R.string.connection_test_route_error, detail),
            )
        }
    }

    /** Measures real HTTPS traffic through the VPN interface used by the AmneziaWG engine. */
    private fun measureAmneziaWgRoute(service: Service, timeoutMillis: Long): ConnectionProbeResult {
        val connectivity = service.getSystemService(ConnectivityManager::class.java)
            ?: return ConnectionProbeResult(
                false,
                service.getString(R.string.connection_test_route_error, "VPN network unavailable"),
            )
        val hasVpnNetwork = connectivity.allNetworks.any { network ->
            connectivity.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
        if (!hasVpnNetwork) {
            return ConnectionProbeResult(
                false,
                service.getString(R.string.connection_test_route_error, "VPN route missing"),
            )
        }

        val startedAt = SystemClock.elapsedRealtime()
        val client = OkHttpClient.Builder()
            .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
        return try {
            val request = Request.Builder()
                .url(PROXY_ROUTE_PROBE_URL)
                .header("User-Agent", "KindlyVPN AmneziaWG route check")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use ConnectionProbeResult(
                        false,
                        service.getString(R.string.connection_test_route_error, "HTTP ${response.code}"),
                    )
                }
                val hasResponseData = response.body.byteStream().use { it.read() != -1 }
                if (!hasResponseData) {
                    return@use ConnectionProbeResult(
                        false,
                        service.getString(R.string.connection_test_route_error, "Empty response"),
                    )
                }
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                ConnectionProbeResult(
                    true,
                    service.getString(R.string.connection_test_route_available, elapsed),
                    elapsed,
                )
            }
        } catch (e: Exception) {
            val detail = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            ConnectionProbeResult(
                false,
                service.getString(R.string.connection_test_route_error, detail),
            )
        }
    }

    /** Uses the same real data-path probe for TURN and every profile with a local SOCKS inlet. */
    private fun measureCurrentRoute(service: Service, timeoutMillis: Long): ConnectionProbeResult {
        if (currentConfig?.configType == EConfigType.AMNEZIAWG) {
            return measureAmneziaWgRoute(service, timeoutMillis)
        }
        val turnProfile = VkTurnManager.isTurnProfile(currentConfig)
        val hasLocalProxy = turnProfile ||
            SettingsManager.isUsingHevTun() ||
            SettingsManager.isRootMode() ||
            MmkvManager.decodeSettingsBool(AppConfig.PREF_ENABLE_LOCAL_PROXY, true)
        if (hasLocalProxy) {
            return measureProxyRoute(
                service = service,
                timeoutMillis = timeoutMillis,
                requireVpnNetwork = turnProfile || SettingsManager.isVpnMode(),
            )
        }

        var detail = "No response"
        listOf(SettingsManager.getDelayTestUrl(), SettingsManager.getDelayTestUrl(true)).forEach { url ->
            try {
                val delayMillis = coreController.measureDelay(url)
                if (delayMillis >= 0L) {
                    return ConnectionProbeResult(
                        true,
                        service.getString(R.string.connection_test_available, delayMillis),
                        delayMillis,
                    )
                }
            } catch (e: Exception) {
                detail = e.message?.takeIf(String::isNotBlank) ?: e.javaClass.simpleName
            }
        }
        return ConnectionProbeResult(
            false,
            service.getString(R.string.connection_test_error, detail),
        )
    }

    private fun publishMeasuredDelay(service: Service, delayMillis: Long) {
        if (delayMillis < 0L) return
        MmkvManager.getSelectServer()?.takeIf(String::isNotBlank)?.let { guid ->
            MmkvManager.encodeServerTestDelayMillis(guid, delayMillis)
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_MEASURE_CONFIG_SUCCESS, guid)
        }
        MessageUtil.sendMsg2UI(
            service,
            AppConfig.MSG_MEASURE_DELAY_VALUE,
            delayMillis.toString(),
        )
    }

    /** Socket migration is necessary even with automatic recovery disabled. */
    fun onUnderlyingNetworkChanged(networkHandle: Long) {
        val previous = lastPhysicalNetworkHandle
        if (previous != networkHandle) turnRouteEpoch.incrementAndGet()
        lastPhysicalNetworkHandle = networkHandle
        if (previous != networkHandle) amneziaNetworkBlocked = false
        if (previous != networkHandle && isSimpleAmnezia()) {
            amneziaMigration.request(networkHandle)
            refreshAmneziaAfterResume("network:$networkHandle", rebindFirst = true)
        }
        if(previous == null || previous == networkHandle) return
        physicalNetworkRefreshJob?.cancel()
        val generation = reconnectGeneration.get()
        val routeEpoch = turnRouteEpoch.get()
        physicalNetworkRefreshJob = backgroundScope.launch {
            val owner = coroutineContext[Job]
            fun wanted() = owner?.isActive == true && generation == reconnectGeneration.get() &&
                routeEpoch == turnRouteEpoch.get() && activeConnection.get() &&
                SettingsManager.isConnectionDesired() && !handlingConnectionDrop.get()
            delay(300L)
            if (!wanted()) return@launch
            val service = getService() ?: return@launch
            // TURN sockets cannot migrate between Android networks. A compatible
            // helper replaces its relay slots while retaining logical connections.
            // Network migration is required even when timed recovery is disabled.
            if(VkTurnManager.isTurnProfile(currentConfig) && !DnsTunnelManager.active) {
                turnTransportRecoveryJob?.cancel()
                turnTransportRecoveryJob = null
                transitionRecovery(VpnRecoveryPhase.STARTING_TURN)
                runCatching {
                    if (VkTurnManager.refreshPhysicalNetwork(service,checkNotNull(currentConfig), ::wanted) && wanted()) {
                        // The listener survived, so no new READY log will arrive.
                        // A control ACK alone cannot establish working traffic.
                        recoveryInitialProbeJob?.cancel()
                        recoveryInitialProbeJob = null
                        transitionRecovery(VpnRecoveryPhase.VERIFYING_CONNECTION)
                        verifyInitialRecoveryRoute(afterMigration = true)
                    }
                }
                    .onFailure { error ->
                        LogUtil.w(AppConfig.TAG,"Could not refresh TURN after network change",error)
                        transitionRecovery(VpnRecoveryPhase.FAILED)
                    }
            }
        }
    }

    internal fun isSimpleAmnezia() =
        MmkvManager.getSelectServer() == com.v2ray.ang.simple.SimpleRuntime.STAGED_GUID &&
            !MmkvManager.decodeSettingsString(com.v2ray.ang.simple.AmneziaRuntime.SETTINGS).isNullOrBlank()

    fun onUnderlyingAddressChanged(networkHandle: Long, addresses: String) {
        if (isSimpleAmnezia()) {
            amneziaMigration.request(networkHandle)
            refreshAmneziaAfterResume("address:$networkHandle:$addresses", rebindFirst = true)
        }
    }

    fun onUnderlyingNetworkBlocked(networkHandle: Long, blocked: Boolean) {
        if (networkHandle != lastPhysicalNetworkHandle) return
        amneziaNetworkBlocked = blocked
        if (blocked) refreshAmneziaAfterResume("blocked:$networkHandle")
    }

    /** Check a real usable-network transition even if the outage escaped the last probe. */
    fun onUnderlyingNetworkUsable(networkHandle: Long) {
        if (networkHandle != lastPhysicalNetworkHandle || !isSimpleAmnezia()) return
        // A short outage on the same Wi-Fi may never leave CONNECTED. The
        // caller already filters for a real validated/unblocked transition.
        amneziaResumeGate.reset()
        refreshAmneziaAfterResume("usable:$networkHandle")
    }

    internal fun onAmneziaHealthAlarm() {
        if (!isSimpleAmnezia() || !activeConnection.get() || expectedShutdown.get() ||
            !SettingsManager.isConnectionDesired() || amneziaResumeJob?.isActive != true) {
            AmneziaHealthAlarm.releaseHandoff()
            return
        }
        amneziaWake.trySend(Unit)
    }

    /** One daemon-owned loop. Events wake it; only stop/profile changes cancel its ownership. */
    @Synchronized
    private fun refreshAmneziaAfterResume(event: String, rebindFirst: Boolean = false) {
        if (!isSimpleAmnezia() || !activeConnection.get() || expectedShutdown.get() ||
            !SettingsManager.isConnectionDesired()) return
        // An in-flight attempt or pending retry already owns helper recovery.
        // A delayed exit broadcast must not bypass the increasing backoff.
        if (event == "helper-exit" && amneziaCheckingOrRetrying) return
        if (event != "helper-exit" && !amneziaResumeGate.admit(event, SystemClock.elapsedRealtime())) return
        if (rebindFirst && amneziaMigration.pending() == null) {
            lastPhysicalNetworkHandle?.let(amneziaMigration::request)
        }
        recoveryInitialProbeJob?.cancel()
        amneziaWake.trySend(Unit)
        if (amneziaResumeJob?.isActive == true) return
        val epoch = amneziaResumeEpoch.incrementAndGet()
        val generation = reconnectGeneration.get()
        val loop = backgroundScope.launch(start = CoroutineStart.LAZY) {
            val owner = coroutineContext[Job]
            fun valid() = owner?.isActive == true && epoch == amneziaResumeEpoch.get() &&
                generation == reconnectGeneration.get() && activeConnection.get() &&
                SettingsManager.isConnectionDesired() && !expectedShutdown.get() && isSimpleAmnezia()
            val control = serviceControl?.get() ?: return@launch
            val service = control.getService()
            fun networkAvailable() = !amneziaNetworkBlocked && hasUnderlyingInternet(service)
            val policy = AmneziaRecoveryPolicy()
            fun attemptWanted() = valid() && !policy.exhausted(SystemClock.elapsedRealtime())

            suspend fun waitForEvent(waitMillis: Long, minimumWaitMillis: Long = 0) {
                val remaining = policy.remainingOutageMillis(SystemClock.elapsedRealtime())
                if (remaining == null) {
                    val armed = AmneziaHealthAlarm.schedule(service, waitMillis)
                    try {
                        // Prefer the wakeup delivery; retain a timer if the OS delays it.
                        withTimeoutOrNull(waitMillis + if (armed) 1_000L else 0L) { amneziaWake.receive() }
                    } finally {
                        AmneziaHealthAlarm.cancelPending()
                    }
                    return
                }
                // Keep the bounded outage timer running with the screen off.
                val timeout = minOf(waitMillis, remaining)
                val earliestNextRound = SystemClock.elapsedRealtime() + minOf(timeout, minimumWaitMillis)
                withRecoveryWakeLock(service, timeoutMillis = timeout + 1_000L) {
                    AmneziaHealthAlarm.releaseHandoff()
                    withTimeoutOrNull(timeout) { amneziaWake.receive() }
                    delay((earliestNextRound - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
                }
            }

            suspend fun disconnectIfExhausted(): Boolean {
                if (!policy.exhausted(SystemClock.elapsedRealtime())) return false
                withContext(Dispatchers.Main.immediate) {
                    if (valid() && serviceControl?.get() === control) {
                        LogUtil.w(AppConfig.TAG, "[AMNEZIA-RECOVERY] Recovery exhausted; removing VPN")
                        com.v2ray.ang.simple.DaemonConnectionState.recordHealth(
                            false, false, "Amnezia recovery exhausted",
                        )
                        SettingsManager.setConnectionDesired(false)
                        control.stopService()
                    }
                }
                return true
            }
            var migrationRevision: Long? = null
            delay(500)
            amneziaResumeMutex.lock()
            try {
                while (valid()) {
                    while (amneziaWake.tryReceive().isSuccess) { }
                    if (disconnectIfExhausted()) break
                    if (!networkAvailable()) {
                        policy.connectionUnavailable(SystemClock.elapsedRealtime())
                        com.v2ray.ang.simple.DaemonConnectionState.recordHealth(false, false, "Waiting for physical network")
                        if (mutableRecoveryState.value.phase != VpnRecoveryPhase.WAITING_FOR_NETWORK) {
                            (service as? CoreVpnService)?.retryAmneziaNetworkCandidate()
                        }
                        transitionRecovery(VpnRecoveryPhase.WAITING_FOR_NETWORK, attempt = 0)
                        // Network callbacks wake immediately; polling also covers older Android
                        // versions and callbacks that fail to register.
                        waitForEvent(RECONNECT_NETWORK_POLL_MS)
                        continue
                    }
                    val now = SystemClock.elapsedRealtime()
                    val recentReceive = com.v2ray.ang.simple.AmneziaRuntime.recentPayload(now)
                    amneziaCheckingOrRetrying = true
                    val result = try {
                        withRecoveryWakeLock(service, timeoutMillis = 45_000L) {
                            AmneziaHealthAlarm.releaseHandoff()
                            if (!valid()) return@withRecoveryWakeLock null
                            val target = amneziaMigration.pending()
                            if (target != null && target.revision != migrationRevision) {
                                // Failures on the old network must not immediately
                                // destroy streams when trying a new candidate.
                                migrationRevision = target.revision
                                policy.newRoute()
                            }
                            val alive = com.v2ray.ang.simple.AmneziaRuntime.isAlive()
                            val replace = policy.replaceHelper(alive)
                            if (replace) {
                                policy.replacing()
                                LogUtil.i(AppConfig.TAG, "[AMNEZIA-RECOVERY] Replacing helper; Xray/TUN retained")
                                val ready = com.v2ray.ang.simple.AmneziaRuntime.restart(service,
                                    target?.network ?: lastPhysicalNetworkHandle ?: 0, ::attemptWanted)
                                if (ready && target != null) amneziaMigration.completed(target)
                            }
                            com.v2ray.ang.simple.DaemonConnectionState.recordHealth(started = true)
                            AmneziaResumeCheck.run(
                                rebindFirst = replace || policy.failures > 0 || recentReceive,
                                valid = ::attemptWanted,
                                refresh = {
                                    val migration = amneziaMigration.pending()
                                    if (migration != null || (!replace && (policy.failures > 0 || !recentReceive))) {
                                        val reset = policy.resetTransport(
                                            com.v2ray.ang.simple.AmneziaRuntime.recentPayload(SystemClock.elapsedRealtime()))
                                        if (reset) policy.resetting()
                                        val rebound = com.v2ray.ang.simple.AmneziaRuntime.refreshNetwork(service,
                                            migration?.network ?: lastPhysicalNetworkHandle ?: 0, ::attemptWanted, reset)
                                        if (rebound && migration != null) amneziaMigration.completed(migration)
                                        delay(500)
                                    }
                                },
                                probe = {
                                    val remaining = policy.remainingOutageMillis(SystemClock.elapsedRealtime())
                                    val timeout = minOf(HEALTH_PROBE_TIMEOUT_MS, remaining ?: HEALTH_PROBE_TIMEOUT_MS)
                                    val measured = measureCurrentRoute(service, timeout)
                                    currentCoroutineContext().ensureActive()
                                    measured
                                },
                                healthy = { it.success },
                                migrationPending = { amneziaMigration.pending() != null },
                            )
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        LogUtil.w(AppConfig.TAG, "[AMNEZIA-RECOVERY] Bounded attempt failed", e)
                        ConnectionProbeResult(false, "Amnezia recovery attempt failed")
                    }
                    if (!valid() || disconnectIfExhausted()) break
                    if (!networkAvailable()) continue
                    val checked = when {
                        !com.v2ray.ang.simple.AmneziaRuntime.isAlive() -> ConnectionProbeResult(false, "Amnezia helper stopped")
                        amneziaMigration.pending() != null -> ConnectionProbeResult(false, "Amnezia network migration is pending")
                        else -> result
                    }
                    if (checked == null) break
                    com.v2ray.ang.simple.DaemonConnectionState.recordHealth(false, checked.success, checked.message)
                    if (checked.success) {
                        amneziaCheckingOrRetrying = false
                        policy.healthy()
                        transitionRecovery(VpnRecoveryPhase.CONNECTED, attempt = 0)
                    } else {
                        policy.failed(SystemClock.elapsedRealtime())
                        if (disconnectIfExhausted()) break
                        // An unvalidated Wi-Fi candidate must not replace a working
                        // route, but can be tried after the selected route fails.
                        (service as? CoreVpnService)?.retryAmneziaNetworkCandidate()
                        val enteringRecovery = mutableRecoveryState.value.phase != VpnRecoveryPhase.RETRY_WAIT
                        transitionRecovery(VpnRecoveryPhase.RETRY_WAIT, attempt = 0)
                        if (enteringRecovery) NotificationManager.showRecoveryForeground(service, "Amnezia: восстановление соединения")
                        LogUtil.i(AppConfig.TAG, "[AMNEZIA-RECOVERY] Retry in ${policy.delayMillis()} ms; failed rounds=${policy.failures}")
                    }
                    // A crashing helper can wake the loop but cannot create a tight restart loop.
                    waitForEvent(policy.delayMillis(), minimumWaitMillis = 2_000L)
                }
            } finally {
                AmneziaHealthAlarm.cancel()
                amneziaCheckingOrRetrying = false
                amneziaResumeMutex.unlock()
            }
        }
        amneziaResumeJob = loop
        loop.start()
    }

    /** Transport resolver migration only; no HTTPS probe, VPN restart or retry loop. */
    internal fun refreshManagedDnsNetwork() {
        val generation=reconnectGeneration.get()
        backgroundScope.launch {
            if(generation!=reconnectGeneration.get() || !activeConnection.get() || !SettingsManager.isConnectionDesired()) return@launch
            val profile=currentConfig?.takeIf(ManagedDnsTunnelManager::isManagedDnsTunnelProfile) ?: return@launch
            val service=getService() ?: return@launch
            runCatching { ManagedDnsTunnelManager.ensureForProfile(service,profile) }
                .onFailure { LogUtil.w(AppConfig.TAG,"DNS resolver network migration failed",it) }
        }
    }

    /** Re-checks the full proxy route soon after Android reports an underlying network change. */
    fun onUnderlyingNetworkAvailable() {
        if (isSimpleAmnezia() && mutableRecoveryState.value.phase == VpnRecoveryPhase.WAITING_FOR_NETWORK) {
            refreshAmneziaAfterResume("available:${lastPhysicalNetworkHandle ?: 0}", rebindFirst = true)
        }
        networkLossJob?.cancel()
        networkLossJob = null
        if (handlingConnectionDrop.get()) {
            LogUtil.i(AppConfig.TAG, "[VPN-RECONNECT] Underlying network available")
            return
        }
        if (
            !isVkAutoReconnectEnabled() ||
            !activeConnection.get() ||
            handlingConnectionDrop.get()
        ) {
            return
        }
        immediateHealthProbeJob?.cancel()
        val generation=reconnectGeneration.get()
        immediateHealthProbeJob = backgroundScope.launch {
            delay(NETWORK_CHANGE_SETTLE_DELAY_MS)
            if(generation!=reconnectGeneration.get() || !isVkAutoReconnectEnabled()) return@launch
            val service = getService() ?: return@launch
            if (DnsTunnelManager.active) {
                runCatching { DnsTunnelManager.ensure(service) }
            }
            checkTunnelHealth(service)
        }
    }

    /** Debounces physical-network loss before starting recovery so handovers do not race. */
    fun onUnderlyingNetworkLost() {
        turnRouteEpoch.incrementAndGet()
        if (isSimpleAmnezia() && activeConnection.get() && SettingsManager.isConnectionDesired()) {
            amneziaResumeGate.reset()
            lastPhysicalNetworkHandle = null
            amneziaNetworkBlocked = false
            refreshAmneziaAfterResume("network-lost")
        }
        if (
            !isVkAutoReconnectEnabled() ||
            !activeConnection.get() ||
            handlingConnectionDrop.get()
        ) {
            return
        }
        networkLossJob?.cancel()
        val generation=reconnectGeneration.get()
        networkLossJob = backgroundScope.launch {
            delay(NETWORK_LOSS_SETTLE_DELAY_MS)
            if(generation!=reconnectGeneration.get() || !isVkAutoReconnectEnabled()) return@launch
            val control = serviceControl?.get() ?: return@launch
            if (
                activeConnection.get() &&
                !handlingConnectionDrop.get() &&
                !hasUnderlyingInternet(control.getService())
            ) {
                handleConnectionDrop(
                    control,
                    control.getService().getString(R.string.vpn_underlying_network_lost),
                )
            }
        }
    }

    /** Keeps the connection pending while the user completes the challenge. */
    fun onTurnCaptchaRequired(context: Context, challengeID: Long) = synchronized(turnCaptchaLock) {
        if (!SettingsManager.isConnectionDesired() ||
            !VkTurnManager.isVkTurnProfile(MmkvManager.getSelectServer()?.let(MmkvManager::decodeServerConfig))) return@synchronized
        val challenge = VkTurnManager.captchaChallenge()?.takeIf { it.id == challengeID } ?: return@synchronized
        // Replayed status markers must not extend the deadline.
        if (turnCaptchaWatchdogID == challengeID && turnCaptchaWatchdogJob?.isActive == true) return@synchronized
        cancelTurnCaptchaWatchdog()
        val appContext = context.applicationContext
        val generation = reconnectGeneration.get()
        turnNotReadySince.set(challenge.startedAtMillis)
        VkTurnManager.requireCaptchaUser(challengeID)
        transitionRecovery(VpnRecoveryPhase.TURN_CAPTCHA_MANUAL)
        NotificationManager.showTurnCaptchaRequired(appContext, automatic = false)
        turnCaptchaWatchdogID = challengeID
        turnCaptchaWatchdogJob = backgroundScope.launch {
            while (isActive && generation == reconnectGeneration.get() && SettingsManager.isConnectionDesired()) {
                val current = VkTurnManager.captchaChallenge()?.takeIf { it.id == challengeID } ?: return@launch
                val now = SystemClock.elapsedRealtime()
                if (current.expired(now)) {
                    onTurnCaptchaFailed(appContext, challengeID)
                    return@launch
                }
                if (current.shouldHandOff(now)) handOffTurnCaptcha(appContext, challengeID, generation)
                delay(500L)
            }
        }
    }

    private fun handOffTurnCaptcha(context: Context, challengeID: Long, generation: Long) = synchronized(turnCaptchaLock) {
        if (generation != reconnectGeneration.get() || !SettingsManager.isConnectionDesired() ||
            !VkTurnManager.requireCaptchaUser(challengeID)) return@synchronized
        transitionRecovery(VpnRecoveryPhase.TURN_CAPTCHA_MANUAL, generation = generation)
        NotificationManager.showTurnCaptchaRequired(context, automatic = false)
        LogUtil.i(AppConfig.TAG, "VK captcha: waiting for user; keeping the original challenge deadline")
    }

    /** A rejected/expired human challenge must not keep the phone trapped in a dead VPN. */
    internal fun onTurnCaptchaFailed(context: Context, challengeID: Long) {
        val control = synchronized(turnCaptchaLock) {
            if (VkTurnManager.captchaChallenge()?.id != challengeID || !SettingsManager.isConnectionDesired()) return
            LogUtil.w(AppConfig.TAG, "VK captcha: challenge failed or expired; releasing VPN")
            SettingsManager.setConnectionDesired(false)
            cancelAutomaticRecovery(context)
            serviceControl?.get()
        }
        if (control != null) control.stopService()
        else MessageUtil.sendMsg2Service(context, AppConfig.MSG_STATE_STOP, "")
    }

    /** All readiness owners use this same absolute five-minute window. */
    private fun waitingForTurnCaptcha(context: Context): Boolean {
        val challenge = VkTurnManager.captchaChallenge() ?: return false
        val now = SystemClock.elapsedRealtime()
        if (challenge.expired(now)) onTurnCaptchaFailed(context, challenge.id)
        else if (challenge.shouldHandOff(now)) handOffTurnCaptcha(context, challenge.id, reconnectGeneration.get())
        return true
    }

    /** Cancels the daemon-owned CAPTCHA deadline once the helper progresses or stops. */
    fun onTurnCaptchaFinished() = synchronized(turnCaptchaLock) {
        // A replacement can publish a new challenge after the old helper's log
        // was accepted. Its callback must not cancel the new challenge's owner.
        if (VkTurnManager.captchaChallenge() != null) return@synchronized
        cancelTurnCaptchaWatchdog()
        if (!SettingsManager.isConnectionDesired() ||
            !VkTurnManager.isVkTurnProfile(MmkvManager.getSelectServer()?.let(MmkvManager::decodeServerConfig))) return@synchronized
        // The transport repair owns its readiness/probe sequence. Do not start
        // a competing initial probe or advertise CONNECTED from a log marker.
        if (turnTransportRecoveryJob?.isActive == true) {
            if (VkTurnManager.isReadyForTraffic()) transitionRecovery(VpnRecoveryPhase.TURN_READY)
            else transitionRecovery(VpnRecoveryPhase.STARTING_TURN)
            return@synchronized
        }
        if (VkTurnManager.isReadyForTraffic() && !handlingConnectionDrop.get()) {
            transitionRecovery(VpnRecoveryPhase.VERIFYING_CONNECTION,attempt=0)
            verifyInitialRecoveryRoute()
        } else if (VkTurnManager.isReadyForTraffic()) {
            transitionRecovery(VpnRecoveryPhase.TURN_READY)
        } else {
            transitionRecovery(VpnRecoveryPhase.STARTING_TURN)
        }
        turnNotReadySince.set(
            if (VkTurnManager.isTurnProfile(currentConfig) && !VkTurnManager.isReadyForTraffic()) {
                SystemClock.elapsedRealtime()
            } else {
                0L
            },
        )
    }

    private fun cancelTurnCaptchaWatchdog() = synchronized(turnCaptchaLock) {
        turnCaptchaWatchdogJob?.cancel()
        turnCaptchaWatchdogJob = null
        turnCaptchaWatchdogID = 0L
    }

    /** Reflect native pool recovery without restarting its surviving sessions or retry timers. */
    internal fun onManagedDnsTunnelReadinessChanged() {
        if (!activeConnection.get() || handlingConnectionDrop.get() ||
            !SettingsManager.isConnectionDesired() ||
            !ManagedDnsTunnelManager.isManagedDnsTunnelProfile(currentConfig)
        ) return
        val service = getService() ?: return
        if (ManagedDnsTunnelManager.isReadyForTraffic()) {
            if(mutableRecoveryState.value.phase!=VpnRecoveryPhase.CONNECTED) {
                transitionRecovery(VpnRecoveryPhase.CONNECTED,attempt=0)
                MessageUtil.sendMsg2UI(service,AppConfig.MSG_STATE_START_SUCCESS,"")
            }
        } else {
            transitionRecovery(VpnRecoveryPhase.DNS_RECONNECTING, attempt = 0)
            NotificationManager.showRecoveryForeground(
                service,
                service.getString(R.string.managed_dns_tunnel_reconnecting),
            )
        }
    }

    private fun startManagedAccessMonitor(service: Service) {
        managedAccessJob?.cancel()
        managedAccessJob=backgroundScope.launch {
            val stateFile=java.io.File(service.noBackupFilesDir,"simple-configurations.enc")
            var modified=-1L
            while(activeConnection.get() && !expectedShutdown.get()) {
                val time=stateFile.lastModified()
                if(time!=modified || !com.v2ray.ang.simple.PanelAccess.stagedAllowed()) {
                    modified=time
                    // Recovery after a process exit between the atomic commit and
                    // updating MMKV: the encrypted committed state is authoritative.
                    if(!com.v2ray.ang.simple.PanelAccess.enforceSafely(service)) return@launch
                }
                delay(1000)
            }
        }
    }

    private fun startHealthMonitor(service: Service) {
        healthMonitorJob?.cancel()
        if (isSimpleAmnezia()) {
            refreshAmneziaAfterResume("monitor")
            return
        }
        if (handlingConnectionDrop.get() || !VkTurnManager.isVkTurnProfile(currentConfig)) return

        val generation = reconnectGeneration.get()
        healthMonitorJob = backgroundScope.launch {
            val schedule = com.v2ray.ang.simple.BackgroundProbeSchedule(SystemClock.elapsedRealtime())
            while (isActive && generation == reconnectGeneration.get()) {
                delay(1000L)
                if(!isVkAutoReconnectEnabled()) continue
                val interval = SettingsManager.getAutoReconnectCheckIntervalMillis()
                if (!schedule.isDue(SystemClock.elapsedRealtime(), interval)) continue
                if (
                    !activeConnection.get() ||
                    handlingConnectionDrop.get() ||
                    generation != reconnectGeneration.get()
                ) {
                    return@launch
                }
                // A concurrent settings toggle pauses the monitor; it must not
                // permanently terminate the job between the two flag reads.
                if(!isVkAutoReconnectEnabled()) continue
                try {
                    checkTunnelHealth(service)
                } catch(e:CancellationException) { throw e }
                catch(e:Exception) {
                    // One bad probe must not permanently kill background checks.
                    LogUtil.w(AppConfig.TAG,"Health monitor iteration failed",e)
                }
                schedule.completed(SystemClock.elapsedRealtime())
            }
        }
    }

    @Synchronized
    private fun verifyInitialRecoveryRoute(afterMigration: Boolean = false) {
        if (isSimpleAmnezia()) {
            refreshAmneziaAfterResume("initial")
            return
        }
        if (turnTransportRecoveryJob?.isActive == true) return
        if(recoveryInitialProbeJob?.isActive==true) return
        val generation = reconnectGeneration.get()
        val routeEpoch = turnRouteEpoch.get()
        recoveryInitialProbeJob = backgroundScope.launch {
            val result = if (afterMigration) {
                val service = getService() ?: return@launch
                withRecoveryWakeLock(service, 35_000L) {
                    awaitTurnRouteWindow(service) {
                        generation == reconnectGeneration.get() && routeEpoch == turnRouteEpoch.get() && activeConnection.get() &&
                            SettingsManager.isConnectionDesired() && !handlingConnectionDrop.get()
                    }
                }
            } else awaitHealthyRoute(generation)
            if (generation != reconnectGeneration.get() || routeEpoch != turnRouteEpoch.get() || !activeConnection.get() || handlingConnectionDrop.get() ||
                !SettingsManager.isConnectionDesired()) return@launch
            val control = serviceControl?.get() ?: return@launch
            if (waitingForTurnCaptcha(control.getService())) return@launch
            if (result?.success == true) {
                consecutiveHealthFailures.set(0)
                transitionRecovery(VpnRecoveryPhase.CONNECTED, attempt = 0, maximum = SettingsManager.getAutoReconnectMaxRetries())
                MessageUtil.sendMsg2UI(control.getService(), AppConfig.MSG_STATE_START_SUCCESS, AppConfig.SERVICE_STATE_VK_TURN)
                publishMeasuredDelay(control.getService(), result.delayMillis)
            } else if (afterMigration && !isVkAutoReconnectEnabled()) {
                // Still report reality when timed recovery is disabled; do not
                // destroy the user's VPN simply because migration took longer.
                transitionRecovery(VpnRecoveryPhase.CONNECTION_UNAVAILABLE)
            } else {
                handleConnectionDrop(control, "Резервный маршрут не прошёл проверку VPN.")
            }
        }
    }

    private fun stopHealthMonitor() {
        AmneziaHealthAlarm.cancel()
        amneziaMigration.reset()
        amneziaResumeGate.reset()
        amneziaResumeEpoch.incrementAndGet()
        amneziaResumeJob?.cancel()
        amneziaResumeJob = null
        while (amneziaWake.tryReceive().isSuccess) { }
        turnTransportRecoveryJob?.cancel()
        turnTransportRecoveryJob = null
        managedAccessJob?.cancel(); managedAccessJob=null
        physicalNetworkRefreshJob?.cancel()
        physicalNetworkRefreshJob = null
        recoveryInitialProbeJob?.cancel()
        recoveryInitialProbeJob = null
        healthMonitorJob?.cancel()
        healthMonitorJob = null
        immediateHealthProbeJob?.cancel()
        immediateHealthProbeJob = null
        networkLossJob?.cancel()
        networkLossJob = null
        consecutiveHealthFailures.set(0)
    }

    private fun checkTunnelHealth(service: Service) {
        if (!isVkAutoReconnectEnabled()) return
        if (turnTransportRecoveryJob?.isActive == true) return
        if (recoveryInitialProbeJob?.isActive == true) return
        if (!healthProbeRunning.compareAndSet(false, true)) return
        val probeGeneration = reconnectGeneration.get()
        val probeRouteEpoch = turnRouteEpoch.get()
        try {
            val turnProfile = VkTurnManager.isTurnProfile(currentConfig)
            if (turnProfile && waitingForTurnCaptcha(service)) return
            if (turnProfile && !VkTurnManager.isReadyForTraffic()) {
                if (DnsTunnelManager.active && !DnsTunnelManager.running) {
                    serviceControl?.get()?.let { handleConnectionDrop(it, service.getString(R.string.dns_tunnel_unavailable)) }
                    return
                }
                val now = SystemClock.elapsedRealtime()
                val startedAt = turnNotReadySince.updateAndGet { existing ->
                    if (existing > 0L) existing else now
                }
                val timeout = ConnectionDropPolicy.turnReadyTimeoutMillis(
                    captchaPending = false,
                    autoCaptcha = VkTurnManager.isAutoCaptchaEnabled(),
                    recoveryTransport = DnsTunnelManager.active,
                )
                if (ConnectionDropPolicy.hasTimedOut(startedAt, now, timeout)) {
                    serviceControl?.get()?.let { control ->
                        handleConnectionDrop(
                            control,
                            service.getString(R.string.vk_turn_ready_timeout),
                        )
                    }
                }
                return
            }
            turnNotReadySince.set(0L)

            com.v2ray.ang.simple.DaemonConnectionState.recordHealth(started=true)
            val result = measureCurrentRoute(service, HEALTH_PROBE_TIMEOUT_MS)
            if (
                probeGeneration != reconnectGeneration.get() ||
                probeRouteEpoch != turnRouteEpoch.get() ||
                !activeConnection.get() ||
                handlingConnectionDrop.get() ||
                turnTransportRecoveryJob?.isActive == true ||
                recoveryInitialProbeJob?.isActive == true ||
                !isVkAutoReconnectEnabled()
            ) {
                return
            }
            com.v2ray.ang.simple.DaemonConnectionState.recordHealth(started=false,success=result.success,detail=result.message)
            if (turnProfile && waitingForTurnCaptcha(service)) return
            if (result.success) {
                consecutiveHealthFailures.set(0)
                if(mutableRecoveryState.value.phase==VpnRecoveryPhase.VERIFYING_CONNECTION) transitionRecovery(VpnRecoveryPhase.CONNECTED,attempt=0)
                SettingsManager.clearAutoReconnectAttempt()
                publishMeasuredDelay(service, result.delayMillis)
                return
            }

            val failures = consecutiveHealthFailures.incrementAndGet()
            transitionRecovery(VpnRecoveryPhase.VERIFYING_CONNECTION)
            LogUtil.w(
                AppConfig.TAG,
                "Tunnel health check failed $failures/${ConnectionDropPolicy.FAILED_HEALTH_CHECKS_BEFORE_DROP}: ${result.message}",
            )
            if (ConnectionDropPolicy.isTunnelDown(failures)) {
                serviceControl?.get()?.let { control ->
                    handleConnectionDrop(
                        control,
                        service.getString(R.string.vpn_health_check_failed),
                    )
                }
            }
        } finally {
            healthProbeRunning.set(false)
        }
    }

    private fun cancelAutomaticRecovery(
        context: Context? = null,
        phase: VpnRecoveryPhase = VpnRecoveryPhase.DISCONNECTED,
    ) {
        reconnectGeneration.incrementAndGet()
        reconnectJob?.cancel()
        reconnectJob = null
        handlingConnectionDrop.set(false)
        stopHealthMonitor()
        cancelTurnCaptchaWatchdog()
        turnNotReadySince.set(0L)
        transitionRecovery(phase)
        context?.let(NotificationManager::cancelConnectionStatus)
    }

    private fun handleConnectionDrop(serviceControl: ServiceControl, reason: String?, allowTransportRepair: Boolean = true) {
        // A probe/network event may race a new challenge from a still-running
        // helper. Its bounded challenge owner, not a second recovery, decides.
        if (activeConnection.get() && isEngineRunning() && SettingsManager.isConnectionDesired() &&
            VkTurnManager.isVkTurnProfile(currentConfig) && waitingForTurnCaptcha(serviceControl.getService())) return
        if (isSimpleAmnezia() && activeConnection.get() && isEngineRunning() &&
            SettingsManager.isConnectionDesired() && !expectedShutdown.get()) {
            refreshAmneziaAfterResume("helper-exit")
            return
        }
        if (allowTransportRepair && activeConnection.get() && !handlingConnectionDrop.get() &&
            isVkAutoReconnectEnabled() && SettingsManager.isConnectionDesired() && isEngineRunning()) {
            repairTurnTransport(serviceControl, reason)
            return
        }
        if (!handlingConnectionDrop.compareAndSet(false, true)) return
        if (DnsTunnelManager.active) DnsTunnelManager.recordRouteFailure()

        val service = serviceControl.getService()
        val detail = reason?.takeIf { it.isNotBlank() }
            ?: service.getString(R.string.vpn_connection_dropped_default)
        val maximum = SettingsManager.getAutoReconnectMaxRetries()
        transitionRecovery(
            VpnRecoveryPhase.RECONNECT_SCHEDULED,
            attempt = SettingsManager.getAutoReconnectAttempt(),
            maximum = maximum,
        )
        LogUtil.w(AppConfig.TAG, "[VPN-RECONNECT] Trigger: connection lost")
        stopHealthMonitor()
        cancelTurnCaptchaWatchdog()
        activeConnection.set(false)
        MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_CONNECTION_DROPPED, detail)

        val shouldReconnect = ConnectionDropPolicy.shouldReconnect(
            isVkAutoReconnectEnabled(),
            unexpected = true,
        )
        NotificationManager.showConnectionLost(service, detail, shouldReconnect)
        val generation = reconnectGeneration.incrementAndGet()
        if (!shouldReconnect) {
            transitionRecovery(VpnRecoveryPhase.DISCONNECTED)
            serviceControl.stopService()
            handlingConnectionDrop.set(false)
            return
        }

        NotificationManager.showRecoveryForeground(
            service,
            service.getString(R.string.reconnect_notification_waiting_text),
        )
        transitionRecovery(VpnRecoveryPhase.STOPPING, maximum = maximum)
        val recoveryControl = try {
            serviceControl.takeIf { it.prepareForRecovery() }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "In-place VPN recovery preparation failed", e)
            null
        }
        if (recoveryControl == null) {
            serviceControl.stopService()
        }

        reconnectJob?.cancel()
        reconnectJob = backgroundScope.launch {
            runReconnectSequence(service.applicationContext, generation, recoveryControl)
        }
    }

    /** First repair the relay while leaving the Android VPN and direct flows intact. */
    @Synchronized
    private fun repairTurnTransport(control: ServiceControl, reason: String?) {
        if (turnTransportRecoveryJob?.isActive == true) return
        recoveryInitialProbeJob?.cancel()
        recoveryInitialProbeJob = null
        val generation = reconnectGeneration.get()
        val routeEpoch = turnRouteEpoch.get()
        val profile = currentConfig?.copy() ?: return
        val repair = backgroundScope.launch(start = CoroutineStart.LAZY) {
            val owner = coroutineContext[Job]
            fun wanted() = owner?.isActive == true && generation == reconnectGeneration.get() && routeEpoch == turnRouteEpoch.get() &&
                activeConnection.get() && SettingsManager.isConnectionDesired() && TurnRecoveryPolicy.enabled(
                    true, VkTurnManager.isVkTurnProfile(currentConfig), VkTurnManager.isVkTurnProfile(
                        MmkvManager.getSelectServer()?.let(MmkvManager::decodeServerConfig)))
            var recovered: ConnectionProbeResult? = null
            try {
                recovered = withRecoveryWakeLock(control.getService()) {
                    val networkDeadline = SystemClock.elapsedRealtime() +
                        if (VkTurnManager.supportsConnectionResume()) 30_000L else 15_000L
                    while (wanted() && !hasUnderlyingInternet(control.getService()) &&
                        SystemClock.elapsedRealtime() < networkDeadline) delay(300L)
                    if (!wanted() || !hasUnderlyingInternet(control.getService())) return@withRecoveryWakeLock null
                    if (VkTurnManager.refreshResumableTransport { wanted() && isVkAutoReconnectEnabled() }) {
                        transitionRecovery(VpnRecoveryPhase.VERIFYING_CONNECTION, attempt = 0)
                        awaitTurnRouteWindow(control.getService(), ::wanted)?.let { return@withRecoveryWakeLock it }
                    }
                    if (!wanted() || !isVkAutoReconnectEnabled()) return@withRecoveryWakeLock null
                    transitionRecovery(VpnRecoveryPhase.STARTING_TURN, attempt = 0)
                    LogUtil.i(AppConfig.TAG, "TURN transport-only repair; retaining VPN interface and Xray")
                    if (!VkTurnManager.recoverTransport(control.getService(), profile) {
                        wanted() && isVkAutoReconnectEnabled()
                    }) return@withRecoveryWakeLock null
                    awaitHealthyRoute(generation)
                }
                if (wanted() && recovered != null) {
                    consecutiveHealthFailures.set(0)
                    turnNotReadySince.set(0L)
                    SettingsManager.clearAutoReconnectAttempt()
                    transitionRecovery(VpnRecoveryPhase.CONNECTED, attempt = 0)
                    publishMeasuredDelay(control.getService(), recovered.delayMillis)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogUtil.w(AppConfig.TAG, "TURN transport-only repair failed", e)
            } finally {
                synchronized(this@CoreServiceManager) {
                    if (turnTransportRecoveryJob === coroutineContext[Job]) turnTransportRecoveryJob = null
                }
            }
            // Full interface/core recovery remains the last step, only after
            // relay-only repair fails and the original request is still current.
            if (recovered == null) {
                when (TurnRecoveryPolicy.afterFailedRepair(wanted(), isVkAutoReconnectEnabled())) {
                    TurnRecoveryPolicy.FailedRepairAction.RECONNECT -> handleConnectionDrop(control, reason, allowTransportRepair = false)
                    TurnRecoveryPolicy.FailedRepairAction.KEEP_TUNNEL -> transitionRecovery(VpnRecoveryPhase.CONNECTION_UNAVAILABLE)
                    TurnRecoveryPolicy.FailedRepairAction.IGNORE -> Unit
                }
            }
        }
        turnTransportRecoveryJob = repair
        repair.start()
    }

    internal fun automaticRecoveryAllowedForSelectedProfile(): Boolean =
        isSimpleAmnezia() || SettingsManager.isAutoReconnectOnDrop() && VkTurnManager.isVkTurnProfile(
            MmkvManager.getSelectServer()?.let(MmkvManager::decodeServerConfig))

    private fun isVkAutoReconnectEnabled(): Boolean = TurnRecoveryPolicy.enabled(
        SettingsManager.isAutoReconnectOnDrop(), VkTurnManager.isVkTurnProfile(currentConfig),
        VkTurnManager.isVkTurnProfile(MmkvManager.getSelectServer()?.let(MmkvManager::decodeServerConfig)))

    private suspend fun runReconnectSequence(context: Context, generation: Long, control: ServiceControl?) {
        while (reconnectGeneration.get() == generation && SettingsManager.isConnectionDesired()) {
            runReconnectBurst(context, generation, control)
            if (activeConnection.get() || !isVkAutoReconnectEnabled() || !SettingsManager.isConnectionDesired()) return
            // A short bounded burst, then a cooldown. Stop from UI/notification cancels this job.
            delay(30_000L)
            if (reconnectGeneration.get() != generation || !SettingsManager.isConnectionDesired()) return
            handlingConnectionDrop.set(true)
            SettingsManager.clearAutoReconnectAttempt()
        }
    }

    private suspend fun runReconnectBurst(
        context: Context,
        generation: Long,
        recoveryControl: ServiceControl?,
    ) {
        val maximum = SettingsManager.getAutoReconnectMaxRetries()
        var attempt = SettingsManager.getAutoReconnectAttempt()
        while (
            attempt < maximum &&
            reconnectGeneration.get() == generation &&
            isVkAutoReconnectEnabled()
        ) {
            // Losing Wi-Fi during a handover should not consume all configured attempts.
            if (!hasUnderlyingInternet(context)) {
                transitionRecovery(
                    VpnRecoveryPhase.WAITING_FOR_NETWORK,
                    attempt = attempt,
                    maximum = maximum,
                    generation = generation,
                )
                NotificationManager.showRecoveryForeground(
                    context,
                    context.getString(R.string.reconnect_notification_waiting_network),
                )
            }
            while (
                reconnectGeneration.get() == generation &&
                isVkAutoReconnectEnabled() &&
                !hasUnderlyingInternet(context)
            ) {
                delay(RECONNECT_NETWORK_POLL_MS)
            }
            if (
                reconnectGeneration.get() != generation ||
                !isVkAutoReconnectEnabled()
            ) return

            transitionRecovery(
                VpnRecoveryPhase.RETRY_WAIT,
                attempt = attempt,
                maximum = maximum,
                generation = generation,
            )
            LogUtil.i(AppConfig.TAG, "[VPN-RECONNECT] Starting reconnect immediately")
            val recovered = withRecoveryWakeLock(context) {
                currentCoroutineContext().ensureActive()
                if (
                    reconnectGeneration.get() != generation ||
                    !isVkAutoReconnectEnabled()
                ) return@withRecoveryWakeLock null
                attempt += 1
                SettingsManager.setAutoReconnectAttempt(attempt)
                transitionRecovery(
                    if (VkTurnManager.isTurnProfile(currentConfig)) {
                        VpnRecoveryPhase.STARTING_TURN
                    } else {
                        VpnRecoveryPhase.STARTING_CORE
                    },
                    attempt = attempt,
                    maximum = maximum,
                    generation = generation,
                )
                NotificationManager.showReconnectAttempt(context, attempt, maximum)
                NotificationManager.showRecoveryForeground(
                    context,
                    context.getString(R.string.reconnect_notification_attempt_text, attempt, maximum),
                )

                try {
                    val started = if (recoveryControl != null) {
                        awaitCoreStopped(generation) && recoveryControl.startRecoveryAttempt()
                    } else {
                        startContextService(context)
                        true
                    }
                    if (started) awaitHealthyRoute(generation) else null
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Automatic reconnect attempt $attempt failed", e)
                    null
                }
            }
            if (reconnectGeneration.get() != generation) return
            if (!isVkAutoReconnectEnabled()) {
                handlingConnectionDrop.set(false)
                reconnectJob = null
                SettingsManager.setConnectionDesired(false)
                recoveryControl?.stopService()
                return
            }
            if (recovered != null) {
                handlingConnectionDrop.set(false)
                consecutiveHealthFailures.set(0)
                turnNotReadySince.set(0L)
                SettingsManager.clearAutoReconnectAttempt()
                transitionRecovery(
                    VpnRecoveryPhase.CONNECTED,
                    attempt = 0,
                    maximum = maximum,
                    generation = generation,
                )
                getService()?.let { runningService ->
                    publishMeasuredDelay(runningService, recovered.delayMillis)
                    if (currentConfig?.configType != EConfigType.AMNEZIAWG) {
                        NotificationManager.startSpeedNotification()
                    }
                    startHealthMonitor(runningService)
                }
                NotificationManager.showConnectionRestored(context, recovered.delayMillis)
                MessageUtil.sendMsg2UI(
                    context,
                    AppConfig.MSG_STATE_CONNECTION_RESTORED,
                    recovered.delayMillis.toString(),
                )
                reconnectJob = null
                return
            }

            // Tear down a half-open core before the next fixed-delay attempt.
            transitionRecovery(
                VpnRecoveryPhase.STOPPING,
                attempt = attempt,
                maximum = maximum,
                generation = generation,
            )
            if (recoveryControl != null) {
                try {
                    recoveryControl.prepareForRecovery()
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Failed to reset VPN recovery attempt", e)
                }
            } else {
                serviceControl?.get()?.takeIf { activeConnection.get() || isEngineRunning() }
                    ?.stopService()
            }
        }

        if (
            reconnectGeneration.get() == generation &&
            isVkAutoReconnectEnabled()
        ) {
            handlingConnectionDrop.set(false)
            SettingsManager.clearAutoReconnectAttempt()
            transitionRecovery(
                VpnRecoveryPhase.FAILED,
                attempt = maximum,
                maximum = maximum,
                generation = generation,
            )
            NotificationManager.showReconnectFailed(context, maximum)
            NotificationManager.showRecoveryForeground(
                context,
                context.getString(R.string.reconnect_notification_failed_text, maximum),
            )
            MessageUtil.sendMsg2UI(
                context,
                AppConfig.MSG_STATE_RECONNECT_FAILED,
                context.getString(R.string.reconnect_notification_failed_text, maximum),
            )
            // The outer sequence resumes after its cooldown; explicit Disconnect cancels it.
        }
    }

    private suspend fun awaitCoreStopped(generation: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + RECONNECT_CORE_STOP_TIMEOUT_MS
        while (isEngineRunning() && SystemClock.elapsedRealtime() < deadline) {
            if (reconnectGeneration.get() != generation) return false
            delay(RECONNECT_STATE_POLL_MS)
        }
        if (!isEngineRunning()) {
            delay(RECONNECT_CORE_STOP_SETTLE_MS)
        }
        return !isEngineRunning() && reconnectGeneration.get() == generation
    }

    private suspend fun awaitHealthyRoute(generation: Long): ConnectionProbeResult? {
        currentCoroutineContext().ensureActive()
        val startDeadline = SystemClock.elapsedRealtime() + RECONNECT_START_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < startDeadline) {
            if (reconnectGeneration.get() != generation) return null
            if (activeConnection.get() && isEngineRunning()) break
            delay(RECONNECT_STATE_POLL_MS)
        }
        if (!activeConnection.get() || !isEngineRunning()) return null

        var stateStartedAt = SystemClock.elapsedRealtime()
        var captchaWasPending = VkTurnManager.isCaptchaPending()
        val readyDeadline = SystemClock.elapsedRealtime() + ManagedDnsTunnelManager.READY_TIMEOUT_MILLIS
        while (generation == reconnectGeneration.get() && SettingsManager.isConnectionDesired() &&
            activeConnection.get() && isEngineRunning()) {
            if (VkTurnManager.isTurnProfile(currentConfig)) {
                while (!VkTurnManager.isReadyForTraffic() || VkTurnManager.isCaptchaPending()) {
                    if (DnsTunnelManager.active && !DnsTunnelManager.running) return null
                    val service = getService() ?: return null
                    if (waitingForTurnCaptcha(service)) {
                        captchaWasPending = true
                        if (reconnectGeneration.get() != generation || !SettingsManager.isConnectionDesired()) return null
                        delay(RECONNECT_STATE_POLL_MS)
                        continue
                    }
                    val now = SystemClock.elapsedRealtime()
                    val captchaPending = VkTurnManager.isCaptchaPending()
                    if (captchaPending != captchaWasPending) {
                        captchaWasPending = captchaPending
                        stateStartedAt = now
                    }
                    val timedOut = ConnectionDropPolicy.hasTimedOut(
                        stateStartedAt,
                        now,
                        ConnectionDropPolicy.turnReadyTimeoutMillis(
                            captchaPending = false,
                            autoCaptcha = VkTurnManager.isAutoCaptchaEnabled(),
                            recoveryTransport = DnsTunnelManager.active,
                        ),
                    )
                    if (
                        reconnectGeneration.get() != generation ||
                        !activeConnection.get() ||
                        !isEngineRunning() ||
                        timedOut
                    ) {
                        return null
                    }
                    delay(RECONNECT_STATE_POLL_MS)
                }
                transitionRecovery(VpnRecoveryPhase.TURN_READY, generation = generation)
            } else if (ManagedDnsTunnelManager.isManagedDnsTunnelProfile(currentConfig)) {
                while (!ManagedDnsTunnelManager.isReadyForTraffic()) {
                    if (
                        reconnectGeneration.get() != generation ||
                        !activeConnection.get() ||
                        !isEngineRunning() ||
                        SystemClock.elapsedRealtime() >= readyDeadline
                    ) {
                        return null
                    }
                    delay(RECONNECT_STATE_POLL_MS)
                }
            } else {
                delay(RECONNECT_ROUTE_SETTLE_DELAY_MS)
            }

            transitionRecovery(VpnRecoveryPhase.VERIFYING_CONNECTION, generation = generation)
            val service = getService() ?: return null
            com.v2ray.ang.simple.DaemonConnectionState.recordHealth(started=true)
            val result=measureCurrentRoute(service, HEALTH_PROBE_TIMEOUT_MS)
            currentCoroutineContext().ensureActive()
            if (generation != reconnectGeneration.get()) return null
            com.v2ray.ang.simple.DaemonConnectionState.recordHealth(started=false,success=result.success,detail=result.message)
            // A challenge can arrive while the final DNS/HTTPS probe is in flight.
            // Keep this attempt alive; null would make reconnect callers tear it down.
            if (waitingForTurnCaptcha(service)) {
                captchaWasPending = true
                continue
            }
            return result.takeIf { it.success }
        }
        return null
    }

    /** One bounded verification window while the native pool repairs its lanes.
     * Used for migration even with periodic auto-recovery disabled. */
    private suspend fun awaitTurnRouteWindow(service: Service, wanted: () -> Boolean): ConnectionProbeResult? {
        var deadline = SystemClock.elapsedRealtime() + 30_000L
        while (wanted()) {
            currentCoroutineContext().ensureActive()
            if (waitingForTurnCaptcha(service)) {
                if (!SettingsManager.isConnectionDesired()) return null
                // The challenge keeps its own absolute deadline. Give the route
                // a fresh, finite verification window once the token is accepted.
                deadline = SystemClock.elapsedRealtime() + 30_000L
                delay(RECONNECT_STATE_POLL_MS)
                continue
            }
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) break
            if (hasUnderlyingInternet(service) && remaining > 0) {
                com.v2ray.ang.simple.DaemonConnectionState.recordHealth(started = true)
                val probe = measureCurrentRoute(service, minOf(5_000L, remaining))
                currentCoroutineContext().ensureActive()
                if (!wanted()) return null
                com.v2ray.ang.simple.DaemonConnectionState.recordHealth(started = false,
                    success = probe.success, detail = probe.message)
                if (probe.success && !VkTurnManager.isCaptchaPending()) return probe
            }
            delay(500L)
        }
        return null
    }

    private suspend fun <T> withRecoveryWakeLock(
        context: Context,
        timeoutMillis: Long = RECOVERY_WAKE_LOCK_TIMEOUT_MS,
        block: suspend () -> T,
    ): T {
        val powerManager = context.getSystemService(PowerManager::class.java)
        val wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "${AppConfig.ANG_PACKAGE}:vpn-recovery",
        )?.apply {
            setReferenceCounted(false)
            acquire(timeoutMillis)
        }
        return try {
            block()
        } finally {
            if (wakeLock?.isHeld == true) {
                runCatching { wakeLock.release() }
                    .onFailure { LogUtil.w(AppConfig.TAG, "Failed to release recovery wake lock", it) }
            }
        }
    }

    private fun hasUnderlyingInternet(context: Context): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return true
        return connectivity.allNetworks.any { network ->
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return@any false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
    }

    private const val PROXY_ROUTE_PROBE_URL = "https://1.1.1.1/cdn-cgi/trace"
    private const val TURN_ROUTE_PROBE_TIMEOUT_MS = 60_000L
    private const val HEALTH_PROBE_TIMEOUT_MS = 15_000L
    private const val RECONNECT_NETWORK_POLL_MS = 5_000L
    private const val NETWORK_CHANGE_SETTLE_DELAY_MS = 2_000L
    private const val NETWORK_LOSS_SETTLE_DELAY_MS = 1_500L
    private const val RECONNECT_ROUTE_SETTLE_DELAY_MS = 1_000L
    private const val RECONNECT_START_TIMEOUT_MS = 20_000L
    private const val RECONNECT_CORE_STOP_TIMEOUT_MS = 10_000L
    private const val RECONNECT_CORE_STOP_SETTLE_MS = 250L
    private const val RECOVERY_WAKE_LOCK_TIMEOUT_MS = 2 * 60_000L
    private const val RECONNECT_STATE_POLL_MS = 500L

    /**
     * Gets the current service instance.
     * @return The current service instance, or null if not available.
     */
    private fun getService(): Service? {
        return serviceControl?.get()?.getService()
    }

    /**
     * Core callback handler implementation for handling V2Ray core events.
     * Handles startup, shutdown, socket protection, and status emission.
     */
    private class CoreCallback : CoreCallbackHandler {
        /**
         * Called when V2Ray core starts up.
         * @return 0 for success, any other value for failure.
         */
        override fun startup(): Long {
            return 0
        }

        /**
         * Called when V2Ray core shuts down.
         * @return 0 for success, any other value for failure.
         */
        override fun shutdown(): Long {
            val serviceControl = serviceControl?.get() ?: return -1
            return try {
                val unexpected = ConnectionDropPolicy.isUnexpected(
                    connectionWasActive = activeConnection.getAndSet(false),
                    shutdownExpected = expectedShutdown.get(),
                )
                if (unexpected) {
                    handleConnectionDrop(serviceControl, null)
                } else if (!preserveServiceForRecovery.get()) {
                    serviceControl.stopService()
                }
                0
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to stop service", e)
                -1
            }
        }

        /**
         * Called when V2Ray core emits status information.
         * @param l Status code.
         * @param s Status message.
         * @return Always returns 0.
         */
        override fun onEmitStatus(l: Long, s: String?): Long {
            return 0
        }
    }

    /**
     * Process finder implementation for Xray core.
     * Uses ConnectivityManager to find the owning UID of a connection based on network parameters.
     */
    private class XrayProcessFinder(context: Context) : ProcessFinder {
        private val cm: ConnectivityManager? = context.getSystemService(ConnectivityManager::class.java)

        override fun findProcessByConnection(network: String, srcIP: String, srcPort: Long, destIP: String, destPort: Long): Long {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return -1L
            if (cm == null) return -1L
            val proto = when (network) {
                "tcp" -> OsConstants.IPPROTO_TCP
                "udp" -> OsConstants.IPPROTO_UDP
                else -> return -1L
            }

            if (destIP.isBlank() || destPort == 0L) {
                LogUtil.d(AppConfig.TAG, "ProcessFinder: Find $network connection from $srcIP:$srcPort to :$destPort, (no dest)")
                return -1L
            }

            return try {
                val uid = cm.getConnectionOwnerUid(
                    proto,
                    InetSocketAddress(srcIP, srcPort.toInt()),
                    InetSocketAddress(destIP, destPort.toInt())
                ).toLong()
                LogUtil.d(AppConfig.TAG, "ProcessFinder: Find $network connection from $srcIP:$srcPort to $destIP:$destPort, uid=$uid")
                //LogUtil.d(AppConfig.TAG, "ProcessFinder: Find $network connection from $srcIP:$srcPort to $destIP:$destPort, uid=$uid,${PackageUidResolver.uidToPackageName(uid.toString())}")

                uid
            } catch (_: Exception) {
                -1L
            }
        }
    }

    /**
     * Broadcast receiver for handling messages sent to the service.
     * Handles registration, service control, and screen events.
     */
    private val fullTunnelSwitching = AtomicBoolean(false)
    internal fun isSwitchingTemporaryFullTunnel() = fullTunnelSwitching.get()

    private fun switchTemporaryFullTunnel(command: String) {
        val control = serviceControl?.get() ?: return
        val context = control.getService().applicationContext
        val id = MmkvManager.decodeSettingsString(SimpleRuntime.ACTIVE_ID) ?: return
        val requestedGeneration = reconnectGeneration.get()
        val expiryToken = if (command.startsWith("expire:")) command.substringAfter(':').toLongOrNull() else null
        if (command != "on" && command != "off" && expiryToken == null) return
        if (!fullTunnelSwitching.compareAndSet(false, true)) return

        fun stillWanted(generation: Long) =
            SettingsManager.isConnectionDesired() && reconnectGeneration.get() == generation &&
                serviceControl?.get() === control && MmkvManager.decodeSettingsString(SimpleRuntime.ACTIVE_ID) == id

        backgroundScope.launch {
            var switchGeneration: Long? = null
            var previousLease: TemporaryFullTunnel.Lease? = null
            try {
                val store = SimpleStore(context)
                val profile = store.configurations.firstOrNull { it.id == id } ?: return@launch
                if (!TemporaryFullTunnelPolicy.supported(profile.mode, store.amneziaSplitEnabled)) return@launch
                val enabled = command == "on"
                val generation = withContext(Dispatchers.Main) {
                    if (!stillWanted(requestedGeneration)) return@withContext null
                    if (expiryToken != null && !TemporaryFullTunnel.shouldExpire(expiryToken)) return@withContext null
                    if (expiryToken == null && enabled == TemporaryFullTunnel.active(id)) return@withContext null
                    if (enabled && (!activeConnection.get() || handlingConnectionDrop.get())) return@withContext null

                    cancelAutomaticRecovery(context, VpnRecoveryPhase.SWITCHING_PROFILE)
                    val nextGeneration = reconnectGeneration.get()
                    switchGeneration = nextGeneration
                    previousLease = TemporaryFullTunnel.currentLease(id)
                    // Destroying the service would invalidate the restart generation.
                    check(control.prepareForRecovery()) { "Could not prepare VPN for routing change" }
                    nextGeneration
                } ?: return@launch
                val stopped = awaitCoreStopped(generation)
                withContext(Dispatchers.Main) {
                    if (!stillWanted(generation)) return@withContext
                    check(stopped) { "Previous VPN core has not stopped" }
                    TemporaryFullTunnel.prepare(id, enabled, store.fullTunnelMinutes)
                    check(control.startRecoveryAttempt()) { "Could not restart VPN with updated routing" }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Temporary full-tunnel switch failed", e)
                val generation = switchGeneration
                val prepared = generation != null && withContext(Dispatchers.Main) {
                    stillWanted(generation) && runCatching { control.prepareForRecovery() }.getOrDefault(false)
                }
                val coreStopped = prepared && awaitCoreStopped(checkNotNull(generation))
                withContext(Dispatchers.Main) {
                    if (!stillWanted(generation ?: requestedGeneration)) return@withContext
                    if (generation == null) {
                        MessageUtil.sendMsg2UI(context, AppConfig.MSG_STATE_START_FAILURE, "Не удалось применить режим.")
                        return@withContext
                    }
                    if (coreStopped) {
                        TemporaryFullTunnel.restoreForRestart(previousLease)
                        if (runCatching { control.startRecoveryAttempt() }.getOrDefault(false)) {
                            MessageUtil.sendMsg2UI(context, AppConfig.MSG_STATE_START_FAILURE,
                                "Не удалось применить режим. Восстановлена предыдущая маршрутизация.")
                            return@withContext
                        }
                    }
                    TemporaryFullTunnel.restoreForRestart(null)
                    SettingsManager.setConnectionDesired(false)
                    control.stopService()
                    MessageUtil.sendMsg2UI(context, AppConfig.MSG_STATE_START_FAILURE,
                        "Не удалось применить режим. Подключите VPN повторно.")
                }
            } finally {
                fullTunnelSwitching.set(false)
            }
        }
    }

    private class ReceiveMessageHandler : BroadcastReceiver() {
        /**
         * Handles received broadcast messages.
         * Processes service control messages and screen state changes.
         * @param ctx The context in which the receiver is running.
         * @param intent The intent being received.
         */
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val serviceControl = serviceControl?.get() ?: return
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_REGISTER_CLIENT -> {
                    val recovery = mutableRecoveryState.value
                    if (
                        recovery.phase != VpnRecoveryPhase.DISCONNECTED &&
                        recovery.phase != VpnRecoveryPhase.CONNECTED
                    ) {
                        MessageUtil.sendMsg2UI(
                            serviceControl.getService(),
                            AppConfig.MSG_STATE_RECOVERY,
                            recovery.toUiPayload(),
                        )
                        if (recovery.phase == VpnRecoveryPhase.TURN_CAPTCHA || recovery.phase == VpnRecoveryPhase.TURN_CAPTCHA_MANUAL) {
                            VkTurnManager.reportCurrentStatus(serviceControl.getService())
                        }
                    } else if (isEngineRunning()) {
                        val isTurn = VkTurnManager.isTurnProfile(currentConfig)
                        MessageUtil.sendMsg2UI(
                            serviceControl.getService(),
                            AppConfig.MSG_STATE_RUNNING,
                            if (isTurn) AppConfig.SERVICE_STATE_VK_TURN else "",
                        )
                        if (isTurn) {
                            VkTurnManager.reportCurrentStatus(serviceControl.getService())
                        }
                    } else {
                        MessageUtil.sendMsg2UI(serviceControl.getService(), AppConfig.MSG_STATE_NOT_RUNNING, "")
                    }
                }

                AppConfig.MSG_UNREGISTER_CLIENT -> {
                    // nothing to do
                }

                AppConfig.MSG_STATE_START -> {
                    // nothing to do
                }

                AppConfig.MSG_STATE_STOP -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Stop service")
                    SettingsManager.setConnectionDesired(false)
                    cancelAutomaticRecovery(serviceControl.getService())
                    serviceControl.stopService()
                }

                AppConfig.MSG_STATE_CONNECTION_DROPPED -> {
                    handleConnectionDrop(
                        serviceControl,
                        intent.getStringExtra("content"),
                    )
                }

                AppConfig.MSG_STATE_RESTART -> {
                    cancelAutomaticRecovery(serviceControl.getService())
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Restart service")
                    intent.getStringExtra("content")
                        ?.takeIf(String::isNotBlank)
                        ?.takeIf { MmkvManager.decodeServerConfig(it) != null }
                        ?.let(MmkvManager::setSelectServer)
                    SettingsManager.setConnectionDesired(true)
                    SettingsManager.clearAutoReconnectAttempt()
                    cancelAutomaticRecovery(serviceControl.getService())
                    transitionRecovery(
                        VpnRecoveryPhase.SWITCHING_PROFILE,
                        attempt = 0,
                        maximum = SettingsManager.getAutoReconnectMaxRetries(),
                    )
                    val appContext = serviceControl.getService().applicationContext
                    serviceControl.stopService()
                    Thread.sleep(500L)
                    try {
                        startContextService(appContext)
                    } catch (e: Exception) {
                        SettingsManager.setConnectionDesired(false)
                        transitionRecovery(VpnRecoveryPhase.DISCONNECTED)
                        MessageUtil.sendMsg2UI(
                            appContext,
                            AppConfig.MSG_STATE_START_FAILURE,
                            e.message ?: e.javaClass.simpleName,
                        )
                    }
                }

                TemporaryFullTunnel.MESSAGE -> {
                    switchTemporaryFullTunnel(intent.getStringExtra("content").orEmpty())
                }
                AppConfig.MSG_MEASURE_DELAY -> {
                    measureV2rayDelay()
                }
                com.v2ray.ang.simple.SimpleRuntime.MSG_REAL_DELAY -> {
                    measureSimpleRealDelay(intent.getStringExtra("content").orEmpty())
                }

                AppConfig.MSG_ROOT_LAN_SHARING_START -> {
                    if (!serviceControl.startLanSharing()) {
                        MessageUtil.sendMsg2UI(
                            serviceControl.getService(),
                            AppConfig.MSG_ROOT_LAN_SHARING_FAILURE,
                            "",
                        )
                    }
                }

                AppConfig.MSG_ROOT_LAN_SHARING_STOP -> {
                    serviceControl.stopLanSharing()
                }

                AppConfig.MSG_NOTIFICATION_DISMISSED -> {
                    if (handlingConnectionDrop.get()) {
                        NotificationManager.showRecoveryForeground(
                            serviceControl.getService(),
                            serviceControl.getService().getString(
                                R.string.reconnect_notification_waiting_text,
                            ),
                        )
                    } else if (isEngineRunning()) {
                        NotificationManager.showNotification(currentConfig)
                    }
                }
            }

            when (intent?.action) {
                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                    if (!serviceControl.getService().getSystemService(PowerManager::class.java).isDeviceIdleMode) {
                        refreshAmneziaAfterResume("idle-exit:${lastPhysicalNetworkHandle ?: 0}")
                    }
                }
                Intent.ACTION_SCREEN_OFF -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Screen off")
                    NotificationManager.stopSpeedNotification()
                }

                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    refreshAmneziaAfterResume("wake:${lastPhysicalNetworkHandle ?: 0}")
                    onUnderlyingNetworkAvailable()
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Screen on")
                    if (currentConfig?.configType != EConfigType.AMNEZIAWG) {
                        NotificationManager.startSpeedNotification()
                    }
                }
            }
        }
    }
}
