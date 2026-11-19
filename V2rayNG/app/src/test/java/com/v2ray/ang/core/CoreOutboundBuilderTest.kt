package com.v2ray.ang.core

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.V2rayConfig.OutboundBean.StreamSettingsBean
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class CoreOutboundBuilderTest {
    @Test
    fun `global TLS fragmentation is never applied to xhttp`() {
        val profile = ProfileItem.create(EConfigType.VLESS).apply {
            network = "xhttp"
            security = "reality"
        }
        val streamSettings = com.v2ray.ang.dto.V2rayConfig.OutboundBean.StreamSettingsBean(
            network = "xhttp",
            security = "reality",
        )

        assertEquals(
            false,
            CoreOutboundBuilder.shouldApplyGlobalFragment(streamSettings, profile),
        )
    }

    @Test
    fun `grpc transport keeps service authority and multi mode`() {
        val profile = ProfileItem.create(EConfigType.VLESS).apply {
            network = NetworkType.GRPC.type
            mode = "multi"
            serviceName = "edge-tunnel"
            authority = "front.example"
        }
        val streamSettings = StreamSettingsBean()

        CoreOutboundBuilder.populateTransportSettings(streamSettings, profile)

        assertEquals(NetworkType.GRPC.type, streamSettings.network)
        assertEquals("edge-tunnel", streamSettings.grpcSettings?.serviceName)
        assertEquals("front.example", streamSettings.grpcSettings?.authority)
        assertEquals(true, streamSettings.grpcSettings?.multiMode)
    }

    @Test
    fun `hysteria2 profile produces version two client and tuned quic transport`() {
        val profile = ProfileItem.create(EConfigType.HYSTERIA2).apply {
            server = "203.0.113.10"
            serverPort = "443"
            password = "secret"
            network = NetworkType.HYSTERIA.type
            security = "tls"
            sni = "front.example"
            alpn = "h3"
            obfsPassword = "obfs-secret"
            portHopping = "2000-3000"
            portHoppingInterval = "20-40"
            bandwidthUp = "20 Mbps"
            bandwidthDown = "100 Mbps"
            finalMask = "{}" // Avoid applying global fragment settings in this unit test.
        }

        val outbound = CoreOutboundBuilder.toOutboundHysteria2(profile)
        assertNotNull(outbound)
        assertEquals("hysteria", outbound?.protocol)
        assertEquals("203.0.113.10", outbound?.settings?.address)
        assertEquals(443, outbound?.settings?.port)
        assertEquals(2, outbound?.settings?.version)
        assertEquals(NetworkType.HYSTERIA.type, outbound?.streamSettings?.network)
        assertEquals(2, outbound?.streamSettings?.hysteriaSettings?.version)
        assertEquals("secret", outbound?.streamSettings?.hysteriaSettings?.auth)
        assertEquals("front.example", outbound?.streamSettings?.tlsSettings?.serverName)
        assertEquals(listOf("h3"), outbound?.streamSettings?.tlsSettings?.alpn)

        val quic = CoreOutboundBuilder.buildHysteriaFinalMask(profile).quicParams
        assertEquals("20 Mbps", quic?.brutalUp)
        assertEquals("100 Mbps", quic?.brutalDown)
        assertEquals("brutal", quic?.congestion)
        assertEquals("2000-3000", quic?.udpHop?.ports)
        assertEquals("20-40", quic?.udpHop?.interval)
        assertEquals("salamander", CoreOutboundBuilder.buildHysteriaFinalMask(profile).udp?.first()?.type)
    }
}
