package com.v2ray.ang.core

/** Pure decision rules used to distinguish an actual connection drop from a user stop. */
internal object ConnectionDropPolicy {
    const val DEFAULT_MAX_RETRIES = 5
    const val MIN_MAX_RETRIES = 1
    const val MAX_MAX_RETRIES = 20
    const val FAILED_HEALTH_CHECKS_BEFORE_DROP = 2
    const val TURN_START_READY_TIMEOUT_MS = 90_000L
    const val TURN_AUTO_CAPTCHA_TIMEOUT_MS = 45_000L
    const val TURN_MANUAL_CAPTCHA_TIMEOUT_MS = 5 * 60_000L

    fun isUnexpected(connectionWasActive: Boolean, shutdownExpected: Boolean): Boolean =
        connectionWasActive && !shutdownExpected

    fun shouldReconnect(autoReconnectEnabled: Boolean, unexpected: Boolean): Boolean =
        autoReconnectEnabled && unexpected

    fun normalizeMaxRetries(value: Int): Int =
        value.coerceIn(MIN_MAX_RETRIES, MAX_MAX_RETRIES)

    fun isTunnelDown(consecutiveFailures: Int): Boolean =
        consecutiveFailures >= FAILED_HEALTH_CHECKS_BEFORE_DROP

    fun turnReadyTimeoutMillis(captchaPending: Boolean, autoCaptcha: Boolean, recoveryTransport: Boolean = false): Long = when {
        recoveryTransport -> 180_000L
        captchaPending && autoCaptcha -> TURN_AUTO_CAPTCHA_TIMEOUT_MS
        captchaPending -> TURN_MANUAL_CAPTCHA_TIMEOUT_MS
        else -> TURN_START_READY_TIMEOUT_MS
    }

    fun hasTimedOut(startedAtMillis: Long, nowMillis: Long, timeoutMillis: Long): Boolean =
        startedAtMillis > 0L && nowMillis - startedAtMillis >= timeoutMillis
}
