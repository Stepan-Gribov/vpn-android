package com.v2ray.ang.core

/** Coalesce screen/unlock duplicates; a different network/IP always gets a new attempt. */
internal class AmneziaResumeGate {
    private var key: String? = null
    private var last = Long.MIN_VALUE
    @Synchronized fun reset() { key = null; last = Long.MIN_VALUE }
    @Synchronized fun admit(value: String, now: Long): Boolean {
        if (key == value && now >= last && now - last < 5_000) return false
        key = value; last = now
        return true
    }
}

/** At most one rebind; cancellation/manual stop invalidates results between steps. */
internal object AmneziaResumeCheck {
    suspend fun <T> run(
        rebindFirst: Boolean,
        valid: () -> Boolean,
        refresh: suspend () -> Unit,
        probe: suspend () -> T,
        healthy: (T) -> Boolean,
        migrationPending: () -> Boolean = { false },
    ): T? {
        if (!valid()) return null
        val mustRebind = rebindFirst || migrationPending()
        if (mustRebind) { refresh(); if (!valid()) return null }
        var result = probe()
        if (!valid()) return null
        if (!healthy(result) && !mustRebind) {
            refresh()
            if (!valid()) return null
            result = probe()
        }
        return result.takeIf { valid() }
    }
}

/** A cancelled coroutine cannot consume migration required by a network event. */
internal class AmneziaMigration {
    data class Target(val revision: Long, val network: Long)
    private var revision=0L
    private var applied=0L
    private var target: Target?=null
    @Synchronized fun request(network: Long) { target=Target(++revision,network) }
    @Synchronized fun pending(): Target? = target?.takeIf { it.revision!=applied }
    @Synchronized fun completed(value: Target) { if(target==value) applied=value.revision }
    @Synchronized fun reset() { revision++; applied=revision; target=null }
}
