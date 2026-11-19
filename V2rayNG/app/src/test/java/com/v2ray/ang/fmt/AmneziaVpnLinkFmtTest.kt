package com.v2ray.ang.fmt

import com.google.gson.JsonObject
import com.v2ray.ang.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.zip.DeflaterOutputStream

class AmneziaVpnLinkFmtTest {
    @Test
    fun `Amnezia VPN export becomes an AWG profile`() {
        val ini = """
            [Interface]
            PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
            Address = 10.0.0.2/32
            DNS = ${'$'}PRIMARY_DNS

            [Peer]
            PublicKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
            AllowedIPs = 0.0.0.0/0
            Endpoint = 198.51.100.1:51820
        """.trimIndent()
        val lastConfig = JsonObject().apply {
            addProperty("config", ini)
            addProperty("Jc", 4)
            addProperty("Jmin", 16)
            addProperty("Jmax", 64)
        }
        val awg = JsonObject().apply { addProperty("last_config", lastConfig.toString()) }
        val container = JsonObject().apply {
            addProperty("container", "amnezia-awg")
            add("awg", awg)
        }
        val root = JsonObject().apply {
            addProperty("description", "Test route")
            addProperty("dns1", "1.1.1.1")
            add("containers", com.google.gson.JsonArray().apply { add(container) })
        }

        val profile = AmneziaVpnLinkFmt.parseJson(root.toString())

        assertNotNull(profile)
        assertEquals(EConfigType.AMNEZIAWG, profile!!.configType)
        assertEquals("Test route", profile.remarks)
        assertEquals("198.51.100.1", profile.server)
        assertTrue(profile.amneziaWgConfig.orEmpty().contains("Jc = 4", ignoreCase = true))
        assertTrue(profile.amneziaWgConfig.orEmpty().contains("DNS = 1.1.1.1", ignoreCase = true))
    }

    @Test
    fun `Qt-compressed VPN payload is decoded`() {
        val json = "{\"containers\":[]}"
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        val compressed = ByteArrayOutputStream().apply {
            DeflaterOutputStream(this).use { it.write(bytes) }
        }.toByteArray()
        val payload = ByteBuffer.allocate(4 + compressed.size).putInt(bytes.size).put(compressed).array()

        assertEquals(json, AmneziaVpnLinkFmt.unpack(payload))
    }
}
