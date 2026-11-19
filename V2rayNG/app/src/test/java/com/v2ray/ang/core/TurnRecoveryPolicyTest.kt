package com.v2ray.ang.core

import org.junit.Assert.*
import org.junit.Test

class TurnRecoveryPolicyTest {
    @Test fun onlyEnabledCurrentAndSelectedTurnMayRecover() {
        for(setting in listOf(false,true)) for(current in listOf(false,true)) for(selected in listOf(false,true))
            assertEquals(setting && current && selected,TurnRecoveryPolicy.enabled(setting,current,selected))
        // The already-running TURN job cannot act after selecting Amnezia/DNS.
        assertFalse(TurnRecoveryPolicy.enabled(true,true,false))
        assertTrue(TurnRecoveryPolicy.enabled(true,true,true))
    }

    @Test fun turningAutomationOffDuringRepairKeepsVpnWhileDisconnectCancelsIt() {
        var autoRecovery = true
        var connectionDesired = true
        assertTrue(TurnRecoveryPolicy.enabled(autoRecovery, true, true)) // Repair starts.
        autoRecovery = false // Settings change while native lanes are recovering.
        assertEquals(TurnRecoveryPolicy.FailedRepairAction.KEEP_TUNNEL,
            TurnRecoveryPolicy.afterFailedRepair(connectionDesired, autoRecovery))
        connectionDesired = false // A real Disconnect must not publish unavailable/restart.
        assertEquals(TurnRecoveryPolicy.FailedRepairAction.IGNORE,
            TurnRecoveryPolicy.afterFailedRepair(connectionDesired, autoRecovery))
        autoRecovery = true
        assertEquals(TurnRecoveryPolicy.FailedRepairAction.IGNORE,
            TurnRecoveryPolicy.afterFailedRepair(connectionDesired, autoRecovery))
        assertEquals(TurnRecoveryPolicy.FailedRepairAction.RECONNECT,
            TurnRecoveryPolicy.afterFailedRepair(true, autoRecovery))
    }
}
