package com.v2ray.ang.handler

import android.content.Context
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiSsid
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Shares the SOCKS/HTTP proxy with nearby devices over Wi-Fi.
 *
 * Android 16 QPR2+ uses a configured local-only hotspot with the exact requested SSID/password.
 * Earlier Android 10+ versions use a Wi-Fi Direct group, which requires a "DIRECT-…" SSID. Android 8-9
 * can only use a local-only hotspot with system-generated credentials.
 *
 * Neither mode forwards internet on its own: connected clients only reach the internet
 * through the proxy, so their traffic cannot bypass the tunnel. Everything runs in the UI
 * process (the VPN core is a separate process), so sharing lives and dies with the app.
 */
object HotspotShareManager {

    const val DEFAULT_NETWORK_NAME = "network"
    const val DEFAULT_PASSWORD = "12345678"

    enum class State { STOPPED, STARTING, RUNNING, FAILED }

    private enum class Mode { NONE, LOCAL_ONLY, WIFI_P2P }

    data class Info(val ssid: String, val password: String, val gatewayIp: String?)

    /** Standard group-owner address of an Android Wi-Fi Direct group. */
    private const val P2P_GATEWAY_FALLBACK = "192.168.49.1"

    @Volatile
    var state = State.STOPPED
        private set

    @Volatile
    var info: Info? = null
        private set

    /** True once the gateway lookup finished, found or not; the UI then stops saying "determining". */
    @Volatile
    var gatewayResolved = false
        private set

    /** Set by the sharing screen while visible; always invoked on the main thread. */
    var onStateChanged: (() -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var mode = Mode.NONE
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var p2pManager: WifiP2pManager? = null
    private var p2pChannel: WifiP2pManager.Channel? = null

    fun isActive() = state == State.STARTING || state == State.RUNNING

    /** True when the platform supports a custom network name and password. */
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.Q)
    fun supportsCustomCredentials() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** Public Soft AP credential setters require the Android 16 minor update. */
    fun supportsExactCredentials() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA &&
        Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1

    /**
     * Turns a user-entered name into a valid Wi-Fi Direct network name:
     * "DIRECT-" + up to 25 sanitized characters, at least two of them.
     */
    fun networkNameFor(rawName: String): String {
        val sanitized = rawName.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            .take(25)
            .ifBlank { "KV" }
            .let { if (it.length < 2) it + "KV" else it }
        return "DIRECT-$sanitized"
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun start(context: Context, customName: String?, customPassword: String?) {
        if (isActive()) return
        setState(State.STARTING)

        if (supportsCustomCredentials() && !customName.isNullOrBlank() && !customPassword.isNullOrBlank()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                startConfiguredLocalOnly(
                    context.applicationContext,
                    customName,
                    customPassword,
                )
            } else {
                startP2pGroup(
                    context.applicationContext,
                    networkNameFor(customName),
                    customPassword,
                )
            }
        } else {
            startLocalOnly(context.applicationContext)
        }
    }

    fun stop() {
        when (mode) {
            Mode.LOCAL_ONLY -> {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) reservation?.close()
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Failed to close hotspot reservation", e)
                }
            }

            Mode.WIFI_P2P -> {
                val manager = p2pManager
                val channel = p2pChannel
                if (manager != null && channel != null) {
                    try {
                        manager.removeGroup(channel, null)
                    } catch (e: Exception) {
                        LogUtil.e(AppConfig.TAG, "Failed to remove Wi-Fi Direct group", e)
                    } finally {
                        closeP2pChannel()
                    }
                }
            }

