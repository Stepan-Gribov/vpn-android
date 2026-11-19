package com.v2ray.ang.fmt

import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Hysteria2FmtTest {
    @Test
    fun `hysteria2 links parse and preserve transport options`() {
        val profile = Hysteria2Fmt.parse(
            "hysteria2://my%40secret@example.com:443?security=tls&sni=front.example&alpn=h3" +
                "&obfs=salamander&obfs-password=token&mport=2000-3000&mportHopInt=20&pinSHA256=abcd#home",
        )

        assertEquals(EConfigType.HYSTERIA2, profile.configType)
        assertEquals("my@secret", profile.password)
        assertEquals(NetworkType.HYSTERIA.type, profile.network)
        assertEquals("token", profile.obfsPassword)
        assertEquals("2000-3000", profile.portHopping)
        assertEquals("20", profile.portHoppingInterval)
        assertEquals("abcd", profile.pinnedCA256)

        val link = Hysteria2Fmt.toUri(profile)
        assertTrue(link.contains("my%40secret@example.com:443"))
        assertTrue(link.contains("obfs=salamander"))
        assertTrue(link.contains("mport=2000-3000"))
        assertTrue(link.contains("pinSHA256=abcd"))
    }
}
