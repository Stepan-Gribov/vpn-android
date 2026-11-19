package com.v2ray.ang.core

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.Assert.*
import org.junit.Test

class DnsTunnelProfilesTest {
    private fun source() = ProfileItem(configType = EConfigType.VLESS,
        server = "127.0.0.1", serverPort = "9000", password = "subscriber-identity",
        security = "none", network = "tcp", remarks = "VK TURN", subscriptionId = "sub-one")

    @Test fun addsSeparateDnsRowWithoutChangingExistingCredentialsOrTransport() {
        val original = source()
        val result = DnsTunnelProfiles.adding(listOf(original), true, "DNS tunnel")
        assertEquals(2, result.size)
        assertEquals("9000", original.serverPort)
        assertEquals("VK TURN", original.remarks)
        val dns = result.last()
        assertTrue(DnsTunnelProfiles.isDns(dns))
        assertFalse(DnsTunnelProfiles.isSource(dns))
        assertEquals(original.password, dns.password)
        assertEquals(original.subscriptionId, dns.subscriptionId)
        assertEquals("DNS tunnel", dns.remarks)
    }

    @Test fun unavailableConfigurationDoesNotAddOrRemoveConnections() {
        val existing = listOf(source())
        assertEquals(existing, DnsTunnelProfiles.adding(existing, false, "DNS"))
        assertEquals(1, existing.size)
    }

    @Test fun automaticConnectionDoesNotInheritTransportOptions() {
        val original = source().copy(flow = "xtls-rprx-vision", headerType = "http",
            host = "old.example", path = "/old", method = "old-encryption", publicKey = "old-key")
        val dns = DnsTunnelProfiles.adding(listOf(original), true, "DNS").last()
        assertEquals(original.password, dns.password)
        assertEquals(original.subscriptionId, dns.subscriptionId)
        assertEquals("none", dns.method)
        assertEquals("none", dns.security)
        assertEquals("tcp", dns.network)
        assertNull(dns.flow)
        assertNull(dns.headerType)
        assertNull(dns.host)
        assertNull(dns.path)
        assertNull(dns.publicKey)
    }

    @Test fun refreshDoesNotDuplicateDnsAndDoesNotConvertDirectConnections() {
        val first = DnsTunnelProfiles.adding(listOf(source()), true, "DNS")
        assertEquals(2, DnsTunnelProfiles.adding(first, true, "DNS").size)
        assertEquals(1, DnsTunnelProfiles.adding(listOf(source().copy(server = "vpn.example")), true, "DNS").size)
        assertEquals(1, DnsTunnelProfiles.adding(listOf(source().copy(security = "tls")), true, "DNS").size)
        assertEquals(1, DnsTunnelProfiles.adding(listOf(source().copy(configType = EConfigType.SOCKS)), true, "DNS").size)
    }

    @Test fun onlyTrustedSubscriptionsGetAutomaticDiscovery() {
        assertTrue(DnsTunnelProfiles.isKindlySubscription("https://vpnapp.example.invalid//sub/test"))
        for (url in listOf("http://panel.example.invalid/sub/test", "https://vpnapp.example.invalid/.evil.example/test",
            "https://user@panel.example.invalid/sub/test", "https://vpnapp.example.invalid/:8080/sub/test")) {
            assertFalse(DnsTunnelProfiles.isKindlySubscription(url))
        }
    }
}
