package com.v2ray.ang.core

import org.junit.Assert.*
import org.junit.Test

class AmneziaRecoveryPolicyTest {
    @Test fun repeatedFailuresEndAfterHelperReplacementHasHadOneRound() {
        val policy = AmneziaRecoveryPolicy()
        val delays = (1 until AmneziaRecoveryPolicy.MAX_FAILED_ROUNDS).map { round ->
            policy.failed(round * 1000L)
            assertFalse(policy.exhausted(round * 1000L))
            policy.delayMillis()
        }
        assertEquals(listOf(2000L, 5000L, 10000L), delays)
        policy.failed(4000)
        assertTrue(policy.exhausted(4000))
        policy.healthy()
        assertFalse(policy.exhausted(Long.MAX_VALUE))
        assertEquals(AmneziaRecoveryPolicy.CHECK_INTERVAL_MS, policy.delayMillis())
    }

    @Test fun offlineWaitAndNetworkChangesCannotExtendTheOutage() {
        val policy = AmneziaRecoveryPolicy()
        val start = 1000L
        val deadline = start + AmneziaRecoveryPolicy.OUTAGE_TIMEOUT_MS
        policy.connectionUnavailable(start)
        policy.failed(start + 1000)
        policy.newRoute()
        policy.connectionUnavailable(deadline - 1)
        assertEquals(0, policy.failures)
        assertEquals(1L, policy.remainingOutageMillis(deadline - 1))
        assertFalse(policy.exhausted(deadline - 1))
        assertTrue(policy.exhausted(deadline))

        policy.healthy()
        assertNull(policy.remainingOutageMillis(deadline))
        policy.connectionUnavailable(deadline)
        assertEquals(AmneziaRecoveryPolicy.OUTAGE_TIMEOUT_MS, policy.remainingOutageMillis(deadline))
    }

    @Test fun softRepairAndHardResetPrecedeTheLastRoundReplacement() {
        val policy = AmneziaRecoveryPolicy()
        assertFalse(policy.resetTransport(false))
        policy.failed(1000)
        assertFalse(policy.replaceHelper(true))
        assertTrue(policy.resetTransport(false))
        policy.resetting()
        assertFalse(policy.resetTransport(false))
        policy.failed(2000)
        assertFalse(policy.replaceHelper(true))
        policy.failed(3000)
        assertTrue(policy.replaceHelper(true))
        policy.replacing()
        assertFalse(policy.replaceHelper(true))
        assertTrue("A dead helper still needs replacement", policy.replaceHelper(false))
    }

    @Test fun partialPayloadDoesNotPreventLastRoundReplacement() {
        val policy = AmneziaRecoveryPolicy()
        repeat(3) {
            policy.failed(1000)
            assertFalse("Received payload protects streams before the final round", policy.resetTransport(true))
        }
        assertTrue(policy.replaceHelper(true))
    }

    @Test fun deadHelperIsStrongFailureEvidenceWithoutWaitingForSiteProbes() {
        assertTrue(AmneziaRecoveryPolicy().replaceHelper(false))
        assertFalse(AmneziaRecoveryPolicy().replaceHelper(true))
    }

    @Test fun newNetworkGetsSoftRepairBeforeOldFailuresCanDestroyStreams() {
        val policy = AmneziaRecoveryPolicy()
        repeat(3) { policy.failed(1000) }
        assertTrue(policy.replaceHelper(true))
        policy.newRoute()
        assertFalse(policy.replaceHelper(true))
        assertFalse(policy.resetTransport(false))
        policy.failed(1000)
        assertTrue(policy.resetTransport(false))
    }
}
