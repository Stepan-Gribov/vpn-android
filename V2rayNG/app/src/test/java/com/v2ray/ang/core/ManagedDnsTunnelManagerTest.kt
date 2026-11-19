package com.v2ray.ang.core

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDnsTunnelManagerTest {
    private val testDomain = "dns.example.invalid"
    private val testPublicKey = "0123456789abcdef".repeat(4)

    @Test
    fun identifiesOnlyManagedLocalSocksProfile() {
        val dns = ProfileItem.create(EConfigType.SOCKS).apply {
            server = AppConfig.DNS_TUNNEL_LOCAL_ADDRESS
            serverPort = AppConfig.DNS_TUNNEL_LOCAL_PORT
        }

        assertTrue(ManagedDnsTunnelManager.isManagedDnsTunnelProfile(dns))
        assertTrue(ManagedDnsTunnelManager.isManagedDnsTunnelProfile(dns.copy(server = "localhost")))
        assertFalse(ManagedDnsTunnelManager.isManagedDnsTunnelProfile(dns.copy(serverPort = "1080")))
        assertFalse(ManagedDnsTunnelManager.isManagedDnsTunnelProfile(dns.copy(configType = EConfigType.VLESS)))
    }

    @Test
    fun keepsSubscriptionDnsAndManagedDnsRoutesDistinct() {
        val subscriptionDns = DnsTunnelProfiles.connection(
            ProfileItem.create(EConfigType.VLESS).apply { password = "subscriber-identity" },
            "Subscription DNS",
        )
        val managedDns = ProfileItem.create(EConfigType.SOCKS).apply {
            server = AppConfig.DNS_TUNNEL_LOCAL_ADDRESS
            serverPort = AppConfig.DNS_TUNNEL_LOCAL_PORT
        }

        assertTrue(DnsTunnelProfiles.isDns(subscriptionDns))
        assertFalse(ManagedDnsTunnelManager.isManagedDnsTunnelProfile(subscriptionDns))
        assertTrue(ManagedDnsTunnelManager.isManagedDnsTunnelProfile(managedDns))
        assertFalse(DnsTunnelProfiles.isDns(managedDns))
        assertFalse(VkTurnManager.isTurnProfile(managedDns))
    }

    @Test
    fun normalizesWorkingDomainResolverAndPublicKey() {
        assertEquals(
            testDomain,
            ManagedDnsTunnelManager.normalizeDomain(" ${testDomain.uppercase()}. "),
        )
        assertEquals("1.1.1.1:53", ManagedDnsTunnelManager.normalizeResolver("1.1.1.1"))
        assertEquals("dns.example:5353", ManagedDnsTunnelManager.normalizeResolver("dns.example:5353"))
        assertEquals("[2606:4700:4700::1111]:53", ManagedDnsTunnelManager.normalizeResolver("2606:4700:4700::1111"))
        assertEquals(
            testPublicKey,
            ManagedDnsTunnelManager.normalizePublicKey(testPublicKey.uppercase()),
        )
    }

    @Test
    fun rejectsMalformedSettings() {
        assertNull(ManagedDnsTunnelManager.normalizeDomain("single-label"))
        assertNull(ManagedDnsTunnelManager.normalizeDomain("-bad.example"))
        assertNull(ManagedDnsTunnelManager.normalizeResolver("http://1.1.1.1/dns-query"))
        assertNull(ManagedDnsTunnelManager.normalizeResolver("https://user:password@dns.example/dns-query"))
        assertNull(ManagedDnsTunnelManager.normalizeResolver("https://dns.example:70000/dns-query"))
        assertNull(ManagedDnsTunnelManager.normalizeResolver("https://dns.example/dns-query#fragment"))
        assertNull(ManagedDnsTunnelManager.normalizeResolver("1.1.1.1:70000"))
        assertNull(ManagedDnsTunnelManager.normalizePublicKey("abcd"))
    }

    @Test
    fun supportsHttpsForDnsttButRejectsItForSlipstream() {
        val resolver = "https://1.1.1.1/dns-query"
        assertEquals(resolver, ManagedDnsTunnelManager.normalizeResolver("https://1.1.1.1"))
        assertNull(ManagedDnsTunnelManager.normalizeResolver(resolver, ManagedDnsTunnelTransport.SLIPSTREAM))
        val command = ManagedDnsTunnelManager.dnsttCommand(
            "/native/libdnstt.so", resolver, testPublicKey, testDomain,
        )
        assertEquals("-doh", command[1])
        assertEquals(resolver, command[2])
        assertFalse(command.contains("-udp"))
        assertNull(ManagedDnsTunnelManager.resolverEndpoint(resolver))
        assertEquals(
            ManagedDnsTunnelManager.ResolverEndpoint("8.8.8.8", 53),
            ManagedDnsTunnelManager.resolverEndpoint("8.8.8.8:53"),
        )
    }

    @Test
    fun buildsUdpHelperCommandForManagedLocalPort() {
        val command = ManagedDnsTunnelManager.dnsttCommand(
            helperPath = "/native/libdnstt.so",
            resolver = "1.1.1.1",
            publicKey = testPublicKey,
            domain = testDomain,
        )

        assertEquals("/native/libdnstt.so", command.first())
        assertEquals("1.1.1.1:53", command[command.indexOf("-udp") + 1])
        assertEquals(
            testPublicKey,
            command[command.indexOf("-pubkey") + 1],
        )
        assertEquals(testDomain, command[command.size - 2])
        assertEquals("127.0.0.1:7100", command.last())
    }

    @Test
    fun configuresPoolSizeAndOneSharedQueryBudget() {
        for (count in ManagedDnsTunnelManager.SESSION_COUNTS) {
            val command = ManagedDnsTunnelManager.dnsttCommand(
                "/native/libdnstt.so", "9.9.9.9", testPublicKey, testDomain,
                sessionCount = count,
            )
            assertEquals(count.toString(), command[command.indexOf("-sessions") + 1])
            assertEquals("800", command[command.indexOf("-max-qps") + 1])
        }
        assertEquals(1, ManagedDnsTunnelManager.normalizeSessionCount(0))
        assertEquals(1, ManagedDnsTunnelManager.normalizeSessionCount(32))
        assertEquals(4, ManagedDnsTunnelManager.normalizeSessionCount(4))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsupportedPoolSize() {
        ManagedDnsTunnelManager.dnsttCommand(
            "/native/libdnstt.so", "9.9.9.9", testPublicKey, testDomain, sessionCount = 32,
        )
    }

    @Test
    fun buildsSlipstreamCommandWithAdvancedOptions() {
        val command = ManagedDnsTunnelManager.slipstreamCommand(
            helperPath = "/native/libslipstream_client.so",
            settings = ManagedDnsTunnelSettings(
                name = "DNS Tunnel",
                transport = ManagedDnsTunnelTransport.SLIPSTREAM,
                tunnelType = ManagedDnsTunnelType.SOCKS5,
                domain = testDomain,
                resolver = "1.1.1.1",
                publicKey = "",
                congestionControl = "bbr",
                keepAliveInterval = 750,
                gsoEnabled = true,
                automaticResolverCapture = true,
                sshUsername = "",
                sshPassword = "",
                sshPrivateKey = "",
            ),
        )

        assertEquals("/native/libslipstream_client.so", command.first())
        assertEquals("1.1.1.1:53", command[command.indexOf("--resolver") + 1])
        assertEquals("bbr", command[command.indexOf("--congestion-control") + 1])
        assertEquals("750", command[command.indexOf("--keep-alive-interval") + 1])
        assertTrue(command.contains("--gso"))
    }
}
