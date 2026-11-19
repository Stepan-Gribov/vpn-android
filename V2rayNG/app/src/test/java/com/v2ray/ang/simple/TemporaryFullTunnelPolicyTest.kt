package com.v2ray.ang.simple

import org.junit.Assert.*
import org.junit.Test

class TemporaryFullTunnelPolicyTest {
    private val saved=SplitTunnelSettings(SplitTunnelSettings.TUNNEL,
        bypassEntries=SplitTunnelImport.manual("direct.test 8.8.8.0/24"),
        tunnelEntries=SplitTunnelImport.manual("proxy.test 9.9.9.0/24"),
        pushNotifications=PushNotificationSettings(apple=true,google=true))
    @Test fun `TURN works by default Amnezia requires opt in DNS remains full`() {
        for((mode, optIn, expected) in listOf(Triple("turn",false,true),Triple("turn",true,true),
            Triple("amnezia",false,false),Triple("amnezia",true,true),Triple("dns",true,false))) {
            assertEquals(expected,TemporaryFullTunnelPolicy.supported(mode,optIn))
            assertEquals(if(expected) saved.mode else SplitTunnelSettings.DISABLED,
                TemporaryFullTunnelPolicy.split(saved,mode,optIn,false).mode)
            assertEquals(if(expected) listOf("com.example.app") else emptyList<String>(),
                TemporaryFullTunnelPolicy.applications(listOf("com.example.app"),mode,optIn,false))
        }
    }
    @Test fun `override removes address and app exclusions but preserves saved lists and push`() {
        for(mode in listOf(SplitTunnelSettings.TUNNEL,SplitTunnelSettings.BYPASS)) {
            val source=saved.copy(mode=mode)
            val full=TemporaryFullTunnelPolicy.split(source,"turn",false,true)
            assertEquals(SplitTunnelSettings.DISABLED,full.mode)
            assertTrue(full.networks.isEmpty());assertTrue(full.domains.isEmpty())
            assertEquals(source.bypassEntries,full.bypassEntries);assertEquals(source.tunnelEntries,full.tunnelEntries)
            assertEquals(source.pushNotifications,full.pushNotifications)
            assertTrue(TemporaryFullTunnelPolicy.applications(listOf("com.example.app"),"turn",false,true).isEmpty())
            assertEquals(source,TemporaryFullTunnelPolicy.split(source,"turn",false,false))
        }
    }
    @Test fun `monotonic deadline expiry profile switch and reboot reject a lease`() {
        assertTrue(TemporaryFullTunnelPolicy.valid("one","one",100,200,199))
        assertFalse(TemporaryFullTunnelPolicy.valid("one","one",100,200,200))
        assertFalse(TemporaryFullTunnelPolicy.valid("one","two",100,200,150))
        assertFalse(TemporaryFullTunnelPolicy.valid("one","one",100,0,1))
        assertTrue(TemporaryFullTunnelPolicy.valid("one","one",100,0,100000))
    }
    @Test fun `new preferences survive backup while old files use safe defaults`() {
        val backup=ApplicationBackup(profiles=emptyList(),mode="turn",autoCaptcha=true,autoRecovery=true,
            savedDNSServers=emptyList(),splitTunneling=saved,amneziaSplitEnabled=true,fullTunnelMinutes=0)
        val restored=ApplicationBackup.decode(backup.encoded())
        assertTrue(restored.amneziaSplitEnabled);assertEquals(0,restored.fullTunnelMinutes)
        val legacy=com.google.gson.JsonParser.parseString(backup.encoded()).asJsonObject
        legacy.remove("amneziaSplitEnabled");legacy.remove("fullTunnelMinutes")
        val old=ApplicationBackup.decode(legacy.toString())
        assertFalse(old.amneziaSplitEnabled);assertEquals(15,old.fullTunnelMinutes)
        legacy.addProperty("fullTunnelMinutes",-1)
        assertThrows(Exception::class.java) {ApplicationBackup.decode(legacy.toString())}
    }
}
