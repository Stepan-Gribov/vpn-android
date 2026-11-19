package com.v2ray.ang.simple

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.V2rayConfig
import org.junit.Assert.*
import org.junit.Test

class SplitTunnelingTest {
    @Test fun `JSON merges domain networks and canonicalizes CIDR`() {
        val entries = SplitTunnelImport.json("""{"Example.COM":["203.0.113.7/24","2001:db8::1/32"],"example.com":["203.0.113.0/24","198.51.100.1"]}""")
        assertEquals(listOf(SplitTunnelEntry("example.com", listOf("198.51.100.1", "2001:db8::/32", "203.0.113.0/24"))), entries)
    }
    @Test fun `named groups are IP networks rather than invented domains`() {
        val entries = SplitTunnelImport.json("""{"copilot":["203.0.113.0/24"],"jetbrains@cdn":["198.51.100.0/24"]}""")
        val split = SplitTunnelSettings(mode = SplitTunnelSettings.TUNNEL, tunnelEntries = entries).validated()
        assertTrue(entries.all { it.isNetworkGroup })
        assertTrue(split.domains.isEmpty())
        assertEquals(listOf("198.51.100.0/24", "203.0.113.0/24"), split.networks)
    }
    @Test fun `JSON group labels containing at and dot retain every CIDR`() {
        val entries=SplitTunnelImport.json("""{"jetbrains@grazie.ai":["203.0.113.1/24","198.51.100.0/24"]}""")
        assertTrue(entries.single().isNetworkGroup)
        val split=SplitTunnelSettings(SplitTunnelSettings.TUNNEL,tunnelEntries=entries)
        assertTrue(split.domains.isEmpty())
        assertEquals(listOf("198.51.100.0/24","203.0.113.0/24"),split.networks)
    }
    @Test fun `manual addresses URLs and IPv6 normalize without resolving domains`() {
        val entries = SplitTunnelImport.manual("https://Example.com/path, *.example.com; 203.0.113.9 2001:db8::9/32 192.168.1.7/24")
        assertEquals(listOf("192.168.1.0/24", "2001:db8::/32", "203.0.113.9", "example.com"), entries.map { it.address })
    }
    @Test fun `policies keep separate lists through switching and disabling`() {
        val original = SplitTunnelSettings(SplitTunnelSettings.BYPASS,
            bypassEntries = SplitTunnelImport.manual("direct.test"), tunnelEntries = SplitTunnelImport.manual("proxy.test"))
        assertEquals(listOf("direct.test"), original.activeEntries.map { it.address })
        val opposite = original.copy(mode = SplitTunnelSettings.TUNNEL)
        assertEquals(listOf("proxy.test"), opposite.activeEntries.map { it.address })
        assertEquals(original.bypassEntries, opposite.bypassEntries)
        assertEquals(original.bypassEntries, opposite.copy(mode = SplitTunnelSettings.DISABLED).bypassEntries)
        assertTrue(opposite.copy(mode = SplitTunnelSettings.DISABLED).activeEntries.isEmpty())
    }
    @Test fun `invalid lists fail before returning a partial result`() {
        listOf("999.1.2.3", "203.0.113.1/33", "2001:db8::1/129", "bad..test", "example.com:443", "https://user:pass@example.com").forEach {
            assertThrows(IllegalArgumentException::class.java) { SplitTunnelImport.manual("valid.test $it") }
        }
        assertThrows(IllegalArgumentException::class.java) { SplitTunnelImport.json("""{"example.com":["another.test"]}""") }
        assertThrows(IllegalArgumentException::class.java) { SplitTunnelImport.json("[]") }
        assertThrows(IllegalArgumentException::class.java) { SplitTunnelImport.json(" ".repeat(SplitTunnelImport.MAXIMUM_SIZE + 1)) }
    }
    private fun config() = V2rayConfig(
        log = V2rayConfig.LogBean(),
        inbounds = arrayListOf(V2rayConfig.InboundBean("socks", 10808, "socks",sniffing=V2rayConfig.InboundBean.SniffingBean(true,arrayListOf("http","tls")))),
        outbounds = arrayListOf(V2rayConfig.OutboundBean(protocol = "socks"),
            V2rayConfig.OutboundBean(tag = AppConfig.TAG_DIRECT, protocol = "freedom"),
            V2rayConfig.OutboundBean(tag = AppConfig.TAG_BLOCKED, protocol = "blackhole"),
            V2rayConfig.OutboundBean(tag = "dns-out", protocol = "dns")),
        dns = V2rayConfig.DnsBean(servers = arrayListOf("https://1.1.1.1/dns-query")),
        routing = V2rayConfig.RoutingBean("AsIs", rules = arrayListOf()),
    )
    @Test fun `full DNS tunnel preserves resolved app IP instead of resolving SNI remotely again`() {
        val config=config()
        SplitTunnelRouting.apply(config,SplitTunnelSettings(),19085,managedDns=true)
        assertEquals(true,config.inbounds.first { it.tag=="socks" }.sniffing?.routeOnly)
        assertEquals("UseIPv4",config.dns?.queryStrategy)
    }
    @Test fun `opposite policies route domains OR networks and keep health probes in proxy`() {
        for (mode in listOf(SplitTunnelSettings.BYPASS, SplitTunnelSettings.TUNNEL)) {
            val entries = SplitTunnelImport.manual("selected.test 203.0.113.0/24")
            val split = SplitTunnelSettings(mode = mode).withEntries(mode, entries)
            val config = config()
            SplitTunnelRouting.apply(config, split, 19085, managedDns = false)
            val expected = if (mode == SplitTunnelSettings.BYPASS) AppConfig.TAG_DIRECT else AppConfig.TAG_PROXY
            val domain = config.routing.rules.single { it.domain == listOf("domain:selected.test") }
            val ip = config.routing.rules.single { it.ip == listOf("203.0.113.0/24") }
            assertNull(domain.ip); assertNull(ip.domain)
            assertTrue(config.routing.rules.indexOf(domain) < config.routing.rules.indexOfFirst { it.ip != null && it.ip!=listOf("::/0") })
            assertEquals(expected, domain.outboundTag); assertEquals(expected, ip.outboundTag)
            assertEquals(AppConfig.TAG_PROXY, config.routing.rules.single { it.inboundTag == listOf(SplitTunnelRouting.HEALTH_TAG) }.outboundTag)
            assertEquals(19085, config.inbounds.single { it.tag == SplitTunnelRouting.HEALTH_TAG }.port)
            assertEquals("AsIs", config.routing.domainStrategy)
            assertEquals(true,config.inbounds.first { it.tag=="socks" }.sniffing?.routeOnly)
            assertEquals("UseIPv4", config.dns?.queryStrategy)
            assertTrue(config.routing.rules.any { it.ip?.contains("192.168.0.0/16") == true && it.outboundTag == AppConfig.TAG_DIRECT })
            val fallback = config.routing.rules.single { it.network == "tcp,udp" }
            assertEquals(if (mode == SplitTunnelSettings.BYPASS) AppConfig.TAG_PROXY else AppConfig.TAG_DIRECT, fallback.outboundTag)
            assertEquals(true, config.dns?.disableFallbackIfMatch)
            assertTrue(config.dns!!.servers!!.any { (it as V2rayConfig.DnsBean.ServersBean).tag=="simple-dns-proxy" })
        }
    }
    @Test fun `DNS tunnel uses its TCP capable resolver and health probe cannot match selected apps`() {
        val config = config()
        val dns = config.dns
        val split = SplitTunnelSettings(SplitTunnelSettings.TUNNEL, tunnelEntries = SplitTunnelImport.manual("selected.test"))
        SplitTunnelRouting.apply(config, split, 19085, managedDns = true)
        assertEquals((dns?.servers?.first() as String),(config.dns?.servers?.last() as V2rayConfig.DnsBean.ServersBean).address)
        assertEquals(AppConfig.TAG_PROXY,config.routing.rules.first { it.inboundTag==listOf("simple-dns-proxy") }.outboundTag)
        assertTrue(config.routing.rules.filter { it.network == "tcp,udp" || it.domain != null || it.ip != null }
            .none { it.inboundTag?.contains(SplitTunnelRouting.HEALTH_TAG) == true })
    }
    @Test fun `JSON domain labels cannot override IP routing for any transport`() {
        for(managedDns in listOf(false,true)) {
            val split=SplitTunnelSettings(SplitTunnelSettings.TUNNEL,
                tunnelEntries=SplitTunnelImport.json("""{"selected.test":["203.0.113.0/24"]}"""))
            val config=config()
            SplitTunnelRouting.apply(config,split,19085,managedDns,"127.0.0.1:12345")
            assertTrue(config.routing.rules.none { it.domain?.contains("domain:selected.test")==true })
            assertEquals(listOf("domain:selected.test"),split.dnsDomains)
            assertEquals(AppConfig.TAG_PROXY,config.routing.rules.single { it.ip==split.networks }.outboundTag)
            val servers=config.dns!!.servers!!.map { it as V2rayConfig.DnsBean.ServersBean }
            assertEquals("127.0.0.1",servers.first().address)
            assertEquals(12345,servers.first().port)
            assertNull(servers.first().domains)
            assertEquals("simple-dns-direct",servers.first().tag)
            assertEquals(split.dnsDomains,servers.last().domains)
            assertEquals("simple-dns-proxy",servers.last().tag)
        }
    }
    @Test fun `bypass DNS has no dependency on the unavailable proxy`() {
        val config=config()
        val split=SplitTunnelSettings(SplitTunnelSettings.BYPASS,bypassEntries=SplitTunnelImport.manual("direct.test"))
        SplitTunnelRouting.apply(config,split,19085,false,"127.0.0.1:12345")
        val local=config.dns!!.servers!!.map { it as V2rayConfig.DnsBean.ServersBean }.single { it.tag=="simple-dns-direct" }
        assertEquals(listOf("domain:direct.test"),local.domains)
        assertEquals(true,config.dns!!.disableFallbackIfMatch)
        assertEquals(AppConfig.TAG_DIRECT,config.routing.rules.first { it.inboundTag==listOf(local.tag) }.outboundTag)
    }
    @Test fun `staged UDP endpoints are normalized to TCP for every tunnel`() {
        for(input in listOf("udp://1.1.1.1:53","1.1.1.1:53")) {
            val config=config().apply { dns=V2rayConfig.DnsBean(servers=arrayListOf(input)) }
            SplitTunnelRouting.apply(config,SplitTunnelSettings(SplitTunnelSettings.TUNNEL),19085,false,"127.0.0.1:12345")
            val remote=config.dns!!.servers!!.last() as V2rayConfig.DnsBean.ServersBean
            assertEquals("tcp://1.1.1.1:53",remote.address)
        }
    }
    @Test fun `IPv6 admission follows explicit setting with no HTTP blackhole`() {
        for(enabled in listOf(false,true)) for(mode in listOf(SplitTunnelSettings.TUNNEL,SplitTunnelSettings.BYPASS)) {
            val split=SplitTunnelSettings(mode).withEntries(mode,SplitTunnelImport.manual("selected.test 203.0.113.0/24 2001:db8::/32"))
            val config=config()
            config.outbounds.single { it.protocol=="blackhole" }.settings=V2rayConfig.OutboundBean.OutSettingsBean(
                response=V2rayConfig.OutboundBean.OutSettingsBean.Response("http"))
            SplitTunnelRouting.apply(config,split,0,false,ipv6Enabled=enabled)
            assertEquals(if(enabled) "UseIP" else "UseIPv4",config.dns!!.queryStrategy)
            assertEquals("none",config.outbounds.single { it.protocol=="blackhole" }.settings!!.response!!.type)
            val blocked=config.routing.rules.indexOfFirst { it.ip==listOf("::/0") }
            if(enabled) assertEquals(-1,blocked) else assertTrue(blocked<config.routing.rules.indexOfFirst { it.domain!=null })
            val policy=RoutingPolicySnapshot.capture(config,split,enabled,183)
            assertEquals(1,policy.ipv4Count);assertEquals(1,policy.ipv6Count)
            assertEquals(183L,policy.sourceRevision)
            val mutated=split.copy(mode=if(mode==SplitTunnelSettings.TUNNEL) SplitTunnelSettings.BYPASS else SplitTunnelSettings.TUNNEL)
            assertNotEquals(policy.policySHA256,RoutingPolicySnapshot.create(mutated,enabled).policySHA256)
            config.routing.rules.removeAll { it.ip==split.networks }
            assertThrows(IllegalStateException::class.java) { RoutingPolicySnapshot.capture(config,split,enabled,183) }
        }
    }

}
