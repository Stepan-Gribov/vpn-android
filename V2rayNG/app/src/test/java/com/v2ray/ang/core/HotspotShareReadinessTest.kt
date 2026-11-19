package com.v2ray.ang.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HotspotShareReadinessTest {
    @Test
    fun `running TURN can add sharing without restarting once client routing is ready`() {
        assertFalse(
            HotspotShareReadiness.canStart(
                serviceRunning = true,
                serviceTransitioning = false,
                requiresTurn = true,
                turnReady = true,
                requiresRootRouting = true,
                rootRoutingReady = false,
            ),
        )

        assertTrue(
            HotspotShareReadiness.canStart(
                serviceRunning = true,
                serviceTransitioning = false,
                requiresTurn = true,
                turnReady = true,
                requiresRootRouting = true,
                rootRoutingReady = true,
            ),
        )
    }

    @Test
    fun `sharing never starts before the bonded TURN listener is ready`() {
        assertFalse(
            HotspotShareReadiness.canStart(
                serviceRunning = true,
                serviceTransitioning = false,
                requiresTurn = true,
                turnReady = false,
                requiresRootRouting = true,
                rootRoutingReady = true,
            ),
        )
    }

    @Test
    fun `manual proxy sharing needs no root route`() {
        assertTrue(
            HotspotShareReadiness.canStart(
                serviceRunning = true,
                serviceTransitioning = false,
                requiresTurn = true,
                turnReady = true,
                requiresRootRouting = false,
                rootRoutingReady = false,
            ),
        )
    }
}
