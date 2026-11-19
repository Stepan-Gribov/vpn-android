package com.v2ray.ang.simple

import android.content.Context
import android.net.VpnService
import com.v2ray.ang.core.CoreServiceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One opportunity per app process. Returning from settings/rotation never reconnects. */
internal class StartupAttempt {
    private var attempted = false
    private var userActions = 0L
    @Synchronized fun claim(): Long? {
        if (attempted) return null
        attempted = true
        return userActions
    }
    @Synchronized fun cancel() { attempted = true; userActions++ }
    @Synchronized fun current(ticket: Long) = ticket == userActions
}

internal object SimpleStartup {
    private val attempt = StartupAttempt()
    fun cancel() = attempt.cancel()
    @Suppress("DEPRECATION")
    private fun alreadyRunning(context: Context): Boolean {
        val networks = context.getSystemService(android.net.ConnectivityManager::class.java)
        if (networks?.allNetworks?.any {
            networks.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
        } == true) return true
        // Android still exposes this app's own services. A cached heartbeat can
        // remain fresh for 10 s after force-stop; it cannot veto a cold start.
        val manager = context.getSystemService(android.app.ActivityManager::class.java) ?: return false
        return manager.getRunningServices(Int.MAX_VALUE).any {
            it.service.packageName == context.packageName && it.service.className in setOf(
                "com.v2ray.ang.service.CoreVpnService", "com.v2ray.ang.service.AmneziaWgVpnService")
        }
    }

    suspend fun connectOnce(context: Context) = withContext(Dispatchers.IO) {
        val ticket = attempt.claim() ?: return@withContext
        try {
            val store = SimpleStore(context)
            if (!store.autoConnectOnRestart || alreadyRunning(context)) return@withContext
            val profile = store.configurations.firstOrNull { it.id == store.selectedProfileID } ?: return@withContext
            store.requireAccess(profile)
            val validated = profile.validated(forConnection = true)
            // Never launch a permission dialog or disconnect an existing VPN automatically.
            if (VpnService.prepare(context) != null || !attempt.current(ticket)) return@withContext
            store.reload()
            if (!store.autoConnectOnRestart || alreadyRunning(context) || !attempt.current(ticket)) return@withContext
            val current = store.configurations.firstOrNull { it.id == validated.id } ?: return@withContext
            store.requireAccess(current)
            if (!attempt.current(ticket)) return@withContext
            SimpleRuntime.stage(current.validated(forConnection = true), store) {
                attempt.current(ticket) && !alreadyRunning(context)
            }
            // Preparation is IO work; the service entry point also shows Android
            // UI notifications/toasts and must run on the main thread.
            withContext(Dispatchers.Main.immediate) {
                if (attempt.current(ticket) && !alreadyRunning(context)) {
                    CoreServiceManager.startVService(context, SimpleRuntime.STAGED_GUID)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) {
            // Missing/revoked profile, OS restriction or unavailable keystore: no retry loop.
            android.util.Log.w("TnDtun-Startup", "Automatic connection skipped or failed")
        }
    }
}
