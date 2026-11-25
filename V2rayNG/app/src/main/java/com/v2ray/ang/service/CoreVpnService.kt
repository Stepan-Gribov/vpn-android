package com.v2ray.ang.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.StrictMode
import androidx.annotation.RequiresApi
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.LOOPBACK
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.contracts.Tun2SocksControl
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.fmt.AmneziaWgFmt
import com.v2ray.ang.core.ManagedDnsTunnelManager
import com.v2ray.ang.core.VkTurnManager
import com.v2ray.ang.core.DnsTunnelManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.root.RootLanSharing
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.MyContextWrapper
import com.v2ray.ang.util.Utils
import org.amnezia.awg.GoBackend
import org.amnezia.awg.config.Config
import org.amnezia.awg.util.SharedLibraryLoader
import java.lang.ref.SoftReference

@SuppressLint("VpnServicePolicy")
open class CoreVpnService : VpnService(), ServiceControl {
    private lateinit var mInterface: ParcelFileDescriptor
    private var isRunning = false
    private var tun2SocksService: Tun2SocksControl? = null
    private var effectiveVpnMtu = AppConfig.VPN_MTU
    private var isVkTurnProfile = false
    private var isManagedDnsTunnelProfile = false
    private var isAmneziaWgProfile = false
    private var amneziaWgConfig: Config? = null
    private var amneziaWgHandle = -1
    private var networkCallbackRegistered = false
    private var currentUnderlyingNetwork: Network? = null
    private var currentUnderlyingAddresses: String? = null
    private var currentUnderlyingValidated = false
    private var currentUnderlyingBlocked: Boolean? = null
    private val amneziaNetworkSelection = com.v2ray.ang.core.AmneziaNetworkSelection<Network>()
    private val networkCallbackHandler = Handler(Looper.getMainLooper())
    private var shutdownPrepared = false

    /**destroy
     * Unfortunately registerDefaultNetworkCallback is going to return our VPN interface: https://android.googlesource.com/platform/frameworks/base/+/dda156ab0c5d66ad82bdcf76cda07cbc0a9c8a2e
     *
     * This makes doing a requestNetwork with REQUEST necessary so that we don't get ALL possible networks that
     * satisfies default network capabilities but only THE default network. Unfortunately we need to have
     * android.permission.CHANGE_NETWORK_STATE to be able to call requestNetwork.
     *
     * Source: https://android.googlesource.com/platform/frameworks/base/+/2df4c7d/services/core/java/com/android/server/ConnectivityService.java#887
     */
    @delegate:RequiresApi(Build.VERSION_CODES.P)
    private val defaultNetworkRequest by lazy {
        NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()
    }

    private val connectivity by lazy { getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager }

