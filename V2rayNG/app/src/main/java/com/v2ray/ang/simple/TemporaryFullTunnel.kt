package com.v2ray.ang.simple

import android.content.Context
import android.os.SystemClock
import com.google.gson.Gson
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.MessageUtil
import kotlinx.coroutines.*

/** Runtime-only lease; the VPN service owns transitions and expiry, never the Activity. */
internal object TemporaryFullTunnel {
    const val MESSAGE = 903
    private const val KEY = "simple.routing.temporary-full.v1"
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var expiryJob: Job? = null
    private var preserveNextStart = false // Deliberately not persisted across process death/reboot.
    data class Lease(val profile: String, val started: Long, val expires: Long)
    private fun read(): Lease? = runCatching {
        gson.fromJson(MmkvManager.decodeSettingsString(KEY, ""), Lease::class.java)
    }.getOrNull()
    fun active(profile: String?): Boolean = profile != null && currentLease(profile) != null
    fun currentLease(profile: String): Lease? = read()?.takeIf {
        TemporaryFullTunnelPolicy.valid(it.profile, profile, it.started, it.expires, SystemClock.elapsedRealtime())
    }
    @Synchronized fun prepare(profile: String, enabled: Boolean, minutes: Int) {
        require(minutes in TemporaryFullTunnelPolicy.durations)
        val now = SystemClock.elapsedRealtime()
        restoreForRestart(if (enabled) Lease(profile, now, if (minutes == 0) 0 else now + minutes * 60_000L) else null)
    }
    @Synchronized fun restoreForRestart(lease: Lease?) {
        expiryJob?.cancel(); expiryJob = null
        preserveNextStart = lease != null
        MmkvManager.encodeSettings(KEY, lease?.let(gson::toJson).orEmpty())
    }
    @Synchronized fun beforeConnectionStart(profile: String) {
        val keep = preserveNextStart && active(profile)
        preserveNextStart = false
        if (!keep) MmkvManager.encodeSettings(KEY, "")
    }
    @Synchronized fun stopped() {
        restoreForRestart(null)
    }
    @Synchronized fun started(context: Context) {
        expiryJob?.cancel()
        val lease = read()?.takeIf { it.expires > 0 } ?: return
        expiryJob = scope.launch {
            // Recheck monotonic time after sleep/Doze instead of extending the lease.
            while (isActive && read() == lease) {
                val left = lease.expires - SystemClock.elapsedRealtime()
                if (left <= 0) {
                    MessageUtil.sendMsg2Service(context, MESSAGE, "expire:${lease.started}")
                    return@launch
                }
                delay(left.coerceAtMost(1_000))
            }
        }
    }
    fun shouldExpire(token: Long): Boolean = read()?.let {
        it.started == token && it.expires > 0 && SystemClock.elapsedRealtime() >= it.expires
    } == true
}
