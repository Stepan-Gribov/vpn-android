package com.v2ray.ang.core

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.Assert.*
import org.junit.Test

class DnsTunnelSettingsTest {
    private val key = "ab".repeat(32)

    @Test fun normalizesPastedDomainUrlAndLiteralResolverAddresses() {
        val settings = DnsTunnelSettings.fromFields("https://t.example.com/", key.uppercase(),
            "1.1.1.1\n8.8.8.8:5353, [2001:4860:4860::8888]:53; 1.1.1.1:53")
        assertEquals("t.example.com", settings.domain)
        assertEquals(key, settings.publicKey)
        assertEquals(listOf("1.1.1.1:53", "8.8.8.8:5353", "[2001:4860:4860::8888]:53"), settings.resolvers)
        assertEquals(settings, DnsTunnelSettings.fromJson(settings.toJson()))
    }

    @Test fun rejectsWebsitePathsInvalidKeysAndNonliteralOrUnboundedResolvers() {
        for (domain in listOf("https://t.example.com/config.json", "https://user@t.example.com", "https://t.example.com/?key=x", "-t.example.com", "t..example.com")) {
            assertThrows(IllegalArgumentException::class.java) { DnsTunnelSettings.fromFields(domain, key, "1.1.1.1") }
        }
        for (badKey in listOf("", "ab".repeat(31), "zz".repeat(32))) {
            assertThrows(IllegalArgumentException::class.java) { DnsTunnelSettings.fromFields("t.example.com", badKey, "1.1.1.1") }
        }
        for (dns in listOf("", "dns.example.com:53", "1.1.1.1:0", "999.1.1.1", "1.1.1.1:70000", "1.1.1.1 8.8.8.8 9.9.9.9 1.0.0.1 8.8.4.4")) {
            assertThrows(IllegalArgumentException::class.java) { DnsTunnelSettings.fromFields("t.example.com", key, dns) }
        }
    }

    @Test fun dnsSetupDoesNotAcceptOtherTransportsOrSilentlyDropExtraRoutes() {
        val raw = DnsTunnelSettings.fromFields("t.example.com", key, "1.1.1.1").toJson()
        assertThrows(IllegalArgumentException::class.java) { DnsTunnelSettings.fromJson(raw.replace("dnstt", "https")) }
        assertThrows(IllegalArgumentException::class.java) { DnsTunnelSettings.fromJson(raw.replace("\"version\":1", "\"version\":2")) }
        assertThrows(IllegalArgumentException::class.java) { DnsTunnelSettings.fromJson(" ".repeat(65_537)) }
        val root = com.google.gson.JsonParser.parseString(raw).asJsonObject
        root.getAsJsonArray("profiles").add(root.getAsJsonArray("profiles")[0].deepCopy())
        assertThrows(IllegalArgumentException::class.java) { DnsTunnelSettings.fromJson(root.toString()) }
    }

    @Test fun connectionReusesIdentityWithoutCopyingTheOldTransportOrSubscriptionLifecycle() {
        val source = ProfileItem(configType = EConfigType.VLESS, subscriptionId = "paid-subscription",
            password = "existing-identity", server = "vpn.example.com", serverPort = "443",
            network = "xhttp", security = "reality", flow = "xtls-rprx-vision", publicKey = "old-key")
        val dns = DnsTunnelProfiles.connection(source, "My DNS")
        assertTrue(DnsTunnelProfiles.isDns(dns))
        assertEquals(source.password, dns.password)
        assertEquals("", dns.subscriptionId)
        assertEquals("tcp", dns.network)
        assertEquals("none", dns.security)
        assertNull(dns.flow)
        assertNull(dns.publicKey)
        assertEquals("reality", source.security)
        assertEquals("paid-subscription", DnsTunnelProfiles.connection(source, "DNS", source.subscriptionId).subscriptionId)
        assertFalse(DnsTunnelProfiles.canSupplyIdentity(source.copy(password = "")))
    }
}
