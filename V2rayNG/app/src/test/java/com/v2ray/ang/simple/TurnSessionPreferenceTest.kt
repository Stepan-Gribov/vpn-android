package com.v2ray.ang.simple

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.v2ray.ang.core.TurnSessionCount
import org.junit.Assert.*
import org.junit.Test

class TurnSessionPreferenceTest {
    @Test fun legacyStateKeepsTenAndPanelSyncPreservesLocalChoice() {
        val gson = Gson()
        assertEquals(10, gson.fromJson("{}", SimpleState::class.java).turnSessionCount)
        val fixture = PanelFixture()
        for (count in TurnSessionCount.choices) {
            val state = SimpleState(turnSessionCount = count)
            val restored = gson.fromJson(gson.toJson(state), SimpleState::class.java)
            val synced = PanelStateReducer.apply(restored, fixture.packet(), fixture.trust, "token", 10, null)
            assertEquals(count, synced.turnSessionCount)
        }
    }

    @Test fun backupRoundTripAndRestorePreserveChoiceAndRejectInvalidValues() {
        val backup = ApplicationBackup(profiles = emptyList(), mode = "turn", autoCaptcha = true,
            autoRecovery = true, savedDNSServers = emptyList(), splitTunneling = SplitTunnelSettings(), turnSessionCount = 1)
        val decoded = ApplicationBackup.decode(backup.encoded())
        assertEquals(1, decoded.turnSessionCount)
        assertEquals(1, PanelStateReducer.restoreBackup(SimpleState(), decoded).turnSessionCount)
        val json = JsonParser.parseString(backup.encoded()).asJsonObject
        json.remove("turnSessionCount")
        assertEquals(10, ApplicationBackup.decode(json.toString()).turnSessionCount)
        for (invalid in listOf("0", "3", "-1", "1000", "\"1\"", "null", "1.5")) {
            json.add("turnSessionCount", JsonParser.parseString(invalid))
            assertThrows(Exception::class.java) { ApplicationBackup.decode(json.toString()) }
        }
    }
}
