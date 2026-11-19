package com.v2ray.ang.core

/** Pure prerequisite check shared by hotspot UI code and lifecycle tests. */
internal object HotspotShareReadiness {
    fun canStart(
        serviceRunning: Boolean,
        serviceTransitioning: Boolean,
        requiresTurn: Boolean,
        turnReady: Boolean,
        requiresRootRouting: Boolean,
        rootRoutingReady: Boolean,
    ): Boolean = serviceRunning &&
        !serviceTransitioning &&
        (!requiresTurn || turnReady) &&
        (!requiresRootRouting || rootRoutingReady)
}
