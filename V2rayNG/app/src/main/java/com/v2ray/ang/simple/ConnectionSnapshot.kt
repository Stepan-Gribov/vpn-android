package com.v2ray.ang.simple

/** Last authoritative daemon state. Monotonic time invalidates leftovers after a reboot. */
internal data class ConnectionSnapshot(
    val phase: String = "DISCONNECTED",
    val attempt: Int = 0,
    val maximumAttempts: Int = 5,
    val heartbeatMillis: Long = 0L,
    val serviceAlive: Boolean = false,
) {
    val active: Boolean get() = phase in ACTIVE_PHASES
    val connected: Boolean get() = phase == "CONNECTED"
    val captchaPending: Boolean get() = phase == "TURN_CAPTCHA" || phase == "TURN_CAPTCHA_MANUAL"
    val captchaNeedsUser: Boolean get() = phase == "TURN_CAPTCHA_MANUAL"

    fun hasLiveService(nowMillis: Long, ownServiceRunning: () -> Boolean = { false }): Boolean =
        serviceAlive && heartbeatMillis > 0L && nowMillis >= heartbeatMillis &&
            (nowMillis - heartbeatMillis <= STALE_AFTER_MILLIS || ownServiceRunning())

    fun resolved(
        nowMillis: Long,
        connectionDesired: Boolean,
        ownServiceRunning: () -> Boolean = { false },
    ): ConnectionSnapshot {
        if (!connectionDesired) return copy(phase = "DISCONNECTED", attempt = 0)
        if (phase !in ACTIVE_PHASES && phase != "FAILED") return copy(phase = "DISCONNECTED", attempt = 0)
        if (active) {
            if (!hasLiveService(nowMillis, ownServiceRunning)) return copy(phase = "DISCONNECTED", attempt = 0)
            // A live service can survive sleep without publishing a heartbeat. Its
            // presence keeps Disconnect available but cannot verify traffic.
            if (connected && !hasLiveService(nowMillis)) return copy(phase = "VERIFYING_CONNECTION")
        }
        return this
    }

    companion object {
        const val STALE_AFTER_MILLIS = 10_000L
        private val ACTIVE_PHASES = setOf(
            "CONNECTED", "CONNECTION_UNAVAILABLE", "RECONNECT_SCHEDULED", "SWITCHING_PROFILE", "WAITING_FOR_NETWORK",
            "RETRY_WAIT", "STOPPING", "STARTING_TURN", "STARTING_CORE", "DNS_RECONNECTING",
            "TURN_CAPTCHA", "TURN_CAPTCHA_MANUAL", "TURN_READY", "VERIFYING_CONNECTION",
        )
    }
}
