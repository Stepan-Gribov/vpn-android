package com.v2ray.ang.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AmneziaResumeTest {
    @Test fun staleMigrationCompletionCannotClearNewerNetwork() {
        val pending=AmneziaMigration()
        pending.request(1)
        val old=checkNotNull(pending.pending())
        pending.request(2)
        pending.completed(old)
        assertEquals(2L,pending.pending()?.network)
        pending.completed(checkNotNull(pending.pending()))
        assertNull(pending.pending())
    }
    @Test fun lossAndManualStopResetPendingEvent() {
        val gate=AmneziaResumeGate()
        assertTrue(gate.admit("available:1",10_000))
        gate.reset()
        assertTrue(gate.admit("available:1",10_100))
        val pending=AmneziaMigration(); pending.request(1); val old=checkNotNull(pending.pending())
        pending.reset(); pending.completed(old)
        assertNull(pending.pending())
    }

    @Test fun wakeCannotDiscardPendingNetworkMigration()=runBlocking {
        // Network callback requested migration, but wake superseded its coroutine
        // before its delay completed. A probe on the OLD network may still pass.
        var pending=true; var rebinds=0
        assertEquals(true,AmneziaResumeCheck.run(false,{true},{rebinds++;pending=false},{true},{it}, {pending}))
        assertEquals(1,rebinds)
        assertFalse(pending)
    }

    @Test fun wakeAndUnlockAreCoalescedButNewNetworkAndIPAreNot() {
        val gate=AmneziaResumeGate()
        assertTrue(gate.admit("wake:1",10_000))
        assertFalse(gate.admit("wake:1",11_000))
        assertTrue(gate.admit("network:2",11_001))
        assertTrue(gate.admit("address:2:192.0.2.3",11_002))
        assertFalse(gate.admit("address:2:192.0.2.3",11_003))
        assertTrue(gate.admit("address:2:192.0.2.3",16_002))
    }
    @Test fun healthyWakeDoesNotResetTransport()=runBlocking {
        var rebinds=0; var probes=0
        assertEquals(true,AmneziaResumeCheck.run(false,{true},{rebinds++},{probes++;true},{it}))
        assertEquals(0,rebinds); assertEquals(1,probes)
    }
    @Test fun brokenWakeRepairsOnceAndRequiresFreshTraffic()=runBlocking {
        var rebinds=0; var probes=0
        assertEquals(true,AmneziaResumeCheck.run(false,{true},{rebinds++},{++probes==2},{it}))
        assertEquals(1,rebinds); assertEquals(2,probes)
    }
    @Test fun failingRouteNeverLoopsOrTreatsRebindAsSuccess()=runBlocking {
        var rebinds=0; var probes=0
        assertEquals(false,AmneziaResumeCheck.run(false,{true},{rebinds++},{probes++;false},{it}))
        assertEquals(1,rebinds); assertEquals(2,probes)
    }
    @Test fun handoverRebindsBeforeAnyProbeAndDoesNotRepeat()=runBlocking {
        val steps=mutableListOf<String>()
        assertEquals(false,AmneziaResumeCheck.run(true,{true},{steps.add("rebind")},{steps.add("probe");false},{it}))
        assertEquals(listOf("rebind","probe"),steps)
    }
    @Test fun manualDisconnectDuringProbePreventsRepairAndGreenStatus()=runBlocking {
        var desired=true; var rebinds=0
        assertNull(AmneziaResumeCheck.run(false,{desired},{rebinds++},{desired=false;false},{it}))
        assertEquals(0,rebinds)
    }
    @Test fun newNetworkDuringRebindDiscardsTheOldAttempt()=runBlocking {
        var current=true; var probes=0
        assertNull(AmneziaResumeCheck.run(true,{current},{current=false},{probes++;true},{it}))
        assertEquals(0,probes)
    }
}
