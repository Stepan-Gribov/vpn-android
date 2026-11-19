package com.v2ray.ang.simple

import org.junit.Assert.*
import org.junit.Test

class ConnectionSnapshotTest {
    @org.junit.Test fun failedAmneziaRouteKeepsDisconnectAvailableWithoutGreenStatus() {
        val value=ConnectionSnapshot(phase="CONNECTION_UNAVAILABLE",heartbeatMillis=100,serviceAlive=true)
        org.junit.Assert.assertTrue(value.resolved(101,true).active)
        org.junit.Assert.assertFalse(value.resolved(101,true).connected)
        org.junit.Assert.assertFalse(value.resolved(101,false).active)
    }

    private val connected = ConnectionSnapshot("CONNECTED", heartbeatMillis = 20_000, serviceAlive = true)

    @Test fun `notification stop wins even when disconnect broadcast was missed`() {
        val state = connected.resolved(nowMillis = 20_100, connectionDesired = false)
        assertFalse(state.active)
        assertFalse(state.connected)
        assertEquals("DISCONNECTED", state.phase)
    }

    @Test fun `fresh running daemon restores state after activity returns`() {
        assertTrue(connected.resolved(22_000, true) { error("fresh heartbeat should not query the OS") }.connected)
    }

    @Test fun `killed process cannot leave a connected badge indefinitely`() {
        assertEquals("DISCONNECTED", connected.resolved(30_001, true) { false }.phase)
    }

    @Test fun `sleeping live VPN stays active without claiming verified traffic`() {
        val state = connected.resolved(30_001, true) { true }
        assertTrue(state.active)
        assertFalse(state.connected)
        assertEquals("VERIFYING_CONNECTION", state.phase)
    }

    @Test fun `stale recovery phase is retained when the own VPN service is running`() {
        val waiting = connected.copy(phase = "WAITING_FOR_NETWORK", attempt = 2)
        assertEquals(waiting, waiting.resolved(30_001, true) { true })
    }

    @Test fun `snapshot from before reboot is never treated as live`() {
        assertFalse(connected.resolved(5_000, true) { error("pre-reboot state should not query the OS") }.active)
    }

    @Test fun `stop UI and service cleanup are separate so switching waits for teardown`() {
        assertFalse(connected.resolved(30_001, false) { error("manual stop should not query the OS") }.active)
        assertTrue(connected.hasLiveService(30_001) { true })
        assertFalse(connected.copy(serviceAlive = false).hasLiveService(30_001) { true })
    }

    @Test fun `captcha and waiting for internet remain active but not connected`() {
        for (phase in listOf("TURN_CAPTCHA", "TURN_CAPTCHA_MANUAL", "WAITING_FOR_NETWORK", "RETRY_WAIT", "DNS_RECONNECTING")) {
            val state = connected.copy(phase = phase, attempt = 2).resolved(21_000, true)
            assertTrue(state.active)
            assertFalse(state.connected)
            assertEquals(2, state.attempt)
        }
    }

    @Test fun `manual captcha survives snapshot reload and cannot be confused with a healthy tunnel`() {
        val snapshot = connected.copy(phase = "TURN_CAPTCHA_MANUAL")
        val reloaded = com.google.gson.Gson().fromJson(com.google.gson.Gson().toJson(snapshot), ConnectionSnapshot::class.java)
        val state = reloaded.resolved(30_001, true) { true }
        assertTrue(state.active)
        assertTrue(state.captchaPending)
        assertTrue(state.captchaNeedsUser)
        assertFalse(state.connected)
        assertFalse(state.resolved(30_001, false).captchaPending)
        assertFalse(state.copy(phase = "STARTING_TURN").captchaNeedsUser)
    }

    @Test fun `failed recovery allows retry without claiming a live tunnel`() {
        val state = connected.copy(phase = "FAILED", serviceAlive = false).resolved(40_000, true)
        assertEquals("FAILED", state.phase)
        assertFalse(state.active)
        assertFalse(state.connected)
    }

    @Test fun `missing or unknown state never claims a connection`() {
        assertFalse(ConnectionSnapshot().resolved(21_000, true).active)
        assertEquals("DISCONNECTED", connected.copy(phase = "INVALID").resolved(21_000, true).phase)
    }
}
