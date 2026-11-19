package com.v2ray.ang.simple

import org.junit.Assert.*
import org.junit.Test

class AppVpnExclusionsTest {
    @Test fun packagesAreStableValidatedIdentities() {
        assertEquals(listOf("com.example.a","com.example.z"),AppVpnExclusions.normalized(listOf("com.example.z","com.example.a","com.example.a")))
        listOf("", "com.example app", "com.example/Activity", ".example", "com..example").forEach {
            assertThrows(IllegalArgumentException::class.java) { AppVpnExclusions.normalized(listOf(it)) }
        }
    }
    @Test fun oldPanelPackagesAndSubscriptionRemovalKeepLocalExclusionsAndAddressLists() {
        val f=PanelFixture()
        val apps=listOf("com.example.one")
        val state=SimpleState(excludedApplications=apps)
        val first=PanelStateReducer.apply(state,f.packet(),f.trust,"token",10,null)
        assertEquals(apps,first.excludedApplications)
        val next=PanelStateReducer.apply(first,f.packet(),f.trust,"token",11,null)
        assertEquals(apps,next.excludedApplications)
        assertEquals(apps,PanelStateReducer.removeSubscription(next,next.subscriptions.single().id).excludedApplications)
        val policy=RoutingPolicySnapshot.create(next.splitTunneling,false,excludedApplications=apps)
        assertNotEquals(policy.policySHA256,RoutingPolicySnapshot.create(next.splitTunneling,false).policySHA256)
        assertEquals(policy.networkSHA256,RoutingPolicySnapshot.create(next.splitTunneling,false).networkSHA256)
    }
}
