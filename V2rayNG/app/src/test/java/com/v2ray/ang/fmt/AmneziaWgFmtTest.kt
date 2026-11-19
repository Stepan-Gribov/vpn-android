package com.v2ray.ang.fmt

import com.v2ray.ang.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmneziaWgFmtTest {
    private val exampleConfig = """
        # Synthetic test-only keys and documentation IP; not a usable VPN profile.
        [Interface]
        PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
        Address = 10.0.0.2/32
        DNS = 1.1.1.1
        Jc = 4
        Jmin = 16
        Jmax = 64

        [Peer]
        PublicKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
        AllowedIPs = 0.0.0.0/0
        Endpoint = 198.51.100.1:51820
    """.trimIndent()

    @Test
    fun `Amnezia settings select the native protocol and survive serialization`() {
        assertTrue(AmneziaWgFmt.hasAmneziaSettings(exampleConfig))
        val profile = AmneziaWgFmt.parseConfig(exampleConfig, "Test route.conf")
        assertEquals(EConfigType.AMNEZIAWG, profile.configType)
        assertEquals("Test route", profile.remarks)
        assertEquals("198.51.100.1", profile.server)
        assertEquals("51820", profile.serverPort)
        assertEquals(1280, profile.mtu)

        val userspaceConfig = AmneziaWgFmt.parseConfig(profile.amneziaWgConfig.orEmpty())
            .toAwgUserspaceString()
        assertTrue(userspaceConfig.contains("jc=4"))
        assertTrue(userspaceConfig.contains("jmin=16"))
        assertTrue(userspaceConfig.contains("jmax=64"))
    }

    @Test
    fun `plain WireGuard settings are not misclassified`() {
        assertFalse(AmneziaWgFmt.hasAmneziaSettings(exampleConfig.replace("Jc = 4", "")
            .replace("Jmin = 16", "").replace("Jmax = 64", "")))
    }
}
