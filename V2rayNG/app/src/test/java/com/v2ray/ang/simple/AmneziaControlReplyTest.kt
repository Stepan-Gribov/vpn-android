package com.v2ray.ang.simple

import org.junit.Assert.*
import org.junit.Test

class AmneziaControlReplyTest {
    @Test fun stopCancelsWaitWithoutWaitingForTheHelperTimeout() {
        val started = System.nanoTime()
        assertFalse(AmneziaControlReply(4).await(5000) { false })
        assertTrue((System.nanoTime() - started) / 1_000_000 < 100)
    }
    @Test fun lateOldReplyCannotAcknowledgeNewNetwork() {
        val old = AmneziaControlReply(1)
        assertFalse(old.await(1))
        val current = AmneziaControlReply(2)
        current.accept("TNDTUN_AMNEZIA_REBOUND 1")
        assertFalse(current.await(1))
        current.accept("TNDTUN_AMNEZIA_REBIND_FAILED 2")
        assertFalse(current.await(1))
    }
    @Test fun onlyMatchingSuccessCompletesMigration() {
        val current = AmneziaControlReply(3)
        current.accept("TNDTUN_AMNEZIA_REBOUND")
        assertFalse(current.await(1))
        current.accept("TNDTUN_AMNEZIA_REBIND_FAILED 2")
        assertFalse(current.await(1))
        current.accept("TNDTUN_AMNEZIA_REBOUND 3")
        assertTrue(current.await(1))
    }
}