    @delegate:RequiresApi(Build.VERSION_CODES.P)
    private val defaultNetworkCallback by lazy {
        object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                if (currentUnderlyingNetwork != network) return
                val addresses = linkProperties.linkAddresses.map { it.toString() }.sorted().joinToString(",")
                val previous = currentUnderlyingAddresses
                currentUnderlyingAddresses = addresses
                if (previous != null && previous != addresses) {
                    CoreServiceManager.onUnderlyingAddressChanged(network.networkHandle, addresses)
                }
                DnsTunnelManager.underlyingNetwork(network)
                CoreServiceManager.onUnderlyingNetworkAvailable()
            }
            override fun onAvailable(network: Network) {
                amneziaNetworkSelection.available(network, CoreServiceManager.isSimpleAmnezia())
                    ?.let(::selectUnderlyingNetwork)
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    amneziaNetworkSelection.validated(network)?.let(::selectUnderlyingNetwork)
                }
                if (currentUnderlyingNetwork != network) return
                com.v2ray.ang.simple.SimpleRuntime.onPhysicalNetworkChanged(
                    networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                )
                DnsTunnelManager.underlyingNetwork(network)
                val validated=networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                val becameUsable=validated && !currentUnderlyingValidated
                currentUnderlyingValidated=validated
                if(becameUsable) CoreServiceManager.onUnderlyingNetworkUsable(network.networkHandle)
                // it's a good idea to refresh capabilities
                setUnderlyingNetworks(arrayOf(network))
                CoreServiceManager.onUnderlyingNetworkAvailable()
            }

            override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                if(currentUnderlyingNetwork!=network) return
                val unblocked=currentUnderlyingBlocked==true && !blocked
                currentUnderlyingBlocked=blocked
                CoreServiceManager.onUnderlyingNetworkBlocked(network.networkHandle, blocked)
                if(unblocked) CoreServiceManager.onUnderlyingNetworkUsable(network.networkHandle)
            }

            override fun onLost(network: Network) {
                val replacement = amneziaNetworkSelection.lost(network)
                if (currentUnderlyingNetwork != network) return
                currentUnderlyingNetwork = null
                currentUnderlyingAddresses = null
                if (replacement != null) {
                    selectUnderlyingNetwork(replacement)
                    return
                }
                com.v2ray.ang.simple.PhysicalDnsBridge.underlyingNetwork(null)
                DnsTunnelManager.lostNetwork(network)
                setUnderlyingNetworks(null)
                CoreServiceManager.onUnderlyingNetworkLost()
            }
        }
    }

    private fun selectUnderlyingNetwork(network: Network) {
        val changed = currentUnderlyingNetwork != network
        if (changed) {
            currentUnderlyingAddresses = null
            currentUnderlyingValidated = false
            currentUnderlyingBlocked = null
        }
        currentUnderlyingNetwork = network
        com.v2ray.ang.simple.PhysicalDnsBridge.underlyingNetwork(network)
        DnsTunnelManager.underlyingNetwork(network)
        setUnderlyingNetworks(arrayOf(network))
        if (changed) CoreServiceManager.onUnderlyingNetworkChanged(network.networkHandle)
        CoreServiceManager.onUnderlyingNetworkAvailable()
    }

    /** Called after real traffic on the selected route fails, never merely on
     * onAvailable. Use the callback thread so candidate/loss events stay ordered. */
    internal fun retryAmneziaNetworkCandidate() {
        networkCallbackHandler.post {
            if (networkCallbackRegistered && isRunning && SettingsManager.isConnectionDesired() && CoreServiceManager.isSimpleAmnezia()) {
                amneziaNetworkSelection.failed()?.let(::selectUnderlyingNetwork)
            }
        }
    }

    private val excludedAppPackageReceiver=com.v2ray.ang.simple.ExcludedAppPackageReceiver()

    override fun onCreate() {
        super.onCreate()
        androidx.core.content.ContextCompat.registerReceiver(this,excludedAppPackageReceiver,
            android.content.IntentFilter().apply {
                addAction(android.content.Intent.ACTION_PACKAGE_ADDED);addAction(android.content.Intent.ACTION_PACKAGE_REMOVED);addDataScheme("package")
            },androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        com.v2ray.ang.simple.DaemonConnectionState.serviceCreated()
        LogUtil.i(AppConfig.TAG, "StartCore-VPN: Service created")
        val policy = StrictMode.ThreadPolicy.Builder().permitAll().build()
        StrictMode.setThreadPolicy(policy)
        CoreServiceManager.serviceControl = SoftReference(this)
    }

    override fun onRevoke() {
        LogUtil.w(AppConfig.TAG, "StartCore-VPN: Permission revoked")
        SettingsManager.setConnectionDesired(false)
        stopAllService()
    }

//    override fun onLowMemory() {
//        stopV2Ray()
//        super.onLowMemory()
//    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(excludedAppPackageReceiver) }
        unregisterDefaultNetworkCallback()
        tun2SocksService?.stopTun2Socks()
        tun2SocksService = null
        VkTurnManager.stop()
        ManagedDnsTunnelManager.stop()
        // A process/service teardown can bypass stopAllService(). Remove any privileged
        // forwarding rules before the local SOCKS listener disappears, otherwise clients
        // would remain routed into a dead tunnel until the next successful start.
        RootLanSharing.stopClientSharing(this)
        if (!shutdownPrepared) {
            CoreServiceManager.stopCoreLoop()
        }
        com.v2ray.ang.simple.DaemonConnectionState.serviceDestroyed()
        super.onDestroy()
        LogUtil.i(AppConfig.TAG, "StartCore-VPN: Service destroyed")

        // Ensure VPN interface is properly closed when the service is destroyed without
        // going through stopAllService() (e.g. when killed unexpectedly). isRunning is
        // set to false at the start of stopAllService(), so this guard prevents a double-close.
        closeVpnInterface()

        NotificationManager.cancelNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LogUtil.i(AppConfig.TAG, "StartCore-VPN: Service command received")
        if (CoreServiceManager.isSwitchingTemporaryFullTunnel() || (isRunning && CoreServiceManager.isRunning())) {
            LogUtil.i(AppConfig.TAG, "StartCore-VPN: Ignoring duplicate start for live tunnel")
            return if(CoreServiceManager.automaticRecoveryAllowedForSelectedProfile()) START_STICKY else START_NOT_STICKY
        }
        if (intent != null && !SettingsManager.isConnectionDesired()) {
            // A start queued before the user's Disconnect must not resurrect VPN.
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent == null && (!SettingsManager.isConnectionDesired() || !CoreServiceManager.automaticRecoveryAllowedForSelectedProfile())) {
            SettingsManager.setConnectionDesired(false)
            LogUtil.i(AppConfig.TAG, "StartCore-VPN: Ignoring sticky restart after an intentional stop")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        NotificationManager.showNotification(null)
        if (intent == null && SettingsManager.getAutoReconnectAttempt() > 0) {
            NotificationManager.showRecoveryForeground(
                this,
                getString(R.string.reconnect_notification_checking_text),
            )
        }
        if (setupVpnService(stopOnFailure = true)) {
            startService()
        }
        return if(CoreServiceManager.automaticRecoveryAllowedForSelectedProfile()) START_STICKY else START_NOT_STICKY
    }

    override fun getService(): Service {
        return this
    }

    override fun startService() {
        if (!::mInterface.isInitialized) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Interface not initialized")
            return
        }
        if (!CoreServiceManager.startCoreLoop(mInterface)) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to start core loop")
            stopAllService()
            return
        }

        // Start LAN sharing if enabled in settings
        if (!isAmneziaWgProfile) RootLanSharing.startClientSharing(this)
    }

    override fun stopService() {
        stopAllService(true)
    }

    override fun prepareForRecovery(): Boolean {
        LogUtil.i(AppConfig.TAG, "StartCore-VPN: Preparing full in-place recovery")
        isRunning = false
        unregisterDefaultNetworkCallback()
        tun2SocksService?.stopTun2Socks()
        tun2SocksService = null
        NotificationManager.stopSpeedNotification()
        RootLanSharing.stopClientSharing(this)
        CoreServiceManager.stopCoreLoop(keepForegroundForRecovery = true)
        closeVpnInterface()
        return true
    }

    override fun startRecoveryAttempt(): Boolean {
        LogUtil.i(AppConfig.TAG, "StartCore-VPN: Starting full in-place recovery attempt")
        if (!setupVpnService(stopOnFailure = false)) return false
        if (!CoreServiceManager.startCoreLoop(mInterface)) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Recovery core failed to start")
            return false
        }
        if (!isAmneziaWgProfile) RootLanSharing.startClientSharing(this)
        return true
    }

    override fun vpnProtect(socket: Int): Boolean {
        return protect(socket)
    }

    override fun startLanSharing(): Boolean = RootLanSharing.startClientSharing(this)

    override fun stopLanSharing() = RootLanSharing.stopClientSharing(this)

    override fun startAmneziaWgTunnel(
        vpnInterface: ParcelFileDescriptor,
        config: String,
    ): Boolean {
        check(amneziaWgHandle < 0) { "AmneziaWG tunnel is already running" }
        check(config.isNotBlank()) { "AmneziaWG configuration is empty" }
        val parsed = checkNotNull(amneziaWgConfig) { "AmneziaWG configuration was not prepared" }

        SharedLibraryLoader.loadSharedLibrary(applicationContext, "wg-go")
        val handle = GoBackend.awgTurnOn("kindlyvpn", vpnInterface.detachFd(), parsed.toAwgUserspaceString())
        check(handle >= 0) { "AmneziaWG engine could not start (code $handle)" }

        try {
            listOf(GoBackend.awgGetSocketV4(handle), GoBackend.awgGetSocketV6(handle))
                .filter { it >= 0 }
                .forEach { socket ->
                    check(protect(socket)) { "Could not protect AmneziaWG transport socket" }
                }
            amneziaWgHandle = handle
            return true
        } catch (e: Exception) {
            GoBackend.awgTurnOff(handle)
            throw e
        }
    }

    override fun stopAmneziaWgTunnel() {
        val handle = amneziaWgHandle
        amneziaWgHandle = -1
        if (handle >= 0) {
            runCatching { GoBackend.awgTurnOff(handle) }
                .onFailure { LogUtil.e(AppConfig.TAG, "Failed to stop AmneziaWG engine", it) }
        }
    }

    override fun attachBaseContext(newBase: Context?) {
        val context = newBase?.let {
            MyContextWrapper.wrap(newBase, SettingsManager.getLocale())
        }
        super.attachBaseContext(context)
    }

    /**
     * Sets up the VPN service.
     * Prepares the VPN and configures it if preparation is successful.
     */
    private fun setupVpnService(stopOnFailure: Boolean): Boolean {
        val prepare = prepare(this)
        if (prepare != null) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Permission not granted")
            if (stopOnFailure) stopSelf()
            return false
        }

        try { com.v2ray.ang.simple.SimpleRuntime.refreshPreparedConfiguration(this) }
        catch(_:Exception) {
            LogUtil.e(AppConfig.TAG,"StartCore-VPN: Saved configuration is unavailable")
            if(stopOnFailure) stopSelf()
            return false
        }
        if (configureVpnService() != true) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Configuration failed")
            if (stopOnFailure) stopSelf()
            return false
        }

        runTun2socks()
        return true
    }

    /**
     * Configures the VPN service.
     * @return True if the VPN service was configured successfully, false otherwise.
     */
    private fun configureVpnService(): Boolean {
        val builder = Builder()
        val selectedProfile = MmkvManager.getSelectServer()?.let(MmkvManager::decodeServerConfig)
        isVkTurnProfile = VkTurnManager.isTurnProfile(selectedProfile)
        isManagedDnsTunnelProfile = ManagedDnsTunnelManager.isManagedDnsTunnelProfile(selectedProfile)
        isAmneziaWgProfile = selectedProfile?.configType == com.v2ray.ang.enums.EConfigType.AMNEZIAWG
        amneziaWgConfig = if (isAmneziaWgProfile) {
            try {
                AmneziaWgFmt.parseConfig(selectedProfile?.amneziaWgConfig.orEmpty()).also { parsed ->
                    // Resolve while the underlying connection is still active. Once the
                    // TUN is established, DNS for this app may enter the empty VPN route.
                    parsed.getPeers().forEach { peer ->
                        val endpoint = peer.getEndpoint().orElse(null) ?: return@forEach
                        check(endpoint.getResolved().isPresent) {
                            "Could not resolve AmneziaWG endpoint ${endpoint.getHost()}"
                        }
                    }
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Invalid AmneziaWG profile", e)
                return false
            }
        } else {
            null
        }
        if (isManagedDnsTunnelProfile) {
            // Do this before the VPN interface is established so we read the actual
            // Wi-Fi/mobile resolver rather than the resolver of this VPN.
            ManagedDnsTunnelManager.captureAutomaticallyIfEnabled(this)
        }
        effectiveVpnMtu = if (isAmneziaWgProfile) {
            amneziaWgConfig?.getInterface()?.getMtu()?.orElse(1280) ?: 1280
        } else {
            com.v2ray.ang.simple.AmneziaRuntime.effectiveVpnMtu(
                VkTurnManager.effectiveVpnMtu(selectedProfile, SettingsManager.getVpnMtu()),
            )
        }

        // Configure network settings (addresses, routing and DNS)
        configureNetworkSettings(builder)

        // Configure app-specific settings (session name and per-app proxy)
        configurePerAppProxy(builder)

        // Close the old interface since the parameters have been changed
        try {
            if (::mInterface.isInitialized) {
                mInterface.close()
            }
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "Failed to close old interface", e)
        }

        // Configure platform-specific features
        configurePlatformFeatures(builder)
        if (isAmneziaWgProfile) builder.setBlocking(true)

        // Create a new interface using the builder and save the parameters
        try {
            mInterface = builder.establish()!!
            isRunning = true
            return true
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to establish VPN interface", e)
        }
        return false
    }

    /**
     * Configures the basic network settings for the VPN.
     * This includes IP addresses, routing rules, and DNS servers.
     *
     * @param builder The VPN Builder to configure
     */
    private fun configureNetworkSettings(builder: Builder) {
        if (isAmneziaWgProfile) {
            configureAmneziaWgNetworkSettings(builder)
            return
        }

        val vpnConfig = SettingsManager.getCurrentVpnInterfaceAddressConfig()
        val bypassLan = !isVkTurnProfile && SettingsManager.routingRulesetsBypassLan()

        // Configure IPv4 settings
        builder.setMtu(effectiveVpnMtu)
        builder.addAddress(vpnConfig.ipv4Client, 30)

        // Configure routing rules
        if (bypassLan) {
            AppConfig.ROUTED_IP_LIST.forEach {
                val addr = it.split('/')
                builder.addRoute(addr[0], addr[1].toInt())
            }
        } else {
            builder.addRoute("0.0.0.0", 0)
        }

        // Configure IPv6 if enabled
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_IPV6_ENABLED) == true) {
            builder.addAddress(vpnConfig.ipv6Client, 126)
            if (bypassLan) {
                builder.addRoute("2000::", 3) // Currently only 1/8 of total IPv6 is in use
                builder.addRoute("fc00::", 18) // Xray-core default FakeIPv6 Pool
            } else {
                builder.addRoute("::", 0)
            }
        }

        // Configure DNS servers
        //if (MmkvManager.decodeSettingsBool(AppConfig.PREF_LOCAL_DNS_ENABLED) == true) {
        //  builder.addDnsServer(PRIVATE_VLAN4_ROUTER)
        //} else {
        val vpnDnsServers = if (isManagedDnsTunnelProfile && MmkvManager.getSelectServer()==com.v2ray.ang.simple.SimpleRuntime.STAGED_GUID) {
            // A stable IPv4 interception address; carrier/DoH transport resolver
            // addresses must never become the phone's application DNS server.
            listOf("1.1.1.1")
        } else if (isManagedDnsTunnelProfile) {
            // Android requires an IP here. Xray intercepts these queries and uses the
            // selected HTTPS resolver when the tunnel runs over DoH.
            listOf(ManagedDnsTunnelManager.getResolverEndpoint()?.host ?: "1.1.1.1")
        } else {
            SettingsManager.getVpnDnsServers()
        }
        // Adding an IPv6 DNS server implicitly enables that family in Android.
        // Do not reopen a disabled family through the DNS configuration.
        val admittedDns=vpnDnsServers.filter { MmkvManager.decodeSettingsBool(AppConfig.PREF_IPV6_ENABLED) || ':' !in it }
            .ifEmpty { listOf("1.1.1.1") }
        admittedDns.forEach {
            if (Utils.isPureIpAddress(it)) {
                builder.addDnsServer(it)
            }
        }

        //builder.setSession(V2RayServiceManager.getRunningServerName())
    }

    private fun configureAmneziaWgNetworkSettings(builder: Builder) {
        val config = checkNotNull(amneziaWgConfig) { "AmneziaWG profile is missing" }
        val interfaceConfig = config.getInterface()
        builder.setSession("KindlyVPN · ${MmkvManager.getSelectServer()?.let(MmkvManager::decodeServerConfig)?.remarks.orEmpty()}")
        builder.setMtu(effectiveVpnMtu)

        interfaceConfig.getAddresses().forEach { address ->
            builder.addAddress(address.getAddress(), address.getMask())
        }
        var hasDefaultRoute = false
        config.getPeers().forEach { peer ->
            peer.getAllowedIps().forEach { allowedIp ->
                if (allowedIp.getMask() == 0) hasDefaultRoute = true
                builder.addRoute(allowedIp.getAddress(), allowedIp.getMask())
            }
        }
        if (!(hasDefaultRoute && config.getPeers().size == 1)) {
            builder.allowFamily(android.system.OsConstants.AF_INET)
            builder.allowFamily(android.system.OsConstants.AF_INET6)
        }
        interfaceConfig.getDnsServers().forEach { builder.addDnsServer(it.hostAddress) }
        interfaceConfig.getDnsSearchDomains().forEach(builder::addSearchDomain)
    }

    /**
     * Configures platform-specific VPN features for different Android versions.
     *
     * @param builder The VPN Builder to configure
     */
    private fun configurePlatformFeatures(builder: Builder) {
        // Android P (API 28) and above: Configure network callbacks
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                if (!networkCallbackRegistered) {
                    connectivity.requestNetwork(defaultNetworkRequest, defaultNetworkCallback, networkCallbackHandler)
                    networkCallbackRegistered = true
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to request network", e)
            }
        }

        // Android Q (API 29) and above: Configure metering and HTTP proxy
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
            if (!isAmneziaWgProfile && MmkvManager.decodeSettingsBool(AppConfig.PREF_APPEND_HTTP_PROXY)) {
                builder.setHttpProxy(ProxyInfo.buildDirectProxy(LOOPBACK, SettingsManager.getHttpPort()))
            }
        }
    }

    /**
     * Configures per-app proxy rules for the VPN builder.
     *
     * - If per-app proxy is not enabled, disallow the VPN service's own package.
     * - If no apps are selected, disallow the VPN service's own package.
     * - If bypass mode is enabled, disallow all selected apps (including self).
     * - If proxy mode is enabled, only allow the selected apps (excluding self).
     *
     * @param builder The VPN Builder to configure.
     */
    private fun configurePerAppProxy(builder: Builder) {
        val selfPackageName = BuildConfig.APPLICATION_ID

        if (MmkvManager.getSelectServer() == com.v2ray.ang.simple.SimpleRuntime.STAGED_GUID) {
            // Android excludes the entire UID before either HEV or address rules.
            val apps=MmkvManager.decodeSettingsStringSet(com.v2ray.ang.simple.AppVpnExclusions.STAGED_KEY).orEmpty()
            com.v2ray.ang.simple.AppVpnExclusionBuilder.apply(builder,this,apps.toList())
            return
        }

        if (isAmneziaWgProfile) {
            val interfaceConfig = amneziaWgConfig?.getInterface()
            val includedApplications = interfaceConfig?.getIncludedApplications().orEmpty()
            val excludedApplications = interfaceConfig?.getExcludedApplications().orEmpty()
            if (includedApplications.isNotEmpty()) {
                (includedApplications + selfPackageName).forEach {
                    addVpnApplication(builder, it, allowed = true)
                }
                return
            }
            if (excludedApplications.isNotEmpty()) {
                excludedApplications.filterNot { it == selfPackageName }.forEach {
                    addVpnApplication(builder, it, allowed = false)
                }
                return
            }

            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY) != true) return
            val apps = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET)
                .orEmpty()
            if (apps.isEmpty()) return
            val bypassApps = MmkvManager.decodeSettingsBool(AppConfig.PREF_BYPASS_APPS)
            if (bypassApps) {
                apps.filterNot { it == selfPackageName }.forEach {
                    addVpnApplication(builder, it, allowed = false)
                }
            } else {
                (apps + selfPackageName).forEach {
                    addVpnApplication(builder, it, allowed = true)
                }
            }
            return
        }

        // If per-app proxy is not enabled, disallow the VPN service's own package and return
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY) == false) {
            builder.addDisallowedApplication(selfPackageName)
            return
        }

        // If no apps are selected, disallow the VPN service's own package and return
        val apps = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET)
        if (apps.isNullOrEmpty()) {
            builder.addDisallowedApplication(selfPackageName)
            return
        }

        val bypassApps = MmkvManager.decodeSettingsBool(AppConfig.PREF_BYPASS_APPS)
        // Handle the VPN service's own package according to the mode
        if (bypassApps) apps.add(selfPackageName) else apps.remove(selfPackageName)

        apps.forEach {
            try {
                if (bypassApps) {
                    // In bypass mode, disallow the selected apps
                    builder.addDisallowedApplication(it)
                } else {
                    // In proxy mode, only allow the selected apps
                    builder.addAllowedApplication(it)
                }
            } catch (e: PackageManager.NameNotFoundException) {
                LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to configure app", e)
            }
        }
    }

    private fun addVpnApplication(builder: Builder, packageName: String, allowed: Boolean) {
        try {
            if (allowed) builder.addAllowedApplication(packageName)
            else builder.addDisallowedApplication(packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to configure app $packageName", e)
        }
    }

    /**
     * Runs the tun2socks process.
     * Starts the tun2socks process with the appropriate parameters.
     */
    private fun runTun2socks() {
        if (isAmneziaWgProfile) {
            tun2SocksService = null
            return
        }
        if (SettingsManager.isUsingHevTun() || isVkTurnProfile) {
            tun2SocksService = TProxyService(
                context = applicationContext,
                vpnInterface = mInterface,
                vpnMtu = effectiveVpnMtu
            )
        } else {
            tun2SocksService = null
        }

        tun2SocksService?.startTun2Socks()
    }

    private fun stopAllService(isForced: Boolean = true) {
//        val configName = defaultDPreference.getPrefString(PREF_CURR_CONFIG_GUID, "")
//        val emptyInfo = VpnNetworkInfo()
//        val info = loadVpnNetworkInfo(configName, emptyInfo)!! + (lastNetworkInfo ?: emptyInfo)
//        saveVpnNetworkInfo(configName, info)
        isRunning = false
        shutdownPrepared = true
        try {
            unregisterDefaultNetworkCallback()
            tun2SocksService?.stopTun2Socks()
            tun2SocksService = null
            try {
                RootLanSharing.stopClientSharing(this)
            } finally {
                CoreServiceManager.stopCoreLoop()
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Cleanup failed during stop", e)
        } finally {
            if (isForced) {
                // Keep stopSelf ahead of closing the interface so the old core can
                // release its ports before a new service starts.
                stopSelf()
                try {
                    Thread.sleep(100)
                } catch (e: InterruptedException) {
                    LogUtil.w(AppConfig.TAG, "StartCore-VPN: Sleep interrupted", e)
                } finally {
                    closeVpnInterface()
                }
            }
        }
    }

    private fun unregisterDefaultNetworkCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || !networkCallbackRegistered) return
        try {
            connectivity.unregisterNetworkCallback(defaultNetworkCallback)
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "StartCore-VPN: Failed to unregister callback", e)
        } finally {
            networkCallbackRegistered = false
            currentUnderlyingNetwork = null
            amneziaNetworkSelection.reset()
        }
    }

    private fun closeVpnInterface() {
        stopAmneziaWgTunnel()
        try {
            if (::mInterface.isInitialized) {
                mInterface.close()
                LogUtil.i(AppConfig.TAG, "StartCore-VPN: VPN interface closed")
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to close interface", e)
        }
    }
}
