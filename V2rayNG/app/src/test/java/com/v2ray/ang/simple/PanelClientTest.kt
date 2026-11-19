package com.v2ray.ang.simple

import com.google.gson.JsonObject
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.UUID

class PanelClientTest {
    private class Storage:PanelStorage {
        var state=SimpleState()
        var commits=0
        var identityCommitted=false
        override fun installationID():String {
            if(state.installationID.isBlank()) state=state.copy(installationID=UUID.randomUUID().toString())
            identityCommitted=true
            return state.installationID
        }
        override fun subscriptions()=state.subscriptions
        override fun accept(packet:PanelPackage,trust:PanelTrust,token:String,now:Long,activation:Boolean,activationCode:String?) {
            state=PanelStateReducer.apply(state,packet,trust,token,now,state.selectedProfileID,activation,activationCode); commits++
        }
        override fun reactivation(id:String) {
            state=state.copy(subscriptions=state.subscriptions.map { s->if(s.id==id)
                PanelSubscription(s.id,s.trust,s.deviceID,s.token,s.clientID,s.clientName,s.revision,s.status,s.expiresAt,s.syncAfterSeconds,s.lastSync,s.settings,true,s.activationCode) else s })
        }
    }
    private fun activation(f:PanelFixture,nonce:String):ByteArray=JsonObject().apply {
        addProperty("device_id",f.device); addProperty("token","fixture-device-token")
        add("package",com.google.gson.JsonParser.parseString(String(f.envelope(f.payload(n=nonce)))))
    }.toString().toByteArray()
    @Test fun shortAndLegacyCodesReachActivationWithoutChangingTheSyncContract()=runBlocking {
        val f=PanelFixture()
        for(raw in listOf(" q-w r\tt ","abcdefghijklmnopqrst234567")) {
            val storage=Storage(); val codes=mutableListOf<String>()
            val exchange=PanelExchange { _,_,path,body,token->
                assertEquals("activate",path); assertNull(token)
                assertEquals("android",body.get("platform").asString)
                codes.add(body.get("code").asString)
                activation(f,body.get("nonce").asString)
            }
            PanelClient(storage,exchange,{ }).activate(PanelContract.activation(raw,f.trust),"Test")
            assertEquals(listOf(PanelContract.code(raw)),codes)
            assertEquals(1,storage.commits)
            assertEquals(PanelContract.code(raw),storage.state.subscriptions.single().activationCode)
        }
    }
    @Test fun activationCodeSurvivesReloadSyncAndReactivation() {
        val f=PanelFixture(); val gson=com.google.gson.Gson()
        val activated=PanelStateReducer.apply(SimpleState(),f.packet(),f.trust,"token",10,null,true,"0012")
        val restored=gson.fromJson(gson.toJson(activated),SimpleState::class.java)
        assertEquals("0012",restored.subscriptions.single().activationCode)
        val synced=PanelStateReducer.apply(restored,f.packet(),f.trust,"token",20,null)
        val storage=Storage().apply { state=synced }
        storage.reactivation(synced.subscriptions.single().id)
        assertEquals("0012",storage.state.subscriptions.single().activationCode)
        val reactivated=PanelStateReducer.apply(storage.state,f.packet(),f.trust,"token",30,null,true,"ABCD")
        assertEquals("ABCD",reactivated.subscriptions.single().activationCode)
        assertTrue(PanelStateReducer.removeSubscription(reactivated,reactivated.subscriptions.single().id).subscriptions.isEmpty())
    }
    @Test fun legacySubscriptionWithoutCodeRemainsUnknown() {
        val f=PanelFixture(); val gson=com.google.gson.Gson()
        val legacy=PanelStateReducer.apply(SimpleState(),f.packet(),f.trust,"token",10,null)
        val json=gson.toJsonTree(legacy).asJsonObject
        json.getAsJsonArray("subscriptions")[0].asJsonObject.remove("activationCode")
        val restored=gson.fromJson(json,SimpleState::class.java)
        assertNull(restored.subscriptions.single().activationCode)
        assertNull(PanelStateReducer.apply(restored,f.packet(),f.trust,"token",20,null).subscriptions.single().activationCode)
    }
    @Test fun lostActivationReplyAndRestartUseOneDeviceSlot()=runBlocking {
        val f=PanelFixture(); val storage=Storage(); val installations=mutableSetOf<String>(); val pauses=mutableListOf<Long>(); var requests=0
        val exchange=PanelExchange { _,_,path,body,token->
            assertTrue(storage.identityCommitted); assertEquals("activate",path); assertNull(token)
            installations.add(body.get("installation_id").asString); requests++
            if(requests==1) throw IOException("lost response after server committed device")
            activation(f,body.get("nonce").asString)
        }
        val input=ActivationInput("A".repeat(26),f.trust)
        PanelClient(storage,exchange,{ pauses.add(it) }).activate(input,"Test phone")
        val saved=storage.state.installationID
        // Fresh engine models a restarted UI/process; durable identity stays the same.
        PanelClient(storage,exchange,{ }).activate(input,"Test phone")
        assertEquals(1,installations.size); assertEquals(saved,installations.single())
        assertEquals(4,storage.state.configurations.size); assertEquals(1,storage.state.subscriptions.size)
        assertEquals("fixture-device-token",storage.state.subscriptions.single().token)
        assertEquals(1,pauses.size); assertTrue(pauses.first() in 2000..3499)
    }
    @Test fun provisioningPendingRetriesSameInstallationAndNonce()=runBlocking {
        val f=PanelFixture(); val storage=Storage(); val ids=mutableSetOf<String>(); val nonces=mutableSetOf<String>(); var calls=0
        val exchange=PanelExchange { _,_,_,body,_->
            ids.add(body.get("installation_id").asString); nonces.add(body.get("nonce").asString)
            if(++calls<3) throw PanelHttpFailure(503,"provisioning_pending")
            activation(f,body.get("nonce").asString)
        }
        PanelClient(storage,exchange,{ }).activate(ActivationInput("A".repeat(26),f.trust),"Test")
        assertEquals(3,calls); assertEquals(1,ids.size); assertEquals(1,nonces.size); assertEquals(1,storage.commits)
    }
    @Test fun deviceLimitAndInvalidActivationNeverWipeCachedProfiles()=runBlocking {
        val f=PanelFixture()
        for(error in listOf(PanelHttpFailure(409,"device_limit"),PanelHttpFailure(403,"invalid_activation"))) {
            val storage=Storage().apply { state=PanelStateReducer.apply(state,f.packet(),f.trust,"existing-token",10,null) }
            val before=storage.state; var calls=0
            val client=PanelClient(storage,PanelExchange { _,_,_,_,_->calls++; throw error },{ fail("Must not retry") })
            try { client.activate(ActivationInput("A".repeat(26),f.trust),"Test"); fail() } catch(_:PanelHttpFailure){}
            assertEquals(1,calls); assertEquals(before.configurations,storage.state.configurations)
            assertEquals(before.subscriptions,storage.state.subscriptions); assertEquals(0,storage.commits)
            assertTrue(storage.state.installationID.isNotBlank())
        }
    }
    @Test fun syncUsesBearerRevisionAndLeavesCacheOnUnsignedErrors()=runBlocking {
        val f=PanelFixture(); val storage=Storage().apply { state=PanelStateReducer.apply(state,f.packet(),f.trust,"cached-token",10,null) }
        val before=storage.state
        val exchange=PanelExchange { trust,_,path,body,token->
            assertTrue(trust.sameSource(f.trust)); assertEquals("sync",path); assertEquals("cached-token",token)
            assertEquals(4L,body.get("known_revision").asLong); assertTrue(body.get("nonce").asString.length>=16)
            throw PanelHttpFailure(403,"device_revoked")
        }
        try { PanelClient(storage,exchange,{ }).sync(before.subscriptions.single()); fail() } catch(_:PanelHttpFailure){}
        assertSame(before,storage.state)
        val unauthorized=PanelExchange { _,_,_,_,_->throw PanelHttpFailure(401,"unauthorized") }
        try { PanelClient(storage,unauthorized,{ }).sync(before.subscriptions.single()); fail() } catch(_:PanelHttpFailure){}
        assertEquals(before.configurations,storage.state.configurations)
        assertTrue(storage.state.subscriptions.single().needsActivation)
        assertTrue(storage.state.subscriptions.single().accessAllowed(10))
    }
    @Test fun corruptResponseNeverCommitsAndVerifiedRevocationDoes()=runBlocking {
        val f=PanelFixture(); val storage=Storage().apply { state=PanelStateReducer.apply(state,f.packet(),f.trust,"cached-token",10,null) }
        val before=storage.state
        val corrupt=PanelExchange { _,_,_,body,_->
            val env=com.google.gson.JsonParser.parseString(String(f.envelope(f.payload(n=body.get("nonce").asString,revision=5)))).asJsonObject
            env.addProperty("signature",f.b64(ByteArray(64))); env.toString().toByteArray()
        }
        try { PanelClient(storage,corrupt,{ }).sync(before.subscriptions.single()); fail() } catch(_:IllegalStateException){}
        assertSame(before,storage.state); assertEquals(0,storage.commits)
        val revoked=PanelExchange { _,_,_,body,_->f.envelope(f.payload(n=body.get("nonce").asString,revision=5,status="revoked")) }
        PanelClient(storage,revoked,{ }).sync(before.subscriptions.single())
        assertEquals("revoked",storage.state.subscriptions.single().status); assertTrue(storage.state.configurations.isEmpty())
    }
    @Test fun oneDeadlineIncludesRetriesAndReturnsNormalErrorWithoutApplyingData()=runBlocking {
        val f=PanelFixture(); val storage=Storage()
        val nonces=mutableSetOf<String>(); var calls=0; var closed=false
        val exchange=PanelExchange { _,_,_,body,_->
            nonces.add(body.get("nonce").asString)
            if(++calls==1) { delay(50); throw PanelHttpFailure(503,"provisioning_pending") }
            try { awaitCancellation() } finally { closed=true }
        }
        val started=System.nanoTime()
        val result=runCatching {
            PanelClient(storage,exchange,pause={ delay(50) },requestTimeoutMillis=250)
                .activate(ActivationInput("A".repeat(26),f.trust),"Test")
        }
        assertTrue(result.exceptionOrNull() is IOException)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("не ответил вовремя"))
        assertTrue("Total deadline must include retry",(System.nanoTime()-started)/1_000_000<1500)
        assertEquals(2,calls); assertEquals(1,nonces.size); assertTrue(closed)
        assertEquals(0,storage.commits)
        currentCoroutineContext().ensureActive() // UI may immediately retry the normal error.
        PanelClient(storage,PanelExchange { _,_,_,body,_->activation(f,body.get("nonce").asString) })
            .activate(ActivationInput("A".repeat(26),f.trust),"Retry")
        assertEquals(1,storage.commits)
    }
    @Test fun externalCancellationAbortsRequestAndNeverRetriesOrCommits()=runBlocking {
        val f=PanelFixture(); val storage=Storage(); val entered=CompletableDeferred<Unit>()
        var calls=0; var closed=false
        val request=async {
            PanelClient(storage,PanelExchange { _,_,_,_,_->
                calls++
                try { entered.complete(Unit); awaitCancellation() } finally { closed=true }
            },pause={ fail("Cancelled requests must not retry") },requestTimeoutMillis=2000)
                .activate(ActivationInput("A".repeat(26),f.trust),"Test")
        }
        entered.await(); request.cancelAndJoin()
        assertTrue(request.isCancelled); assertTrue(closed)
        assertEquals(1,calls); assertEquals(0,storage.commits)
    }
}
