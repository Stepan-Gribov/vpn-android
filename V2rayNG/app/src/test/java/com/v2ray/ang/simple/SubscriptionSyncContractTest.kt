package com.v2ray.ang.simple

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubscriptionSyncContractTest {
    private val fixture = PanelFixture()

    private fun packetWithPolicy(policy: String, fixture: PanelFixture = this.fixture,
                                 payload: JsonObject = fixture.payload()): JsonObject = payload.apply {
        getAsJsonObject("settings").add("subscriptionSync", JsonParser.parseString(policy))
    }

    @Test fun signedPolicyIsOptionalAndAcceptsBothIntervalLimits() {
        assertNull(fixture.packet().settings?.subscriptionSync)
        assertEquals(SubscriptionSyncPolicy(true, 15), fixture.packet(packetWithPolicy(
            """{"enabled":true,"intervalMinutes":15}""")).settings?.subscriptionSync)
        assertEquals(SubscriptionSyncPolicy(false, 1440), fixture.packet(packetWithPolicy(
            """{"enabled":false,"intervalMinutes":1440}""")).settings?.subscriptionSync)
    }

    @Test fun malformedPolicyIsRejectedEvenWhenTurnedOff() {
        val malformed = listOf(
            "null", "[]", "{}",
            """{"enabled":"true","intervalMinutes":15}""",
            """{"enabled":true,"intervalMinutes":"15"}""",
            """{"enabled":true,"intervalMinutes":15.5}""",
            """{"enabled":false,"intervalMinutes":14}""",
            """{"enabled":true,"intervalMinutes":1441}"""
        )
        for (policy in malformed) fixture.rejects { fixture.packet(packetWithPolicy(policy)) }
    }

    @Test fun invalidSignatureCannotEnableAutomaticSync() {
        val payload = packetWithPolicy("""{"enabled":true,"intervalMinutes":30}""")
        val envelope = JsonParser.parseString(fixture.envelope(payload).decodeToString()).asJsonObject
        envelope.addProperty("signature", fixture.b64(ByteArray(64)))
        fixture.rejects {
            PanelContract.verify(envelope.toString().encodeToByteArray(), fixture.trust, fixture.nonce, fixture.device)
        }
    }

    @Test fun subscriptionsKeepIndependentDeadlinesAndSuccessTimesAcrossReload() {
        val first = fixture.packet(packetWithPolicy("""{"enabled":true,"intervalMinutes":15}"""))
        val one = PanelStateReducer.apply(SimpleState(), first, fixture.trust, "fixture-token", 1000, null)
        val secondFixture = PanelFixture()
        val secondDevice = "00000000-0000-4000-8000-000000000009"
        val payload = secondFixture.payload().apply {
            addProperty("device_id", secondDevice)
            add("profiles", com.google.gson.JsonArray())
        }
        packetWithPolicy("""{"enabled":true,"intervalMinutes":30}""", secondFixture, payload)
        val second = PanelContract.verify(secondFixture.envelope(payload), secondFixture.trust, secondFixture.nonce, secondDevice)
        val two = PanelStateReducer.apply(one, second, secondFixture.trust, "fixture-token-2", 1010, null, activation = true)
        val reload = Gson().let { it.fromJson(it.toJson(two), SimpleState::class.java) }
        val old = reload.subscriptions.associateBy { it.deviceID }
        assertEquals(1900L, old.getValue(fixture.device).automaticSyncAt)
        assertEquals(2810L, old.getValue(secondDevice).automaticSyncAt)
        assertEquals(1000L, old.getValue(fixture.device).lastSync)
        assertEquals(1010L, old.getValue(secondDevice).lastSync)

        val disabled = fixture.packet(packetWithPolicy("""{"enabled":false,"intervalMinutes":15}"""))
        val updated = PanelStateReducer.apply(reload, disabled, fixture.trust, "fixture-token", 2000, null)
        assertNull(updated.subscriptions.single { it.deviceID == fixture.device }.automaticSyncAt)
        assertEquals(2000L, updated.subscriptions.single { it.deviceID == fixture.device }.lastSync)
        assertEquals(2810L, updated.subscriptions.single { it.deviceID == secondDevice }.automaticSyncAt)
    }

    @Test fun oldStoredSubscriptionsAndRevokedOrReactivationStatesStayManual() {
        val legacy = PanelStateReducer.apply(SimpleState(), fixture.packet(), fixture.trust, "fixture-token", 1000, null)
        val gson = Gson()
        val json = gson.toJsonTree(legacy).asJsonObject
        json.getAsJsonArray("subscriptions").single().asJsonObject.remove("nextSyncAt")
        val existing = gson.fromJson(json, SimpleState::class.java).subscriptions.single()
        assertEquals(1000L, existing.lastSync)
        assertNull(existing.automaticSyncAt)

        val active = fixture.packet(packetWithPolicy("""{"enabled":true,"intervalMinutes":15}"""))
        val state = PanelStateReducer.apply(legacy, active, fixture.trust, "fixture-token", 1500, null)
        assertNull(state.subscriptions.single().withSyncState(needsActivation = true).automaticSyncAt)
        val revoked = fixture.packet(fixture.payload(status = "revoked"))
        val stopped = PanelStateReducer.apply(state, revoked, fixture.trust, "fixture-token", 2000, null)
        assertNull(stopped.subscriptions.single().automaticSyncAt)
        assertEquals(2000L, stopped.subscriptions.single().lastSync)
    }
}
