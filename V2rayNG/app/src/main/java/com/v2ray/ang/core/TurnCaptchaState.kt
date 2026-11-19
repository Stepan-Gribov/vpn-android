package com.v2ray.ang.core

/** One native challenge, independent of the saved preference and Activity lifetime. */
internal class TurnCaptchaState {
    data class Challenge(val id: Long, val startedAtMillis: Long, val needsUser: Boolean) {
        fun expired(now: Long) = now - startedAtMillis >= ConnectionDropPolicy.TURN_MANUAL_CAPTCHA_TIMEOUT_MS
        fun shouldHandOff(now: Long) = !needsUser &&
            now - startedAtMillis >= ConnectionDropPolicy.TURN_AUTO_CAPTCHA_TIMEOUT_MS
    }

    private var serial = 0L
    private var current: Challenge? = null

    @Synchronized fun begin(now: Long, automatic: Boolean): Challenge = current ?: Challenge(
        ++serial, now, needsUser = !automatic,
    ).also { current = it }

    @Synchronized fun snapshot(): Challenge? = current

    @Synchronized fun requireUser(id: Long): Boolean {
        val value = current?.takeIf { it.id == id } ?: return false
        current = value.copy(needsUser = true)
        return true
    }

    @Synchronized fun clear() { current = null }
}
