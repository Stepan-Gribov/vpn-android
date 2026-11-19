package com.v2ray.ang.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionDropPolicyTest {
    @Test
    fun `active connection stopping without a request is a drop`() {
        assertTrue(ConnectionDropPolicy.isUnexpected(true, false))
    }

    @Test
    fun `manual stop is not a drop and never reconnects`() {
        val unexpected = ConnectionDropPolicy.isUnexpected(true, true)

        assertFalse(unexpected)
        assertFalse(ConnectionDropPolicy.shouldReconnect(true, unexpected))
    }

    @Test
    fun `unexpected drop reconnects only when enabled`() {
        assertTrue(ConnectionDropPolicy.shouldReconnect(true, true))
        assertFalse(ConnectionDropPolicy.shouldReconnect(false, true))
    }

    @Test
    fun `health monitor requires two consecutive failures`() {
        assertFalse(ConnectionDropPolicy.isTunnelDown(1))
        assertTrue(ConnectionDropPolicy.isTunnelDown(2))
    }

    @Test
    fun `retry count is bounded and defaults to five`() {
        assertEquals(5, ConnectionDropPolicy.DEFAULT_MAX_RETRIES)
        assertEquals(1, ConnectionDropPolicy.normalizeMaxRetries(0))
        assertEquals(7, ConnectionDropPolicy.normalizeMaxRetries(7))
        assertEquals(20, ConnectionDropPolicy.normalizeMaxRetries(100))
    }

    @Test
    fun `automatic TURN captcha has a bounded background deadline`() {
        assertEquals(
            ConnectionDropPolicy.TURN_AUTO_CAPTCHA_TIMEOUT_MS,
            ConnectionDropPolicy.turnReadyTimeoutMillis(captchaPending = true, autoCaptcha = true),
        )
        assertTrue(
            ConnectionDropPolicy.hasTimedOut(
                startedAtMillis = 1_000L,
                nowMillis = 1_000L + ConnectionDropPolicy.TURN_AUTO_CAPTCHA_TIMEOUT_MS,
                timeoutMillis = ConnectionDropPolicy.TURN_AUTO_CAPTCHA_TIMEOUT_MS,
            ),
        )
    }

    @Test
    fun `manual TURN captcha keeps a longer user interaction deadline`() {
        assertEquals(
            ConnectionDropPolicy.TURN_MANUAL_CAPTCHA_TIMEOUT_MS,
            ConnectionDropPolicy.turnReadyTimeoutMillis(captchaPending = true, autoCaptcha = false),
        )
        assertFalse(
            ConnectionDropPolicy.hasTimedOut(
                startedAtMillis = 1_000L,
                nowMillis = 1_000L + ConnectionDropPolicy.TURN_AUTO_CAPTCHA_TIMEOUT_MS,
                timeoutMillis = ConnectionDropPolicy.TURN_MANUAL_CAPTCHA_TIMEOUT_MS,
            ),
        )
    }
}
