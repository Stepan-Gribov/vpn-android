package com.v2ray.ang.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDnsTunnelReadinessTest {
    private val listener = "2026/09/24 KINDLYVPN_DNSTT_READY listening on 127.0.0.1:7100 sessions=4 max-qps=800"
    private fun pool(count: Int) = "2026/09/24 KINDLYVPN_DNSTT_POOL ready=$count requested=4"

    @Test
    fun poolAuthenticationDoesNotBecomeReadyBeforeLocalListener() {
        val state = ManagedDnsTunnelReadiness().apply { reset(1) }
        assertTrue(state.onLog(1, pool(1)))
        assertFalse(state.ready)
        assertTrue(state.onLog(1, listener))
        assertTrue(state.ready)
    }

    @Test
    fun listenerWithoutLiveSessionsIsNotReady() {
        val state = ManagedDnsTunnelReadiness().apply { reset(1) }
        state.onLog(1, listener)
        assertFalse(state.ready)
        state.onLog(1, pool(1))
        assertTrue(state.ready)
    }

    @Test
    fun allSessionLossClearsReadinessAndPoolRecoveryRestoresIt() {
        val state = ManagedDnsTunnelReadiness().apply { reset(1) }
        state.onLog(1, pool(4))
        state.onLog(1, listener)
        assertTrue(state.ready)
        state.onLog(1, pool(1))
        assertTrue(state.ready)
        state.onLog(1, pool(0))
        assertFalse(state.ready)
        state.onLog(1, pool(1))
        assertTrue(state.ready)
    }

    @Test
    fun oldGenerationLogsCannotReviveOrInvalidateReplacement() {
        val state = ManagedDnsTunnelReadiness().apply { reset(1) }
        state.onLog(1, pool(1))
        state.onLog(1, listener)
        state.reset(2)
        assertFalse(state.onLog(1, pool(4)))
        assertFalse(state.onLog(1, listener))
        assertFalse(state.ready)
        state.onLog(2, pool(1))
        state.onLog(2, listener)
        assertFalse(state.onLog(1, pool(0)))
        assertTrue(state.ready)
    }

    @Test
    fun sshMustReconnectAfterPoolLossAndLateOldHandshakeCannotRestoreReadiness() {
        val state = ManagedDnsTunnelReadiness().apply { reset(1, needsSsh = true) }
        state.onLog(1, pool(1))
        state.onLog(1, listener)
        val firstEpoch = state.transportEpoch
        assertFalse(state.ready)
        assertTrue(state.onSshReady(1, firstEpoch))
        assertTrue(state.ready)
        state.onLog(1, pool(0))
        assertFalse(state.ready)
        assertFalse(state.onSshReady(1, firstEpoch))
        state.onLog(1, pool(1))
        assertFalse(state.ready)
        assertFalse(state.onSshReady(1, firstEpoch))
        assertTrue(state.onSshReady(1, state.transportEpoch))
        assertTrue(state.ready)
        state.reset(2, needsSsh = true)
        state.onLog(2, pool(1))
        state.onLog(2, listener)
        assertFalse(state.onSshReady(1, state.transportEpoch))
        assertFalse(state.ready)
    }

    @Test
    fun slipstreamReadinessStillUsesListenerAndOptionalSshStage() {
        val state = ManagedDnsTunnelReadiness().apply { reset(1, needsPool = false) }
        state.onListenerReady(1)
        assertTrue(state.ready)
        state.reset(2, needsPool = false, needsSsh = true)
        state.onListenerReady(2)
        assertFalse(state.ready)
        assertTrue(state.onSshReady(2, state.transportEpoch))
        assertTrue(state.ready)
    }

    @Test
    fun unrelatedOrMalformedLogsDoNotChangeReadiness() {
        val state = ManagedDnsTunnelReadiness().apply { reset(1) }
        for (line in listOf("connection ready", pool(5), pool(-1), "KINDLYVPN_DNSTT_POOL ready=1 requested=0")) {
            assertFalse(state.onLog(1, line))
        }
        state.onLog(1, listener)
        assertFalse(state.ready)
    }
}
