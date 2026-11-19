package com.v2ray.ang.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.MessageUtil
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Runs the transport belonging to the selected DNS connection. */
object DnsTunnelManager {
    private const val FAILED_KEY = "dns_tunnel_failed_route_v1"
    private const val HELPER = "libkindlytransport.so"
    @Volatile private var process: Process? = null
    @Volatile private var currentNetwork: Network? = null
    @Volatile private var currentProfile = ""
    @Volatile var ready = false
        private set
    @Volatile var stage = "stopped"
        private set
    private var signature: String? = null
    private var processNetwork: Network? = null
    val active: Boolean get() = process != null
    val running: Boolean get() = process?.isAliveCompat() == true
    /** Native validation is shared with iOS/server code and never prints the input. */
    fun validate(context: Context, raw: String): String {
        val value = DnsTunnelSettings.fromJson(raw).toJson()
        val helper = File(context.applicationInfo.nativeLibraryDir, HELPER)
        require(helper.canExecute()) { "Обновите приложение: модуль DNS-туннеля отсутствует." }
        val check = ProcessBuilder(helper.absolutePath, "--check").redirectErrorStream(true).start()
        try {
            check.outputStream.use { it.write(value.toByteArray(Charsets.UTF_8)) }
            require(check.waitForCompat(5, TimeUnit.SECONDS)) { "Проверка конфигурации не завершена." }
            require(check.exitValue() == 0) { "Проверьте конфигурацию и поддержку маршрутов в этой сборке." }
            return value
        } finally { check.destroy() }
    }

    fun resetFailure() { MmkvManager.encodeSettings(FAILED_KEY, "") }

    /** Excludes VPN LinkProperties. The app UID is already excluded by CoreVpnService. */
    fun underlyingDNS(context: Context): List<String> {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val active = manager.activeNetwork
        val networks = manager.allNetworks.sortedBy { when (it) { currentNetwork -> 0; active -> 1; else -> 2 } }
        return networks.filter { network ->
            manager.getNetworkCapabilities(network)?.let {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            } == true
        }.take(1).flatMap { manager.getLinkProperties(it)?.dnsServers.orEmpty() }
            .mapNotNull { address -> address.hostAddress?.let { if (it.contains(':')) "[$it]:53" else "$it:53" } }
            .distinct().take(4)
    }

    @Synchronized fun underlyingNetwork(network: Network) { currentNetwork = network }
    @Synchronized fun lostNetwork(network: Network) { if (currentNetwork == network) currentNetwork = null }
    @Synchronized fun recordRouteFailure() {
        if (active && ready && currentProfile.isNotEmpty()) MmkvManager.encodeSettings(FAILED_KEY, currentProfile)
    }

    @Synchronized fun ensure(context: Context) {
        val json = JSONObject(DnsTunnelStore.load(context))
        json.put("listen", "127.0.0.1:${DnsTunnelProfiles.PORT}")
        json.put("start_after", MmkvManager.decodeSettingsString(FAILED_KEY).orEmpty())
        val configured = json.optJSONArray("bootstrap_dns") ?: JSONArray()
        val dns = (underlyingDNS(context) + (0 until configured.length()).map { configured.getString(it) }).distinct().take(4)
        json.put("bootstrap_dns", JSONArray(dns))
        val value = json.toString()
        // Handover needs fresh sockets even when both networks advertise the same DNS.
        if (signature == value && processNetwork == currentNetwork && process?.isAliveCompat() == true) return
        stop()
        val helper = File(context.applicationInfo.nativeLibraryDir, HELPER)
        require(helper.canExecute()) { "Модуль DNS-туннеля отсутствует." }
        val started = ProcessBuilder(helper.absolutePath).redirectErrorStream(true).start()
        process = started
        signature = value
        processNetwork = currentNetwork
        ready = false
        stage = "connecting"
        try { started.outputStream.use { it.write(value.toByteArray(Charsets.UTF_8)) } }
        catch (error: Exception) { stop(); throw error }
        thread(name = "dns-tunnel-events", isDaemon = true) {
            try {
                started.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
                    // Provider debug output is never sent to logcat or diagnostic export.
                    if (line.length > 2_048 || !line.startsWith('{')) return@forEach
                    val event = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
                    val notify = synchronized(this) {
                        if (process !== started) return@synchronized false
                        val becameReady = !ready && event.optBoolean("ready")
                        ready = event.optBoolean("ready")
                        currentProfile = event.optString("profile").takeIf { it.matches(Regex("[a-zA-Z0-9_-]{1,40}")) }.orEmpty()
                        stage = event.optString("stage").takeIf { it in setOf("connecting", "authenticated", "failed", "reconnecting", "cooldown", "stopped") } ?: "connecting"
                        becameReady
                    }
                    if (notify) MessageUtil.sendMsg2UI(context, AppConfig.MSG_VK_TURN_READY, context.getString(com.v2ray.ang.R.string.dns_tunnel_ready))
                } }
            } finally {
                synchronized(this) { if (process === started) { ready = false; stage = "failed" } }
            }
        }
    }

    @Synchronized fun stop() {
        val previous = process
        process = null
        signature = null
        processNetwork = null
        currentProfile = ""
        ready = false
        stage = "stopped"
        previous?.destroy()
        if (previous != null && !previous.waitForCompat(2, TimeUnit.SECONDS) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) previous.destroyForcibly()
    }

    // Process.isAlive and timed waitFor were only added in Android 8 (API 26).
    private fun Process.isAliveCompat(): Boolean = try {
        exitValue()
        false
    } catch (_: IllegalThreadStateException) { true }

    private fun Process.waitForCompat(timeout: Long, unit: TimeUnit): Boolean {
        val deadline = System.nanoTime() + unit.toNanos(timeout)
        while (isAliveCompat()) {
            if (System.nanoTime() >= deadline) return false
            Thread.sleep(25)
        }
        return true
    }
}
