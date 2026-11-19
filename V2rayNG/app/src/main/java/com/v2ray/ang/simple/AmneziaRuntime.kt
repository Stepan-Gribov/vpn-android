package com.v2ray.ang.simple

import android.content.Context
import com.google.gson.Gson
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.handler.MmkvManager
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Separate userspace engine; its sockets bypass Android's VPN via the app UID. */
object AmneziaRuntime {
    const val SETTINGS = "simple.amnezia.runtime.v1"
    const val OUTBOUND = "simple.amnezia.outbound.v1"
    @Volatile private var process: Process? = null
    @Volatile private var processPID = 0
    @Volatile private var rebindReply: AmneziaControlReply? = null
    private val requestIDs = java.util.concurrent.atomic.AtomicLong()
    fun effectiveVpnMtu(fallback: Int): Int {
        if (MmkvManager.getSelectServer() != SimpleRuntime.STAGED_GUID) return fallback
        val raw = MmkvManager.decodeSettingsString(SETTINGS).orEmpty()
        return runCatching { Gson().fromJson(raw, AmneziaImporter.Runtime::class.java)?.mtu }
            .getOrNull()?.takeIf { it in 576..1500 }?.let { minOf(fallback, it) } ?: fallback
    }
    @Volatile private var lastPayloadAt = 0L
    fun recentPayload(now: Long): Boolean = isAlive() && lastPayloadAt > 0 && now - lastPayloadAt in 0 until 60_000L
    fun isAlive(): Boolean = process?.alive() == true

    @Synchronized fun ensure(context: Context, networkHandle: Long = 0, wanted: () -> Boolean = { true }) {
        check(wanted()) { "Amnezia start cancelled" }
        val raw = MmkvManager.decodeSettingsString(SETTINGS).orEmpty()
        if (MmkvManager.getSelectServer() != SimpleRuntime.STAGED_GUID || raw.isBlank()) { stop(); return }
        if (process?.alive() == true) return
        val executable = File(context.applicationInfo.nativeLibraryDir, "libtndtunamnezia.so")
        check(executable.canExecute()) { "Движок Amnezia отсутствует. Переустановите приложение через Debug." }
        val started = ProcessBuilder(executable.absolutePath).redirectErrorStream(true).start()
        process = started
        processPID = 0
        lastPayloadAt = 0
        val ready = CountDownLatch(1)
        Thread({
            try {
                started.inputStream.bufferedReader().useLines { lines -> lines.forEach {
                    if (process === started) {
                        if (it.startsWith("TNDTUN_AMNEZIA_PID ")) {
                            processPID = it.substringAfter(' ').toIntOrNull()?.takeIf { pid -> pid > 0 } ?: 0
                        }
                        if (it == "TNDTUN_AMNEZIA_READY" && processPID > 0) ready.countDown()
                        if (it == "TNDTUN_AMNEZIA_PAYLOAD_RECEIVED") lastPayloadAt = android.os.SystemClock.elapsedRealtime()
                        rebindReply?.accept(it)
                    }
                } }
            } catch (_: java.io.IOException) {
                // Process.destroy closes the pipe while the reader is blocked.
                // A normal stop must never crash the whole VPN process.
            } finally {
                ready.countDown()
                if (process === started) {
                    process = null
                    CoreServiceManager.reportConnectionDrop(context.applicationContext, "Движок Amnezia остановился")
                }
            }
        }, "TnDtun-Amnezia-output").start()
        try {
            val runtime = com.google.gson.JsonParser.parseString(raw).asJsonObject
            val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            connectivity.activeNetwork?.let { network ->
                if (connectivity.getNetworkCapabilities(network)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true)
                    runtime.addProperty("network", network.networkHandle)
            }
            if (networkHandle != 0L) runtime.addProperty("network", networkHandle)
            started.outputStream.write((runtime.toString() + "\n").toByteArray(Charsets.UTF_8))
            started.outputStream.flush()
            val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
            while (wanted() && android.os.SystemClock.elapsedRealtime() < deadline && !ready.await(100, TimeUnit.MILLISECONDS)) { }
            check(wanted() && ready.count == 0L && started.alive() && process === started) { "Не удалось запустить Amnezia. Проверьте ключ подключения." }
        } catch (error: Exception) { stop(); throw error }
    }
    /** Retire only this helper and its SOCKS streams, retaining Xray and Android's TUN. */
    @Synchronized fun restart(context: Context, networkHandle: Long, wanted: () -> Boolean): Boolean {
        if (!wanted()) return false
        stop()
        if (!wanted()) return false
        ensure(context, networkHandle, wanted)
        return wanted() && isAlive()
    }
    private fun Process.alive(): Boolean = runCatching { exitValue(); false }.getOrDefault(true)
    @Synchronized fun refreshNetwork(context: Context, networkHandle: Long, wanted: () -> Boolean = { true }, reset: Boolean = false): Boolean {
        if (!wanted()) return false
        val running = process ?: return false
        if (!running.alive()) return false
        val reply = AmneziaControlReply(requestIDs.incrementAndGet())
        rebindReply = reply
        return try {
            val action = if (reset) "reset" else "rebind"
            running.outputStream.write("{\"action\":\"$action\",\"network\":$networkHandle,\"id\":${reply.id}}\n".toByteArray(Charsets.UTF_8))
            running.outputStream.flush()
            reply.await(5_000, wanted) && process === running
        } catch (_: Exception) { false }
        finally { rebindReply = null }
    }
    @Synchronized fun stop() {
        val running = process ?: return
        val pid = processPID
        process = null
        processPID = 0
        running.destroy()
        val deadline = android.os.SystemClock.elapsedRealtime() + 2000
        while (running.alive() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(25)
        if (running.alive()) {
            // Android's Process.destroyForcibly may delegate to destroy (SIGTERM).
            // A frozen/wedged child must release SOCKS before its replacement starts.
            if (pid > 0) android.os.Process.killProcess(pid)
            else if (android.os.Build.VERSION.SDK_INT >= 26) running.destroyForcibly()
            val killedDeadline = android.os.SystemClock.elapsedRealtime() + 1000
            while (running.alive() && android.os.SystemClock.elapsedRealtime() < killedDeadline) Thread.sleep(25)
        }
    }
}
