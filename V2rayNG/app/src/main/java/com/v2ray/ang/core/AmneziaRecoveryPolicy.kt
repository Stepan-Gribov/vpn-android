package com.v2ray.ang.core

/** One service-owned retry sequence; a single failed round never replaces a live helper. */
internal class AmneziaRecoveryPolicy {
    var failures = 0
        private set
    private var helperReplaced = false
    private var resetAtFailure: Int? = null
    private var outageDeadline: Long? = null

    fun newRoute() {
        failures = 0
        resetAtFailure = null
    }

    fun healthy() {
        newRoute()
        outageDeadline = null
        helperReplaced = false
    }

    fun connectionUnavailable(now: Long) {
        if (outageDeadline == null) outageDeadline = now + OUTAGE_TIMEOUT_MS
    }

    fun remainingOutageMillis(now: Long): Long? =
        outageDeadline?.let { (it - now).coerceAtLeast(0L) }

    fun failed(now: Long) {
        connectionUnavailable(now)
        failures++
    }

    fun exhausted(now: Long): Boolean =
        failures >= MAX_FAILED_ROUNDS || remainingOutageMillis(now) == 0L

    fun delayMillis(): Long = when (failures) {
        0 -> CHECK_INTERVAL_MS
        1 -> 2_000L
        2 -> 5_000L
        else -> 10_000L
    }
    fun resetTransport(receivingPayload: Boolean): Boolean =
        !receivingPayload && failures >= 1 && resetAtFailure == null
    fun resetting() { resetAtFailure = failures }
    // A final replacement is preferable to losing the whole VPN, even when
    // some packets still arrive. Earlier rounds preserve established streams.
    fun replaceHelper(alive: Boolean): Boolean =
        !alive || (!helperReplaced && failures == MAX_FAILED_ROUNDS - 1)

    fun replacing() { helperReplaced = true }

    companion object {
        const val MAX_FAILED_ROUNDS = 4
        const val OUTAGE_TIMEOUT_MS = 90_000L
        const val CHECK_INTERVAL_MS = 60_000L
    }
}
