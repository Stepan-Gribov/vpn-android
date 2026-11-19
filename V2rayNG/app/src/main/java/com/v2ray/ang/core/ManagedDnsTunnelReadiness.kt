package com.v2ray.ang.core

/** Readiness for one helper generation; callers serialize access with the manager lock. */
internal class ManagedDnsTunnelReadiness {
    private var generation = 0L
    private var needsPool = true
    private var needsSsh = false
    private var listenerReady = false
    private var liveSessions = 0
    private var sshReady = false

    // An SSH handshake started before complete pool loss cannot restore readiness later.
    var transportEpoch = 0L
        private set

    val transportReady: Boolean
        get() = listenerReady && (!needsPool || liveSessions > 0)

    val ready: Boolean
        get() = transportReady && (!needsSsh || sshReady)

    fun reset(generation: Long, needsPool: Boolean = true, needsSsh: Boolean = false) {
        this.generation = generation
        this.needsPool = needsPool
        this.needsSsh = needsSsh
        listenerReady = false
        liveSessions = 0
        sshReady = false
        transportEpoch++
    }

    fun onLog(generation: Long, line: String): Boolean {
        if (generation != this.generation || !needsPool) return false
        POOL_STATUS.find(line)?.let { match ->
            val live = match.groupValues[1].toIntOrNull() ?: return false
            val requested = match.groupValues[2].toIntOrNull() ?: return false
            if (requested !in 1..16 || live !in 0..requested) return false
            if (live == 0 && liveSessions > 0) {
                sshReady = false
                transportEpoch++
            }
            liveSessions = live
            return true
        }
        if (LISTENER_STATUS.containsMatchIn(line)) return onListenerReady(generation)
        return false
    }

    fun onListenerReady(generation: Long): Boolean {
        if (generation != this.generation) return false
        listenerReady = true
        return true
    }

    fun onSshReady(generation: Long, epoch: Long): Boolean {
        if (generation != this.generation || epoch != transportEpoch || !transportReady) return false
        sshReady = true
        return true
    }

    companion object {
        private val POOL_STATUS = Regex("(?:^|\\s)KINDLYVPN_DNSTT_POOL ready=(\\d+) requested=(\\d+)(?:$|\\s)")
        private val LISTENER_STATUS = Regex("(?:^|\\s)KINDLYVPN_DNSTT_READY listening on ")
    }
}
