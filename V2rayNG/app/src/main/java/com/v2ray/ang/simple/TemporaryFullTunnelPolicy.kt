package com.v2ray.ang.simple

/** Saved rules are never edited by a temporary override. Zero means next connection. */
internal object TemporaryFullTunnelPolicy {
    val durations = listOf(5, 15, 30, 60, 0)
    const val DEFAULT_MINUTES = 15
    fun supported(mode: String, amneziaSplit: Boolean) = mode == "turn" || (mode == "amnezia" && amneziaSplit)
    fun split(saved: SplitTunnelSettings, mode: String, amneziaSplit: Boolean, fullTunnel: Boolean) =
        if (supported(mode, amneziaSplit) && !fullTunnel) saved else saved.copy(mode = SplitTunnelSettings.DISABLED)
    fun applications(saved: List<String>, mode: String, amneziaSplit: Boolean, fullTunnel: Boolean) =
        if (supported(mode, amneziaSplit) && !fullTunnel) saved else emptyList()
    fun valid(profile: String?, requestedProfile: String, started: Long, expires: Long, now: Long) =
        profile == requestedProfile && now >= started && (expires == 0L || now < expires)
}
