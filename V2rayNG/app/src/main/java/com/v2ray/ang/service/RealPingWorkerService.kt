package com.v2ray.ang.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.core.ManagedDnsTunnelManager
import com.v2ray.ang.dto.RealPingEvent
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SpeedtestManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Worker that runs a batch of real-ping tests independently.
 * Each batch owns its own CoroutineScope/dispatcher and can be cancelled separately.
 */
class RealPingWorkerService(
    private val context: Context,
    private val guids: List<String>,
    private val onEvent: (RealPingEvent) -> Unit = {}
) {
    private val job = SupervisorJob()
    private val concurrency = SettingsManager.getRealPingConcurrency()
    private val dispatcher = Executors.newFixedThreadPool(concurrency).asCoroutineDispatcher()
    private val scope = CoroutineScope(job + dispatcher + CoroutineName("RealPingBatchWorker"))

    private val runningCount = AtomicInteger(0)
    private val totalCount = AtomicInteger(0)

    fun start() {
        val jobs = guids.map { guid ->
            totalCount.incrementAndGet()
            scope.launch {
                runningCount.incrementAndGet()
                try {
                    val result = startRealPing(guid)
                    onEvent(RealPingEvent.Result(guid, result))
                } catch (_: Throwable) {
                    // ignore
                } finally {
                    val count = totalCount.decrementAndGet()
                    val left = runningCount.decrementAndGet()
                    onEvent(RealPingEvent.Progress("$left / $count"))
                }
            }
        }

        scope.launch {
            try {
                joinAll(*jobs.toTypedArray())
                onEvent(RealPingEvent.Finish("0"))
            } catch (_: CancellationException) {
                onEvent(RealPingEvent.Finish("-1"))
            } finally {
                close()
            }
        }
    }

    fun cancel() {
        job.cancel()
    }

    private fun close() {
        try {
            dispatcher.close()
        } catch (_: Throwable) {
            // ignore
        }
    }

    private fun startRealPing(guid: String): Long {
        val retFailure = -1L

        val config = MmkvManager.decodeServerConfig(guid) ?: return retFailure
        if (config.configType == EConfigType.AMNEZIAWG) {
            return measureConnectedAmneziaWgRoute(guid)
        }
        val isManagedDnsTunnel = ManagedDnsTunnelManager.isManagedDnsTunnelProfile(config)
        if (isManagedDnsTunnel) {
            try {
                ManagedDnsTunnelManager.ensureForProfile(context, config)
                if (!ManagedDnsTunnelManager.awaitReadyForTraffic()) {
                    ManagedDnsTunnelManager.stopTestHelperIfIdle()
                    return retFailure
                }
            } catch (_: Exception) {
                ManagedDnsTunnelManager.stopTestHelperIfIdle()
                return retFailure
            }
        }

        try {
            if (!config.configType.isComplexType()
                && config.server.isNotNullEmpty()
                && config.serverPort?.toIntOrNull() != null
            ) {
                val url = config.server.orEmpty()
                val port = config.serverPort.orEmpty().toInt()
                val tcpTime = SpeedtestManager.socketConnectTime(url, port, 1000)
                if (tcpTime <= -1L) {
                    return retFailure
                }
            }

            val configResult = CoreConfigManager.getV2rayConfig4Speedtest(context, guid)
            if (!configResult.status) {
                return retFailure
            }
            return CoreNativeManager.measureOutboundDelay(
                configResult.content,
                SettingsManager.getDelayTestUrl(),
            )
        } finally {
            if (isManagedDnsTunnel) ManagedDnsTunnelManager.stopTestHelperIfIdle()
        }
    }

    /** AWG profiles cannot be tested by building an Xray outbound; probe the active VPN data path. */
    private fun measureConnectedAmneziaWgRoute(guid: String): Long {
        if (MmkvManager.getSelectServer() != guid || !SettingsManager.isConnectionDesired()) return -1L
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return -1L
        if (connectivity.allNetworks.none { network ->
                connectivity.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
        ) return -1L

        val startedAt = SystemClock.elapsedRealtime()
        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
        return runCatching {
            val request = Request.Builder()
                .url("https://1.1.1.1/cdn-cgi/trace")
                .header("User-Agent", "KindlyVPN AmneziaWG ping")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful || response.body.byteStream().use { it.read() } == -1) {
                    -1L
                } else {
                    SystemClock.elapsedRealtime() - startedAt
                }
            }
        }.getOrDefault(-1L)
    }
}
