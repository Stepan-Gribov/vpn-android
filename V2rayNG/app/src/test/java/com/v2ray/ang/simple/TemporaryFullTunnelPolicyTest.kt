package com.v2ray.ang.simple

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class TemporaryFullTunnelPolicyTest {
    private val saved=SplitTunnelSettings(SplitTunnelSettings.TUNNEL,
        bypassEntries=SplitTunnelImport.manual("direct.test 8.8.8.0/24"),
        tunnelEntries=SplitTunnelImport.manual("proxy.test 9.9.9.0/24"),
        pushNotifications=PushNotificationSettings(apple=true,google=true))
    @Test fun `TURN always supports split Amnezia follows its switch and DNS remains full`() {
        for((mode, amneziaSplit, expected) in listOf(Triple("turn",false,true),Triple("turn",true,true),
            Triple("amnezia",false,false),Triple("amnezia",true,true),Triple("dns",true,false))) {
            assertEquals(expected,TemporaryFullTunnelPolicy.supported(mode,amneziaSplit))
            assertEquals(if(expected) saved.mode else SplitTunnelSettings.DISABLED,
                TemporaryFullTunnelPolicy.split(saved,mode,amneziaSplit,false).mode)
            assertEquals(if(expected) listOf("com.example.app") else emptyList<String>(),
                TemporaryFullTunnelPolicy.applications(listOf("com.example.app"),mode,amneziaSplit,false))
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
        val legacy=JsonParser.parseString(backup.encoded()).asJsonObject
        legacy.remove("amneziaSplitEnabled");legacy.remove("fullTunnelMinutes")
        val old=ApplicationBackup.decode(legacy.toString())
        assertTrue(old.amneziaSplitEnabled);assertEquals(15,old.fullTunnelMinutes)
        legacy.addProperty("amneziaSplitEnabled", false)
        assertFalse(ApplicationBackup.decode(legacy.toString()).amneziaSplitEnabled)
        legacy.addProperty("fullTunnelMinutes",-1)
        assertThrows(Exception::class.java) {ApplicationBackup.decode(legacy.toString())}
    }

    @Test fun `new and legacy app state enable Amnezia split unless a value was saved`() {
        val gson = Gson()
        assertTrue(SimpleState().amneziaSplitEnabled)
        assertTrue(gson.fromJson("{}", SimpleState::class.java).amneziaSplitEnabled)
        for (enabled in listOf(false, true)) {
            val state = gson.fromJson("""{"amneziaSplitEnabled":$enabled}""", SimpleState::class.java)
            assertEquals(enabled, state.amneziaSplitEnabled)
        }
    }
}
