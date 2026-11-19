package com.v2ray.ang.dto.entities

import com.v2ray.ang.enums.EConfigType
import org.junit.Assert.*
import org.junit.Test

class ProfileItemTest {
    private fun profile() = ProfileItem(configType = EConfigType.VLESS,
        server = "vpn.example", serverPort = "443", password = "identity")

    @Test fun comparisonWithOtherTypesIsFalse() {
        assertFalse(profile().equals("vpn.example"))
        assertFalse(profile().equals(null))
    }

    @Test fun differentProtocolsAreDifferentConnections() {
        val original = profile()
        assertNotEquals(original, original.copy(configType = EConfigType.VMESS))
    }

    @Test fun equivalentConnectionsRemainDeduplicatedAfterMetadataChanges() {
        val original = profile().copy(security = "tls", insecure = false,
            echConfigList = "ech-data", verifyPeerCertByName = "vpn.example",
            pinSHA256 = "certificate-pin", xhttpExtra = "{\"noGRPCHeader\":true}",
            finalMask = "{\"type\":\"header-custom\"}", browserDialerMode = "WebView")
        val renamed = original.copy(remarks = "New name", subscriptionId = "new-group",
            addedTime = original.addedTime + 1, description = "New description", configVersion = 5)
        assertEquals(original, renamed)
        assertEquals(original.hashCode(), renamed.hashCode())
        assertEquals(1, setOf(original, renamed).size)
        assertTrue(hashSetOf(original).contains(renamed))
    }

    @Test fun duplicateRemovalPreservesDifferentWireGuardKeys() {
        val original = profile().copy(configType = EConfigType.WIREGUARD, preSharedKey = "first-key")
        assertDifferentConnections(original, original.copy(preSharedKey = "rotated-key"))
        assertDifferentConnections(original, original.copy(preSharedKey = null))
    }

    @Test fun duplicateRemovalPreservesPeerVerificationAndRealitySettings() {
        val original = profile().copy(security = "tls", insecure = false)
        for (changed in listOf(
            original.copy(insecure = true),
            original.copy(echConfigList = "ech-data"),
            original.copy(verifyPeerCertByName = "other.example"),
            original.copy(pinSHA256 = "certificate-pin"),
            original.copy(pinnedCA256 = "ca-pin"),
        )) assertDifferentConnections(original, changed)

        val reality = profile().copy(security = "reality")
        assertDifferentConnections(reality, reality.copy(spiderX = "/alternate"))
        assertDifferentConnections(reality, reality.copy(mldsa65Verify = "verification-key"))
    }

    @Test fun duplicateRemovalPreservesTransportAndDialerSettings() {
        val original = profile().copy(network = "xhttp")
        for (changed in listOf(
            original.copy(xhttpExtra = "{\"noGRPCHeader\":true}"),
            original.copy(finalMask = "{\"type\":\"header-custom\"}"),
            original.copy(browserDialerMode = "WebView"),
        )) assertDifferentConnections(original, changed)
    }

    @Test fun policyGroupSelectorsAreConnectionSettingsRatherThanMembershipMetadata() {
        val original = ProfileItem(configType = EConfigType.POLICYGROUP,
            policyGroupType = "random", policyGroupSubscriptionId = "first-subscription",
            policyGroupFilter = "Europe")
        for (changed in listOf(
            original.copy(policyGroupType = "leastPing"),
            original.copy(policyGroupSubscriptionId = "second-subscription"),
            original.copy(policyGroupFilter = "Asia"),
        )) assertDifferentConnections(original, changed)
        val moved = original.copy(subscriptionId = "another-storage-group")
        assertEquals(original, moved)
        assertEquals(original.hashCode(), moved.hashCode())
    }

    private fun assertDifferentConnections(original: ProfileItem, changed: ProfileItem) {
        assertNotEquals(original, changed)
        assertNotEquals(changed, original)
        assertEquals(2, listOf(original, changed, original.copy(), changed.copy()).distinct().size)
    }
}
