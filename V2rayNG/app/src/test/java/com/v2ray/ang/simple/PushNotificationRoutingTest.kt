package com.v2ray.ang.simple

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.v2ray.ang.dto.V2rayConfig
import org.junit.Assert.*
import org.junit.Test

class PushNotificationRoutingTest {
    private fun config()=V2rayConfig(log=V2rayConfig.LogBean(loglevel="none"),
        inbounds=arrayListOf(V2rayConfig.InboundBean(tag="socks",protocol="socks",port=10808)),
        outbounds=arrayListOf(V2rayConfig.OutboundBean(tag="proxy",protocol="socks",settings=V2rayConfig.OutboundBean.OutSettingsBean(
            servers=listOf(V2rayConfig.OutboundBean.OutSettingsBean.ServersBean(address="127.0.0.1",port=9004)))),V2rayConfig.OutboundBean(tag="dns-out",protocol="dns")),
        dns=V2rayConfig.DnsBean(servers=arrayListOf("https://1.1.1.1/dns-query")),
        routing=V2rayConfig.RoutingBean("AsIs",rules=arrayListOf()))

    @Test fun `push overlays all modes but does not rewrite lists or admit disabled IPv6`() {
        for(mode in SplitTunnelSettings.MODES) {
            val split=SplitTunnelSettings(mode,bypassEntries=SplitTunnelImport.manual("17.0.0.0/8"),
                tunnelEntries=SplitTunnelImport.manual("203.0.113.0/24"),pushNotifications=PushNotificationSettings(true,true))
            val saved=Gson().toJson(split)
            val config=config()
            SplitTunnelRouting.apply(config,split,19085,false)
            val rules=config.routing.rules
            val apple=rules.indexOfFirst { it.ip==PushNotificationRouting.appleNetworks }
            assertTrue(apple>=0)
            assertTrue(rules.indexOfFirst { it.ip==listOf("::/0") }<apple)
            if(mode!=SplitTunnelSettings.DISABLED) assertTrue(apple<rules.indexOfFirst { it.network=="tcp,udp" })
            assertEquals("443,5223",rules[apple].port)
            assertEquals("proxy",rules[apple].outboundTag)
            assertEquals("tcp",rules[apple].network)
            assertTrue(rules.single { it.port=="5228-5230" }.inboundTag!!.contains("tun"))
            assertEquals("proxy",rules.single { it.inboundTag==listOf(SplitTunnelRouting.HEALTH_TAG) }.outboundTag)
            val dns=config.dns!!.servers!!.first() as V2rayConfig.DnsBean.ServersBean
            assertEquals("simple-dns-push",dns.tag); assertEquals(true,dns.skipFallback)
            assertEquals("UseIPv4",config.dns!!.queryStrategy)
            assertEquals(true,config.inbounds.first { it.tag=="socks" }.sniffing?.enabled)
            assertEquals(true,config.inbounds.first { it.tag=="socks" }.sniffing?.routeOnly)
            assertNull(config.outbounds.first { it.tag=="proxy" }.settings!!.servers!!.single().users)
            assertEquals(saved,Gson().toJson(split))
            assertEquals(split,Gson().fromJson(saved,SplitTunnelSettings::class.java))
            assertNotEquals(RoutingPolicySnapshot.create(split,false).policySHA256,
                RoutingPolicySnapshot.create(split.copy(pushNotifications=null),false).policySHA256)
            assertEquals(RoutingPolicySnapshot.create(split,false).networkSHA256,
                RoutingPolicySnapshot.create(split.copy(pushNotifications=null),false).networkSHA256)
        }
    }
    @Test fun `older settings stay off and switches are independent`() {
        val old=Gson().fromJson("""{"mode":"disabled","bypassEntries":[],"tunnelEntries":[]}""",SplitTunnelSettings::class.java)
        assertNull(old.pushNotifications)
        assertTrue(PushNotificationRouting.rules(PushNotificationSettings(),listOf("tun")).isEmpty())
        assertFalse(PushNotificationRouting.domains(PushNotificationSettings(apple=true)).contains("full:mtalk.google.com"))
        assertFalse(PushNotificationRouting.domains(PushNotificationSettings(google=true)).contains("domain:push.apple.com"))
    }
    @Test fun `signed panel policy accepts flags and rejects coerced types`() {
        val f=PanelFixture()
        assertNull(f.packet().settings!!.splitTunneling.pushNotifications)
        val payload=f.payload()
        val split=payload.getAsJsonObject("settings").getAsJsonObject("splitTunneling")
        split.add("pushNotifications",JsonParser.parseString("""{"apple":true,"google":false}"""))
        assertEquals(PushNotificationSettings(apple=true),f.packet(payload).settings!!.splitTunneling.pushNotifications)
        split.add("pushNotifications",JsonParser.parseString("""{"apple":1,"google":false}"""))
        f.rejects { f.packet(payload) }
    }
    @Test fun `export effective configurations for packet engine validation`() {
        val folder=java.io.File("build/reports/push-routing").apply { mkdirs() }
        for(mode in SplitTunnelSettings.MODES) for(ipv6 in listOf(false,true)) {
            val config=config()
            val split=SplitTunnelSettings(mode,bypassEntries=SplitTunnelImport.manual("17.0.0.0/8"),
                tunnelEntries=SplitTunnelImport.manual("8.8.8.0/24"),pushNotifications=PushNotificationSettings(true,true))
            SplitTunnelRouting.apply(config,split,19085,false,ipv6Enabled=ipv6)
            java.io.File(folder,"$mode-$ipv6.json").writeText(Gson().toJson(config))
        }
    }
}
