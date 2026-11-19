package com.v2ray.ang.simple

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class ApplicationBackupTest {
    private fun backup(): ApplicationBackup {
        val turn = SimpleConfiguration(id = "turn", callLink = "https://vk.ru/call/join/test", server = "203.0.113.10", vlessID = "00000000-0000-0000-0000-000000000000")
        val dns = SimpleConfiguration(id = "dns", mode = "dns", domain = "dns.example.invalid", publicKey = "a".repeat(64),
            autoDetectResolver = false, savedResolvers = listOf("9.9.9.9"), useWiFiResolver = true, wifiResolver = "8.8.8.8")
        return ApplicationBackup(profiles = listOf(turn, dns), mode = "dns", autoCaptcha = false, autoRecovery = true,
            savedDNSServers = listOf("1.1.1.1"), selectedProfileID = "dns",
            splitTunneling = SplitTunnelSettings(SplitTunnelSettings.BYPASS,
                bypassEntries = SplitTunnelImport.manual("direct.test"), tunnelEntries = SplitTunnelImport.manual("proxy.test")))
    }
    @Test fun `all profiles switches DNS and both split lists survive a backup round trip`() {
        val original = backup().validated()
        val restored = ApplicationBackup.decode(original.encoded())
        assertEquals(original, restored)
        assertFalse(restored.profiles.single { it.mode == "dns" }.autoDetectResolver)
        assertEquals(listOf("1.1.1.1:53", "9.9.9.9:53"), restored.profiles.single { it.mode == "dns" }.savedResolvers)
    }
    @Test fun `legacy Android backups remain readable after rebranding`() {
        val json = backup().encoded().replace(ApplicationBackup.FORMAT, "SimpleBLT-backup")
        assertEquals(backup().validated().copy(createdAt = ApplicationBackup.decode(json).createdAt), ApplicationBackup.decode(json))
    }
    @Test fun `portable nested DNS schema imports without Swift source dependencies`() {
        val value = backup().validated()
        val root = JsonParser.parseString(value.encoded()).asJsonObject
        root.addProperty("format", "TnDtun-backup")
        root.getAsJsonArray("profiles").forEach { raw ->
            val profile = raw.asJsonObject
            val dns = com.google.gson.JsonObject()
            listOf("domain", "publicKey", "tunnelType", "sessionCount", "resolver", "autoDetectResolver", "savedResolvers", "useWiFiResolver", "wifiResolver",
                "sshUsername", "sshPassword", "sshPrivateKey", "sshHostKey").forEach { key -> dns.add(key, profile.remove(key)) }
            profile.add("dns", dns)
        }
        assertEquals(value, ApplicationBackup.decode(root.toString()))
    }
    @Test fun `damaged incomplete or unsupported backups are rejected`() {
        val good = backup().encoded()
        for (change in listOf<(com.google.gson.JsonObject) -> Unit>(
            { it.addProperty("schemaVersion", 2) }, { it.remove("autoCaptcha") }, { it.addProperty("autoRecovery", "true") },
            { it.addProperty("selectedProfileID", "missing") },
            { it.getAsJsonArray("profiles").add(it.getAsJsonArray("profiles")[0].deepCopy()) },
            { it.getAsJsonObject("splitTunneling").remove("tunnelEntries") },
            { it.getAsJsonObject("splitTunneling").addProperty("mode", "invalid") },
        )) {
            val root = JsonParser.parseString(good).asJsonObject; change(root)
            assertThrows(Exception::class.java) { ApplicationBackup.decode(root.toString()) }
        }
        assertThrows(Exception::class.java) { ApplicationBackup.decode(" ".repeat(ApplicationBackup.MAXIMUM_SIZE + 1)) }
    }
    @Test fun `IPv6 backup is optional for old files and strict when present`() {
        assertTrue(ApplicationBackup.decode(backup().copy(ipv6Enabled=true).encoded()).ipv6Enabled)
        val old=JsonParser.parseString(backup().encoded()).asJsonObject
        old.remove("ipv6Enabled")
        assertFalse(ApplicationBackup.decode(old.toString()).ipv6Enabled)
        old.addProperty("ipv6Enabled","true")
        assertThrows(Exception::class.java) { ApplicationBackup.decode(old.toString()) }
    }

    @Test fun `application exclusions round trip while legacy backups default to empty`() {
        val original=backup().copy(excludedApplications=listOf("com.example.app"))
        assertEquals(original.validated(),ApplicationBackup.decode(original.encoded()))
        val old=JsonParser.parseString(backup().encoded()).asJsonObject
        old.remove("excludedApplications")
        assertTrue(ApplicationBackup.decode(old.toString()).excludedApplications.isEmpty())
        old.addProperty("excludedApplications","com.example.app")
        assertThrows(Exception::class.java) { ApplicationBackup.decode(old.toString()) }
    }

}