            Mode.NONE -> {}
        }

        reservation = null
        p2pManager = null
        p2pChannel = null
        mode = Mode.NONE
        info = null
        setState(State.STOPPED)
    }

    // region Wi-Fi Direct group (custom credentials)

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startP2pGroup(context: Context, networkName: String, passphrase: String) {
        val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        val channel = manager?.initialize(context, Looper.getMainLooper(), null)
        if (manager == null || channel == null) {
            LogUtil.w(AppConfig.TAG, "Wi-Fi Direct unavailable for custom hotspot credentials")
            fail()
            return
        }
        p2pManager = manager
        p2pChannel = channel

        val config = try {
            WifiP2pConfig.Builder()
                .setNetworkName(networkName)
                .setPassphrase(passphrase)
                // Let Android select 5 GHz when both devices support it while retaining
                // 2.4 GHz compatibility on older/client-limited hardware.
                .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_AUTO)
                .build()
        } catch (e: IllegalArgumentException) {
            LogUtil.e(AppConfig.TAG, "Invalid Wi-Fi Direct credentials", e)
            closeP2pChannel()
            fail()
            return
        }

        // A stale group from a previous run makes createGroup return BUSY; clear it first.
        val create = {
            try {
                manager.createGroup(channel, config, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        confirmP2pCredentials(
                            manager,
                            channel,
                            networkName,
                            passphrase,
                        )
                    }

                    override fun onFailure(reason: Int) {
                        LogUtil.w(AppConfig.TAG, "Wi-Fi Direct custom group failed (reason $reason)")
                        closeP2pChannel()
                        fail()
                    }
                })
            } catch (e: SecurityException) {
                LogUtil.e(AppConfig.TAG, "Wi-Fi Direct permission was denied", e)
                closeP2pChannel()
                fail()
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to create Wi-Fi Direct group", e)
                closeP2pChannel()
                fail()
            }
        }
        try {
            manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = create()
                override fun onFailure(reason: Int) = create()
            })
        } catch (e: Exception) {
            create()
        }
    }

    private fun confirmP2pCredentials(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        expectedName: String,
        expectedPassphrase: String,
        attemptsRemaining: Int = 6,
    ) {
        try {
            manager.requestGroupInfo(channel) { group ->
                if (group == null && attemptsRemaining > 1) {
                    mainHandler.postDelayed(
                        {
                            confirmP2pCredentials(
                                manager,
                                channel,
                                expectedName,
                                expectedPassphrase,
                                attemptsRemaining - 1,
                            )
                        },
                        300,
                    )
                    return@requestGroupInfo
                }

                val actualName = group?.networkName.orEmpty()
                val actualPassphrase = group?.passphrase.orEmpty()
                if (actualName != expectedName || actualPassphrase != expectedPassphrase) {
                    LogUtil.e(
                        AppConfig.TAG,
                        "Wi-Fi Direct ignored custom credentials; refusing random fallback",
                    )
                    try {
                        manager.removeGroup(channel, null)
                    } catch (_: Exception) {
                        // The group may already have disappeared.
                    }
                    closeP2pChannel()
                    fail()
                    return@requestGroupInfo
                }

                mode = Mode.WIFI_P2P
                info = Info(actualName, actualPassphrase, gatewayIp = null)
                setState(State.RUNNING)
                resolveGatewayIpAsync()
            }
        } catch (e: SecurityException) {
            LogUtil.e(AppConfig.TAG, "Wi-Fi Direct permission was denied", e)
            closeP2pChannel()
            fail()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to verify Wi-Fi Direct credentials", e)
            closeP2pChannel()
            fail()
        }
    }

    private fun closeP2pChannel() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) p2pChannel?.close()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to close Wi-Fi Direct channel", e)
        }
        p2pManager = null
        p2pChannel = null
    }

    // endregion

    // region Local-only hotspot

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun startConfiguredLocalOnly(
        context: Context,
        networkName: String,
        passphrase: String,
    ) {
        val startP2pFallback = {
            if (state == State.STARTING && mode == Mode.NONE) {
                LogUtil.w(
                    AppConfig.TAG,
                    "Configured hotspot unavailable; trying deterministic Wi-Fi Direct credentials",
                )
                startP2pGroup(context, networkNameFor(networkName), passphrase)
            }
        }
        if (Build.VERSION.SDK_INT_FULL < Build.VERSION_CODES_FULL.BAKLAVA_1) {
            startP2pFallback()
            return
        }
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifiManager == null) {
            startP2pFallback()
            return
        }

        val config = try {
            SoftApConfiguration.Builder()
                .setWifiSsid(WifiSsid.fromBytes(networkName.toByteArray(Charsets.UTF_8)))
                .setPassphrase(passphrase, SoftApConfiguration.SECURITY_TYPE_WPA2_PSK)
                .build()
        } catch (e: IllegalArgumentException) {
            LogUtil.e(AppConfig.TAG, "Invalid configured hotspot credentials", e)
            startP2pFallback()
            return
        }

        if (!wifiManager.validateSoftApConfiguration(config)) {
            LogUtil.e(AppConfig.TAG, "Android rejected the configured hotspot credentials")
            startP2pFallback()
            return
        }

        try {
            wifiManager.startLocalOnlyHotspotWithConfiguration(
                config,
                context.mainExecutor,
                localOnlyCallback(
                    expectedCredentials = networkName to passphrase,
                    onConfiguredFailure = startP2pFallback,
                ),
            )
        } catch (e: SecurityException) {
            LogUtil.e(AppConfig.TAG, "Configured hotspot permission was denied", e)
            fail()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to start configured local-only hotspot", e)
            startP2pFallback()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun startLocalOnly(context: Context) {
        val wifiManager =
            context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifiManager == null) {
            fail()
            return
        }

        try {
            wifiManager.startLocalOnlyHotspot(localOnlyCallback(), mainHandler)
        } catch (e: SecurityException) {
            LogUtil.e(AppConfig.TAG, "Local-only hotspot permission was denied", e)
            fail()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to start local-only hotspot", e)
            fail()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun localOnlyCallback(
        expectedCredentials: Pair<String, String>? = null,
        onConfiguredFailure: (() -> Unit)? = null,
    ) = object : WifiManager.LocalOnlyHotspotCallback() {
        private var switchingToFallback = false

        private fun useConfiguredFallback(res: WifiManager.LocalOnlyHotspotReservation? = null) {
            if (switchingToFallback) return
            switchingToFallback = true
            try {
                res?.close()
            } catch (_: Exception) {
                // The framework may already be stopping the reservation.
            }
            reservation = null
            info = null
            mode = Mode.NONE
            val fallback = onConfiguredFailure
            if (fallback == null) {
                fail()
            } else {
                // Let the local-only AP release the radio before creating the P2P group.
                mainHandler.postDelayed(
                    {
                        if (state == State.STARTING && mode == Mode.NONE) fallback()
                    },
                    700,
                )
            }
        }

        override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
            val actualCredentials = credentialsOf(res)
            if (expectedCredentials != null && actualCredentials != expectedCredentials) {
                val nameMatches = actualCredentials.first == expectedCredentials.first
                val passwordMatches = actualCredentials.second == expectedCredentials.second
                LogUtil.e(
                    AppConfig.TAG,
                    "Android ignored configured hotspot credentials " +
                        "(nameMatches=$nameMatches, passwordMatches=$passwordMatches, " +
                        "actualPasswordLength=${actualCredentials.second.length}); " +
                        "refusing random credentials",
                )
                useConfiguredFallback(res)
                return
            }

            mode = Mode.LOCAL_ONLY
            reservation = res
            info = Info(actualCredentials.first, actualCredentials.second, gatewayIp = null)
            setState(State.RUNNING)
            resolveGatewayIpAsync()
        }

        override fun onStopped() {
            if (switchingToFallback) return
            reservation = null
            info = null
            mode = Mode.NONE
            setState(State.STOPPED)
        }

        override fun onFailed(reason: Int) {
            LogUtil.w(AppConfig.TAG, "Local-only hotspot failed (reason $reason)")
            if (expectedCredentials != null && onConfiguredFailure != null) {
                useConfiguredFallback()
                return
            }
            reservation = null
            info = null
            fail()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun credentialsOf(res: WifiManager.LocalOnlyHotspotReservation): Pair<String, String> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val config = res.softApConfiguration
            val ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                config.wifiSsid?.toString()?.trim('"').orEmpty()
            } else {
                @Suppress("DEPRECATION")
                config.ssid.orEmpty()
            }
            return ssid to config.passphrase.orEmpty()
        }

        @Suppress("DEPRECATION")
        val config = res.wifiConfiguration
        return config?.SSID?.trim('"').orEmpty() to config?.preSharedKey?.trim('"').orEmpty()
    }

    // endregion

    /**
     * The AP/group interface gets its IPv4 address slightly after start; poll briefly.
     * Clients must point their proxy at this address.
     */
    private fun resolveGatewayIpAsync() {
        val resolvingMode = mode
        gatewayResolved = false
        CoroutineScope(Dispatchers.IO).launch {
            repeat(10) {
                if (state != State.RUNNING) return@launch
                val ip = findApAddress()
                if (ip != null) {
                    info = info?.copy(gatewayIp = ip)
                    gatewayResolved = true
                    notifyChanged()
                    return@launch
                }
                delay(700)
            }
            // Wi-Fi Direct group owners get a well-known address; local-only hotspots
            // fall back to the gateway the client sees on its side.
            if (resolvingMode == Mode.WIFI_P2P && state == State.RUNNING) {
                info = info?.copy(gatewayIp = P2P_GATEWAY_FALLBACK)
            }
            gatewayResolved = true
            notifyChanged()
        }
    }

    private fun findApAddress(): String? {
        val candidates = mutableListOf<Pair<String, String>>() // interface name to address
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (address in nif.inetAddresses) {
                    if (address is Inet4Address && address.isSiteLocalAddress) {
                        candidates.add(nif.name.lowercase() to address.hostAddress.orEmpty())
                    }
                }
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to enumerate hotspot interfaces", e)
        }

        return candidates.firstOrNull { (name, _) ->
            name.startsWith("ap") || name.startsWith("swlan") ||
                    name.startsWith("softap") || name.startsWith("p2p")
        }?.second
            ?: candidates.firstOrNull { (_, ip) -> ip.endsWith(".1") }?.second
    }

    private fun fail() {
        mode = Mode.NONE
        setState(State.FAILED)
    }

    private fun setState(newState: State) {
        state = newState
        notifyChanged()
    }

    private fun notifyChanged() {
        mainHandler.post { onStateChanged?.invoke() }
    }
}
