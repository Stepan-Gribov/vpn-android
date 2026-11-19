package com.v2ray.ang.simple

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Base64
import java.util.zip.DeflaterOutputStream

class AmneziaImporterTest {
    private fun key(byte: Byte) = Base64.getEncoder().encodeToString(ByteArray(32) { byte })
    private val ini = """
        [Interface]
        PrivateKey = ${key(17)}
        Address = 10.80.0.2/32
        DNS = 1.1.1.1
        H1 = 150-200
        S4 = 15
        I1 = <b 0x12345678><r 20>
        HeaderProtectionKey = ${key(34)}
        RandomTrailers = on
        DisableCookies = off
        [Peer]
        PublicKey = ${key(51)}
        Endpoint = 203.0.113.25:47891
        AllowedIPs = 203.0.113.0/24
        PersistentKeepalive = 25
    """.trimIndent()
    private fun share(): String {
        val last = Gson().toJson(mapOf("config" to ini))
        val data = Gson().toJson(mapOf("containers" to listOf(mapOf("container" to "amnezia-awg2", "awg" to mapOf("last_config" to last))), "description" to "Test Amnezia", "defaultContainer" to "amnezia-awg2")).toByteArray()
        val packed = ByteArrayOutputStream().apply { write(ByteBuffer.allocate(4).putInt(data.size).array()) }
        DeflaterOutputStream(packed).use { it.write(data) }
        return "vpn://" + Base64.getUrlEncoder().withoutPadding().encodeToString(packed.toByteArray())
    }
    @Test fun awg3SharePreservesAllSettings() {
        val value = SimpleConfiguration(mode = "amnezia", amneziaKey = share()).validated(true)
        assertEquals("Test Amnezia", value.title); assertEquals("AmneziaWG", value.detail)
        val runtime = AmneziaImporter.parse(value.amneziaKey).runtime!!
        assertEquals(listOf("10.80.0.2"), runtime.addresses)
        for (field in listOf("h1=150-200", "s4=15", "i1=<b 0x12345678><r 20>", "random_trailers=true", "disable_cookies=false", "allowed_ip=0.0.0.0/0", "allowed_ip=::/0", "header_protection_key=" + "22".repeat(32))) assertTrue(runtime.uapi.contains(field + "\n"))
    }
    @Test fun backupRetainsAmneziaAndInterval() {
        val profile = SimpleConfiguration(mode = "amnezia", amneziaKey = share()).validated()
        val original = ApplicationBackup(profiles = listOf(profile), mode = "amnezia", autoCaptcha = true, autoRecovery = true, savedDNSServers = emptyList(), splitTunneling = SplitTunnelSettings(), selectedProfileID = profile.id, turnCheckInterval = 120)
        assertEquals(original, ApplicationBackup.decode(original.encoded()))
    }
    @Test fun portableBackupImportsAmnezia() {
        val profile = SimpleConfiguration(mode = "amnezia", amneziaKey = share()).validated()
        val original = ApplicationBackup(profiles = listOf(profile), mode = "amnezia", autoCaptcha = true, autoRecovery = true, savedDNSServers = emptyList(), splitTunneling = SplitTunnelSettings())
        val root = JsonParser.parseString(original.encoded()).asJsonObject
        root.addProperty("format", "TnDtun-backup")
        root.getAsJsonArray("profiles")[0].asJsonObject.add("dns", JsonParser.parseString("{}"))
        assertEquals(profile.amneziaKey, ApplicationBackup.decode(root.toString()).profiles.single().amneziaKey)
    }
    @Test fun rejectConflictsHooksAndDamagedKeys() {
        for (text in listOf(ini.replace("[Peer]", "PostUp = arbitrary\n[Peer]"), ini.replace("DNS = 1.1.1.1", "DNS = 1.1.1.1\nPrivateKey = ${key(19)}"), "vpn://AAAAAHja", ini.replace("PublicKey = ${key(51)}", "PublicKey = broken"), ini + "\n[Peer]\nEndpoint = 203.0.113.30:51820")) {
            assertTrue(runCatching { AmneziaImporter.parse(text) }.isFailure)
        }
    }
    @Test fun importedXrayRetainsParametersButAppOwnsRoutes() {
        val root = """{"outbounds":[{"tag":"remote","protocol":"vless","settings":{"vnext":[{"address":"example.com","port":443,"users":[{"id":"test","encryption":"none"}]}]},"streamSettings":{"network":"tcp","security":"reality","realitySettings":{"publicKey":"test","shortId":"123"}}}],"routing":{"rules":[]},"inbounds":[{"port":9999}]}"""
        val imported = AmneziaImporter.parse(root)
        val config = """{"inbounds":[{"port":10808}],"outbounds":[{"tag":"proxy","protocol":"socks"},{"tag":"direct","protocol":"freedom"}],"routing":{"rules":[{"outboundTag":"direct","ip":["203.0.113.0/24"]}]}}"""
        val changed = JsonParser.parseString(AmneziaXrayConfig.replaceProxy(config, imported.outbound.toString())).asJsonObject
        assertEquals(10808, changed.getAsJsonArray("inbounds")[0].asJsonObject.get("port").asInt)
        assertEquals("123", changed.getAsJsonArray("outbounds")[0].asJsonObject.getAsJsonObject("streamSettings").getAsJsonObject("realitySettings").get("shortId").asString)
        assertEquals("direct", changed.getAsJsonObject("routing").getAsJsonArray("rules")[0].asJsonObject.get("outboundTag").asString)
    }
    @Test fun wrappedWireguardKeyAndDefaultInterval() {
        val plain = ini.lineSequence().filterNot { it.substringBefore('=').trim() in setOf("H1", "S4", "I1", "HeaderProtectionKey", "RandomTrailers", "DisableCookies") }.joinToString("\n")
        assertEquals("WireGuard", AmneziaImporter.parse(Gson().toJson(mapOf("key" to plain))).protocol)
        val backup = ApplicationBackup(profiles = emptyList(), mode = "turn", autoCaptcha = true, autoRecovery = true, savedDNSServers = emptyList(), splitTunneling = SplitTunnelSettings())
        val root = JsonParser.parseString(backup.encoded()).asJsonObject; root.remove("turnCheckInterval")
        assertEquals(30, ApplicationBackup.decode(root.toString()).turnCheckInterval)
        root.addProperty("turnCheckInterval", 0)
        assertTrue(runCatching { ApplicationBackup.decode(root.toString()) }.isFailure)
    }
}
