package com.v2ray.ang.simple

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.UUID

internal class PanelFixture {
    val keys=KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    val trust=PanelContract.trust("https://example.com/v1","0123456789abcdef",b64(keys.public.encoded.takeLast(32).toByteArray()),
        PanelDns("t.example.com","ab".repeat(32),"1.1.1.1:53"))
    val device="00000000-0000-4000-8000-000000000001"
    val client="00000000-0000-4000-8000-000000000002"
    val nonce="nonce-for-the-current-request"
    fun b64(bytes:ByteArray)=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    fun payload(n:String=nonce,revision:Long=4,status:String="active"):JsonObject = JsonParser.parseString("""
        {"format":"TnDtun-package","schema_version":1,"revision":$revision,"nonce":"$n",
         "issued_at":"2026-10-14T17:00:00+00:00","client_id":"$client","client_name":"Test user",
         "device_id":"$device","status":"$status","expires_at":null,"sync_after_seconds":60,
         "profiles":[],"settings":{"autoCaptcha":true,"autoRecovery":false,"turnCheckInterval":47,
           "dnsServers":["1.1.1.1:5353"],"dnsTunnel":null,
           "splitTunneling":{"mode":"tunnelSelected","bypassEntries":[{"address":"example.com","networks":[],"isNetworkGroup":false}],
             "tunnelEntries":[{"address":"test group","networks":["203.0.113.0/24"],"isNetworkGroup":true}]}}}
        """).asJsonObject.apply {
            if(status=="revoked") remove("settings") else {
                val array=getAsJsonArray("profiles")
                for(i in 3..6) {
                    val p=JsonObject().apply {
                        addProperty("id","00000000-0000-4000-8000-00000000000$i"); addProperty("inbound_id","inbound-$i"); addProperty("node_id","node-a")
                        addProperty("title","Profile $i"); addProperty("location","Finland"); addProperty("generation",1)
                        if(i<5) {
                            addProperty("mode","turn"); addProperty("server","example.invalid:56010"); addProperty("callLink","https://vk.ru/call/join/fixture")
                            addProperty("vlessID","00000000-0000-4000-8000-000000000007"); addProperty("wrapKey",""); addProperty("credential_scope","shared")
                        } else {
                            addProperty("mode","amnezia")
                            addProperty("amneziaKey","""{"outbounds":[{"protocol":"vless","settings":{"vnext":[{"address":"203.0.113.20","port":443,"users":[{"id":"00000000-0000-4000-8000-000000000008","encryption":"none"}]}]}}]}""")
                        }
                    }
                    array.add(p)
                }
            }
        }
    fun envelope(payload:JsonObject):ByteArray {
        // Deliberately pretty-print: verifying reserialized JSON would fail.
        val bytes=GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(payload).toByteArray()
        val signature=Signature.getInstance("Ed25519").apply { initSign(keys.private); update(bytes) }.sign()
        return JsonObject().apply { addProperty("algorithm","Ed25519");addProperty("key_id",trust.keyID);addProperty("payload",b64(bytes));addProperty("signature",b64(signature)) }.toString().toByteArray()
    }
    fun packet(p:JsonObject=payload())=PanelContract.verify(envelope(p),trust,p.get("nonce").asString,device)
    fun rejects(action:()->Unit) { try { action(); fail("Unexpected acceptance") } catch(_:IllegalArgumentException){} catch(_:IllegalStateException){} }
}
class PanelContractTest {
    @Test fun removedSubscriptionCannotReturnFromDelayedSyncButCanBeActivatedAgain() {
        val f=PanelFixture()
        val manual=SimpleConfiguration(id="manual",mode="turn")
        val accepted=PanelStateReducer.apply(SimpleState(configurations=listOf(manual),installationID=f.device),f.packet(),f.trust,"fixture-token",1,null,true)
        val removed=PanelStateReducer.removeSubscription(accepted,accepted.subscriptions.single().id)
        assertEquals(listOf(manual),removed.configurations)
        assertEquals(f.device,removed.installationID)
        assertTrue(removed.subscriptions.isEmpty())
        assertEquals(SplitTunnelSettings.DISABLED,removed.splitTunneling.mode)
        assertTrue(removed.dnsServers.isEmpty())
        f.rejects { PanelStateReducer.apply(removed,f.packet(),f.trust,"old-in-flight-token",2,null) }
        val again=PanelStateReducer.apply(removed,f.packet(),f.trust,"new-token",3,null,true)
        assertEquals(1,again.subscriptions.size)
        assertTrue(again.configurations.contains(manual))
        assertTrue(again.removedSubscriptions.isEmpty())
    }
    @Test fun tunnelResolverSupportsHttpsWithoutAllowingCredentialsOrFragments() {
        assertEquals("https://dns.google/dns-query",SimpleConfiguration.normalizeTunnelResolver("https://dns.google"))
        assertEquals("8.8.8.8:53",SimpleConfiguration.normalizeTunnelResolver("8.8.8.8"))
        for(value in listOf("https://user:pass@dns.google/dns-query","https://dns.google/#x","https://dns.google/?token=x","http://dns.google/dns-query")) {
            try { SimpleConfiguration.normalizeTunnelResolver(value); fail("Invalid resolver accepted") } catch(_:IllegalArgumentException) {}
        }
        val dns=SimpleConfiguration(mode="dns",domain="t.example.com",publicKey="ab".repeat(32),resolver="https://dns.google/dns-query",useWiFiResolver=true,wifiResolver="https://dns.google/dns-query").validated()
        val backup=ApplicationBackup(profiles=listOf(dns),mode="dns",autoCaptcha=true,autoRecovery=true,savedDNSServers=listOf(dns.resolver),splitTunneling=SplitTunnelSettings())
        assertEquals(dns.resolver,ApplicationBackup.decode(backup.encoded()).profiles.single().resolver)
    }
    @Test fun dnsCandidatesKeepTheConfiguredProviderAndBudgetTheHttpsFallback() {
        val dns=PanelDns("t.example.com","ab".repeat(32),"1.1.1.1:53")
        val list=PanelResolvers.candidates(dns,listOf("192.168.0.1:53","10.220.138.1:53","10.221.138.1:53"),true)
        assertEquals(listOf("https://cloudflare-dns.com/dns-query","192.168.0.1:53","1.1.1.1:53","10.220.138.1:53"),list)
        assertEquals("192.168.0.1:53",PanelResolvers.candidates(dns,listOf("192.168.0.1:53"),false).first())
        val fallbacks=PanelResolvers.candidates(dns.copy(savedResolvers=listOf("8.8.8.8:53")),listOf("192.168.0.1:53"),false)
        assertEquals(listOf("192.168.0.1:53","https://cloudflare-dns.com/dns-query","https://dns.google/dns-query","1.1.1.1:53"),fallbacks)
        assertEquals("https://dns.google/dns-query",PanelResolvers.candidates(dns.copy(savedResolvers=listOf("8.8.8.8:53")),listOf("192.168.0.1:53"),true).first())
        assertEquals(listOf("203.0.113.1:5353"),PanelResolvers.candidates(dns.copy(resolver="203.0.113.1:5353",autoDetectResolver=false),emptyList(),false))
    }
    @Test fun malformedDuplicateOrExcessivelyNestedJsonIsRejected() {
        for(raw in listOf("{\"status\":\"active\",\"status\":\"revoked\"}","[NaN]","{foo:1}","{} {}","[".repeat(33)+"]".repeat(33))) {
            try { PanelJson.parse(raw); fail("Malformed JSON accepted") } catch(_:Exception) {}
        }
        try { PanelContract.utf8(byteArrayOf(0xc3.toByte(),0x28)); fail("Malformed UTF-8 accepted") } catch(_:java.nio.charset.CharacterCodingException) {}
        assertTrue(PanelJson.parse("{\"text\":\"[]{}\",\"nested\":[{}]}").isJsonObject)
    }
    @Test fun aNewSigningPinRequiresExplicitActivationAndKeepsOtherSourcesSettings() {
        val f=PanelFixture(); val accepted=PanelStateReducer.apply(SimpleState(),f.packet(),f.trust,"fixture-token",1,null)
        val newKey=PanelFixture().trust
        f.rejects { PanelStateReducer.apply(accepted,f.packet(),newKey,"fixture-token",2,null) }
        val approved=PanelStateReducer.apply(accepted,f.packet(),newKey,"fixture-token",2,null,activation=true)
        assertTrue(approved.subscriptions.single().trust.sameSource(newKey))
        val other=PanelFixture()
        val changed=other.payload().apply {
            addProperty("device_id","00000000-0000-4000-8000-000000000009")
            getAsJsonObject("settings").addProperty("turnCheckInterval",93)
            getAsJsonArray("profiles").remove(0); getAsJsonArray("profiles").forEach { it.asJsonObject.addProperty("id",UUID.randomUUID().toString()) }
        }
        val bytes=other.envelope(changed)
        val packet=PanelContract.verify(bytes,other.trust,other.nonce,changed.get("device_id").asString)
        val second=PanelStateReducer.apply(accepted,packet,other.trust,"second-token",3,null,activation=true)
        val updated=PanelStateReducer.apply(second,f.packet(),f.trust,"fixture-token",4,null)
        assertEquals(second.activeSubscriptionID,updated.activeSubscriptionID)
        assertEquals(second.splitTunneling,updated.splitTunneling)
        assertEquals(93,updated.turnCheckInterval)
        f.rejects { PanelStateReducer.apply(accepted,f.packet(),f.trust,"токен",5,null) }
    }
    @Test fun shortAndLegacyActivationCodesNormalizeWithoutAcceptingOtherAlphabets() {
        for(raw in listOf("QWRT","qwrt"," qW-\tr t\n")) assertEquals("QWRT",PanelContract.code(raw))
        val legacy="ABCDEFGHIJKLMNOPQRST234567"
        assertEquals(26,legacy.length)
        assertEquals(legacy,PanelContract.code(" abcd-efgh ijkl mnop qrst 234567\n"))
        for(raw in listOf("","QWR","QWRTY","QWR2","QWR7","1234","йцук","ßß","KWRT","ＱＷＲＴ","QW.RT",
            "A".repeat(25),"A".repeat(27),"A".repeat(25)+"0","A".repeat(25)+"1","A".repeat(25)+"8","A".repeat(25)+"9")) {
            assertThrows(IllegalArgumentException::class.java) { PanelContract.code(raw) }
        }
    }
    @Test fun codeLinkAndQrUseTheSameTrustedInput() {
        val f=PanelFixture()
        for(code in listOf("QWRT","qwrt","qw-rt","q w\tr\nt","abcdefghijklmnopqrst234567")) {
            val raw="tndtun://activate?api="+URLEncoder.encode(f.trust.api,"UTF-8")+"&code="+URLEncoder.encode(code,"UTF-8")+"&key="+f.trust.publicKey+"&kid="+f.trust.keyID
            val direct=PanelContract.activation(code,f.trust)
            val pasted=PanelContract.activation(raw,f.trust); val scanned=PanelContract.activation(raw,f.trust)
            assertEquals(PanelContract.code(code),direct.code)
            assertEquals(direct.code,pasted.code); assertEquals(pasted.code,scanned.code); assertTrue(pasted.trust.sameSource(f.trust))
            f.rejects { PanelContract.activation(raw+"&api=https%3A%2F%2Fevil.example%2Fv1",f.trust) }
            f.rejects { PanelContract.activation(raw.replace("https%3A","http%3A"),f.trust) }
            f.rejects { PanelContract.activation(raw+"#fragment",f.trust) }
            f.rejects { PanelContract.activation(raw.replace("&code="+URLEncoder.encode(code,"UTF-8"),"&code=QWR2"),f.trust) }
        }
        f.rejects { PanelContract.api("https://user:pass@example.com/v1") }
        f.rejects { PanelContract.api("https://example.com/v1?redirect=1") }
    }
    @Test fun signatureVerifiesOriginalBytesAndAllProfilesStayDistinct() {
        val f=PanelFixture(); val p=f.packet()
        assertEquals(4,p.profiles.size); assertEquals(2,p.profiles.count { it.mode=="amnezia" })
        assertEquals(2,p.profiles.count { it.server=="example.invalid:56010" })
        assertEquals("Profile 3",p.profiles.first().title); assertEquals(47,p.settings!!.turnCheckInterval)
        assertEquals("tunnelSelected",p.settings.splitTunneling.mode)
        assertEquals(1,p.settings.splitTunneling.bypassEntries.size)
        assertEquals(1,p.settings.splitTunneling.tunnelEntries.size)
        assertEquals(listOf("1.1.1.1:5353"),p.settings.dnsServers)
    }
    @Test fun tamperedNonceClientRevisionSignatureAndTypesAreRejected() {
        val f=PanelFixture(); val p=f.packet()
        val sub=PanelSubscription(PanelContract.subscriptionID(f.trust.api,f.device),f.trust,f.device,"fixture-token",f.client,"Test",5,"active",null,60,1,p.settings)
        f.rejects { PanelContract.verify(f.envelope(f.payload()),f.trust,"wrong-nonce",f.device) }
        f.rejects { PanelContract.verify(f.envelope(f.payload()),f.trust,f.nonce,UUID.randomUUID().toString()) }
        f.rejects { PanelContract.verify(f.envelope(f.payload()),f.trust,f.nonce,f.device,sub) }
        f.rejects { PanelContract.verify(f.envelope(f.payload(revision=5).apply { addProperty("client_id",UUID.randomUUID().toString()) }),f.trust,f.nonce,f.device,sub) }
        val bad=JsonParser.parseString(String(f.envelope(f.payload()))).asJsonObject.apply { addProperty("signature",f.b64(ByteArray(64))) }
        f.rejects { PanelContract.verify(bad.toString().toByteArray(),f.trust,f.nonce,f.device) }
        val altered=JsonParser.parseString(String(f.envelope(f.payload()))).asJsonObject.apply { addProperty("key_id","fedcba9876543210") }
        f.rejects { PanelContract.verify(altered.toString().toByteArray(),f.trust,f.nonce,f.device) }
        for(field in listOf("schema_version","revision","sync_after_seconds")) f.rejects { f.packet(f.payload().apply { addProperty(field,"1") }) }
        f.rejects { f.packet(f.payload().apply { addProperty("schema_version",2) }) }
        f.rejects { f.packet(f.payload().apply { getAsJsonObject("settings").addProperty("autoCaptcha","true") }) }
        f.rejects { f.packet(f.payload().apply { getAsJsonObject("settings").addProperty("turnCheckInterval",47.5) }) }
        f.rejects { f.packet(f.payload().apply { getAsJsonArray("profiles").add(getAsJsonArray("profiles")[0].deepCopy()) }) }
        assertEquals(5L,PanelContract.verify(f.envelope(f.payload(revision=5)),f.trust,f.nonce,f.device,sub).revision)
    }
    @Test fun repeatUpdateRemovesOnlyOwnedProfilesAndKeepsSelection() {
        val f=PanelFixture(); val manual=f.packet().profiles.first().copy(id=UUID.randomUUID().toString(),managed=null)
        val original=SimpleState(configurations=listOf(manual),selectedProfileID=manual.id,installationID="local-installation",savedResolvers=listOf("192.168.0.1:53"))
        val first=PanelStateReducer.apply(original,f.packet(),f.trust,"fixture-token",10,manual.id)
        val repeated=PanelStateReducer.apply(first,f.packet(),f.trust,"fixture-token",11,manual.id)
        assertEquals(5,repeated.configurations.size); assertEquals(manual.id,repeated.selectedProfileID)
        assertEquals(listOf("192.168.0.1:53"),repeated.savedResolvers)
        assertEquals(listOf("1.1.1.1:5353"),repeated.dnsServers)
        val changed=f.payload(revision=5).apply {
            getAsJsonArray("profiles").remove(0)
            getAsJsonObject("settings").getAsJsonObject("splitTunneling").addProperty("mode","bypassSelected")
        }
        val updated=PanelStateReducer.apply(repeated,f.packet(changed),f.trust,"fixture-token",12,manual.id)
        assertEquals(4,updated.configurations.size); assertEquals(manual,updated.configurations.first())
        assertEquals("bypassSelected",updated.splitTunneling.mode)
        assertEquals(1,updated.splitTunneling.tunnelEntries.size)
        val revoked=PanelStateReducer.apply(updated,f.packet(f.payload(revision=6,status="revoked")),f.trust,"fixture-token",13,manual.id)
        assertEquals(listOf(manual),revoked.configurations)
        assertFalse(revoked.subscriptions.single().accessAllowed(13))
        f.rejects { PanelStateReducer.apply(updated,f.packet(),f.trust,"fixture-token",14,manual.id) }
        assertEquals(5L,updated.subscriptions.single().revision)
    }
    @Test fun expiryIsLongNullableAndRenewalReplacesCredentials() {
        val f=PanelFixture(); val p=f.packet(f.payload().apply { addProperty("expires_at",2200000000L) })
        val s=PanelStateReducer.apply(SimpleState(),p,f.trust,"fixture-token",1,null).subscriptions.single()
        assertTrue(s.accessAllowed(2199999999)); assertFalse(s.accessAllowed(2200000000))
        assertEquals(2200000000L,s.expiresAt)
        val renewed=f.packet(f.payload(revision=5).apply { getAsJsonArray("profiles").forEach { it.asJsonObject.addProperty("generation",2) } })
        val current=PanelStateReducer.apply(SimpleState(subscriptions=listOf(s),configurations=p.profiles),renewed,f.trust,"fixture-token",2200000001,null)
        assertTrue(current.subscriptions.single().accessAllowed(Long.MAX_VALUE))
        assertEquals(2L,current.configurations.first().managed!!.generation)
    }
    @Test fun atomicInterruptedCommitAndRestartKeepsWholeOldState() {
        val dir=kotlin.io.path.createTempDirectory("panel-atomic").toFile()
        try {
            val storage=AtomicStateFile(File(dir,"state"))
            val f=PanelFixture(); val original=PanelStateReducer.apply(SimpleState(installationID="stable"),f.packet(),f.trust,"fixture-token",10,null)
            storage.locked { storage.replace(Gson().toJson(original).toByteArray()) }
            try { storage.locked { storage.replace("partial-new-data".toByteArray()) { throw IOException("simulated exit before commit") } }; fail() } catch(_:IOException){}
            File(dir,"state.tmp").writeText("uncommitted leftover")
            val restored=Gson().fromJson(storage.file.readText(),SimpleState::class.java)
            assertEquals("stable",restored.installationID); assertEquals(4L,restored.subscriptions.single().revision)
            assertEquals(4,restored.configurations.size); assertEquals("fixture-token",restored.subscriptions.single().token)
        } finally { dir.deleteRecursively() }
    }
    @Test fun backupsPreserveArbitraryIntervalButNotDeviceCredentialsOrOwnership() {
        val f=PanelFixture(); val p=f.packet()
        val backup=ApplicationBackup(profiles=p.profiles,mode="turn",autoCaptcha=true,autoRecovery=true,
            savedDNSServers=listOf("192.168.0.1:53"),dnsServers=p.settings!!.dnsServers,splitTunneling=p.settings.splitTunneling,turnCheckInterval=47)
        val encoded=backup.encoded()
        assertFalse(encoded.contains("subscriptionID")); assertFalse(encoded.contains("fixture-token"))
        val restored=ApplicationBackup.decode(encoded)
        assertEquals(47,restored.turnCheckInterval); assertTrue(restored.profiles.all { it.managed==null })
        assertEquals(listOf("192.168.0.1:53"),restored.savedDNSServers)
        assertEquals(listOf("1.1.1.1:5353"),restored.dnsServers)
        val accepted=PanelStateReducer.apply(SimpleState(installationID="stable-local-installation"),p,f.trust,"fixture-token",1,null)
        val imported=PanelStateReducer.restoreBackup(accepted,restored)
        assertTrue(imported.configurations.none { row->p.profiles.any { it.id==row.id } })
        assertEquals("stable-local-installation",imported.installationID)
        val synced=PanelStateReducer.apply(imported,p,f.trust,"fixture-token",2,null)
        assertEquals(8,synced.configurations.size)
        f.rejects { ApplicationBackup.decode(encoded.replace("\"turnCheckInterval\": 47","\"turnCheckInterval\": 47.5")) }
    }
}
