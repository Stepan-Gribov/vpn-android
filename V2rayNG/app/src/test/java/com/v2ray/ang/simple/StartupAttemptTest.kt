package com.v2ray.ang.simple

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors

class StartupAttemptTest {
    @Test fun concurrentBootAndActivityOnlyClaimOnce() {
        val attempt=StartupAttempt(); val pool=Executors.newFixedThreadPool(4)
        try {
            val claims=pool.invokeAll((1..24).map { java.util.concurrent.Callable { attempt.claim() } }).map { it.get() }
            assertEquals(1,claims.count { it!=null })
        } finally { pool.shutdownNow() }
    }
    @Test fun manualActionInvalidatesPendingStartAndResumeNeverRetries() {
        val attempt=StartupAttempt(); val ticket=attempt.claim()!!
        assertTrue(attempt.current(ticket))
        attempt.cancel()
        assertFalse(attempt.current(ticket)); assertNull(attempt.claim())
    }
    @Test fun manualDisconnectBeforeLoadingPreventsAutomaticAttempt() {
        val attempt=StartupAttempt()
        attempt.cancel()
        assertNull(attempt.claim())
    }
    @Test fun legacyStateDefaultsOffAndEnabledStateSurvivesReloadAndPanelSync() {
        val gson=Gson()
        assertFalse(gson.fromJson("{}",SimpleState::class.java).autoConnectOnRestart)
        val state=SimpleState(autoConnectOnRestart=true)
        val restored=gson.fromJson(gson.toJson(state),SimpleState::class.java)
        val fixture=PanelFixture()
        assertTrue(PanelStateReducer.apply(restored,fixture.packet(),fixture.trust,"token",10,null).autoConnectOnRestart)
    }
    @Test fun backupRetainsOptInAndOldBackupDefaultsOff() {
        val backup=ApplicationBackup(profiles=emptyList(),mode="turn",autoCaptcha=true,autoRecovery=true,
            savedDNSServers=emptyList(),splitTunneling=SplitTunnelSettings(),autoConnectOnRestart=true)
        assertTrue(ApplicationBackup.decode(backup.encoded()).autoConnectOnRestart)
        val root=com.google.gson.JsonParser.parseString(backup.encoded()).asJsonObject
        root.remove("autoConnectOnRestart")
        assertFalse(ApplicationBackup.decode(root.toString()).autoConnectOnRestart)
        assertTrue(PanelStateReducer.restoreBackup(SimpleState(),backup).autoConnectOnRestart)
    }
}
