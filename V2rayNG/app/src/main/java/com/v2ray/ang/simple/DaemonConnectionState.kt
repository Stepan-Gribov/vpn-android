package com.v2ray.ang.simple

import android.app.ActivityManager
import android.os.SystemClock
import com.google.gson.Gson
import com.v2ray.ang.AngApplication
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.service.CoreVpnService
import kotlinx.coroutines.*

/** One snapshot in multi-process MMKV. Only the VPN service writes it; any screen can read it. */
internal object DaemonConnectionState {
    private const val KEY = "simple.connection.snapshot.v1"
    private const val TRAFFIC_KEY = "simple.connection.traffic.v1"
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var heartbeat: Job? = null
    private var current = ConnectionSnapshot()
    private var ownsState = false

    @Synchronized fun serviceCreated() {
        ownsState = true
        current = ConnectionSnapshot(phase = "STARTING_CORE", serviceAlive = true)
        writeLocked()
        heartbeat?.cancel()
        heartbeat = scope.launch {
            while (isActive) {
                delay(2_000)
                synchronized(this@DaemonConnectionState) {
                    if (ownsState) writeLocked()
                }
            }
        }
    }

    @Synchronized fun publish(phase: String, attempt: Int = 0, maximum: Int = 5) {
        if (!ownsState) return // UI-process CoreServiceManager must never overwrite daemon state.
        val safePhase = if (!SettingsManager.isConnectionDesired()) "DISCONNECTED" else phase
        current = ConnectionSnapshot(safePhase, attempt.coerceAtLeast(0), maximum.coerceAtLeast(0), serviceAlive = true)
        writeLocked()
    }

    @Synchronized fun recordTraffic(upload: Double, download: Double, total: Long) {
        if (!ownsState) return
        val sample = TrafficSample(upload, download, total.toDouble(), SystemClock.elapsedRealtime())
        MmkvManager.encodeSettings(TRAFFIC_KEY, gson.toJson(sample))
    }

    @Synchronized fun recordHealth(started:Boolean,success:Boolean?=null,detail:String="") {
        if(!ownsState) return
        val previous=readHealth() ?: HealthProbeSnapshot()
        val now=SystemClock.elapsedRealtime()
        val next=if(started) previous.copy(startedAtMillis=now,running=true)
            else previous.copy(completedAtMillis=now,running=false,success=success,detail=detail.take(180))
        MmkvManager.encodeSettings("simple.health.snapshot.v1",gson.toJson(next))
    }
    fun readHealth(): HealthProbeSnapshot? = runCatching {
        gson.fromJson(MmkvManager.decodeSettingsString("simple.health.snapshot.v1",""),HealthProbeSnapshot::class.java)
    }.getOrNull()

    fun readTraffic(): TrafficSample? = runCatching {
        gson.fromJson(MmkvManager.decodeSettingsString(TRAFFIC_KEY, ""), TrafficSample::class.java)
    }.getOrNull()?.takeIf {
        val age = SystemClock.elapsedRealtime() - it.elapsedMillis
        age in 0..5_000
    }

    @Synchronized fun serviceDestroyed() {
        if (!ownsState) return
        current = ConnectionSnapshot()
        writeLocked()
        ownsState = false
        heartbeat?.cancel()
        heartbeat = null
    }

    private fun writeLocked() {
        current = current.copy(heartbeatMillis = SystemClock.elapsedRealtime())
        MmkvManager.encodeSettings(KEY, gson.toJson(current))
    }

    private fun readStored(): ConnectionSnapshot = runCatching {
        gson.fromJson(MmkvManager.decodeSettingsString(KEY, ""), ConnectionSnapshot::class.java)
    }.getOrNull() ?: ConnectionSnapshot()

    fun read(): ConnectionSnapshot = readStored().resolved(
        SystemClock.elapsedRealtime(), SettingsManager.isConnectionDesired(), ::isOwnVpnServiceRunning,
    )

    fun hasLiveService(): Boolean = readStored().hasLiveService(SystemClock.elapsedRealtime(), ::isOwnVpnServiceRunning)

    @Suppress("DEPRECATION") // Android still exposes this app's own services.
    private fun isOwnVpnServiceRunning(): Boolean = runCatching {
        val context = AngApplication.application
        val manager = context.getSystemService(ActivityManager::class.java) ?: return false
        manager.getRunningServices(Int.MAX_VALUE).any {
            it.service.packageName == context.packageName && it.service.className == CoreVpnService::class.java.name &&
                it.started && it.foreground && it.pid > 0 && it.restarting == 0L
        }
    }.getOrDefault(false)
}

internal data class TrafficSample(
    val upload: Double,
    val download: Double,
    val total: Double,
    val elapsedMillis: Long,
)

internal data class HealthProbeSnapshot(
    val startedAtMillis:Long=0,
    val completedAtMillis:Long=0,
    val running:Boolean=false,
    val success:Boolean?=null,
    val detail:String="",
)
