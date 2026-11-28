package com.v2ray.ang.simple

/** Saved rules are never edited by a temporary override. Zero means next connection. */
internal object TemporaryFullTunnelPolicy {
    val durations = listOf(5, 15, 30, 60, 0)
    const val DEFAULT_MINUTES = 15
    const val DEFAULT_AMNEZIA_SPLIT_ENABLED = true
    const val UNTIL_RESTART = 0

    fun activationCommand(profileId: String, minutes: Int) = "on:$minutes:$profileId"

    fun requestedMinutes(command: String, profileId: String): Int? {
        val fields = command.split(':', limit = 3)
        if (fields.size != 3 || fields[0] != "on" || fields[2] != profileId) return null
        return fields[1].toIntOrNull()?.takeIf { it == DEFAULT_MINUTES || it == UNTIL_RESTART }
    }

    fun supported(mode: String, amneziaSplit: Boolean) = mode == "turn" || (mode == "amnezia" && amneziaSplit)
    fun split(saved: SplitTunnelSettings, mode: String, amneziaSplit: Boolean, fullTunnel: Boolean) =
        if (supported(mode, amneziaSplit) && !fullTunnel) saved else saved.copy(mode = SplitTunnelSettings.DISABLED)
    fun applications(saved: List<String>, mode: String, amneziaSplit: Boolean, fullTunnel: Boolean) =
        if (supported(mode, amneziaSplit) && !fullTunnel) saved else emptyList()
    fun valid(profile: String?, requestedProfile: String, started: Long, expires: Long, now: Long) =
        profile == requestedProfile && now >= started && (expires == 0L || now < expires)
}
