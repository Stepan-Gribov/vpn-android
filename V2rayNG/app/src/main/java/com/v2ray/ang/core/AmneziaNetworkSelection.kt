package com.v2ray.ang.core

/** Keep a working route while Android evaluates a newly appearing network.
 * A restricted network without Android validation remains usable on cold start,
 * loss of the old network, or after an actual tunnel check fails. */
internal class AmneziaNetworkSelection<T> {
    private var selected: T? = null
    private var candidate: T? = null
    private val available = linkedSetOf<T>()

    fun available(network: T, preserveCurrent: Boolean): T? {
        available.remove(network)
        available.add(network)
        candidate = network
        return if (selected == null || selected == network || !preserveCurrent) select(network) else null
    }

    fun validated(network: T): T? =
        if (candidate == network && selected != network) select(network) else null

    fun failed(): T? = candidate?.takeIf { it != selected }?.let(::select)

    fun lost(network: T): T? {
        available.remove(network)
        if (candidate == network) candidate = available.lastOrNull()
        if (selected != network) return null
        selected = null
        return candidate?.let(::select)
    }

    fun reset() { selected = null; candidate = null; available.clear() }
    private fun select(network: T): T { selected = network; return network }
}
