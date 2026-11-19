package com.v2ray.ang.core

/** Service-process state for the background reconnect pipeline. */
internal enum class VpnRecoveryPhase {
    DISCONNECTED,
    CONNECTED,
    CONNECTION_UNAVAILABLE,
    RECONNECT_SCHEDULED,
    SWITCHING_PROFILE,
    WAITING_FOR_NETWORK,
    RETRY_WAIT,
    STOPPING,
    STARTING_TURN,
    STARTING_CORE,
    DNS_RECONNECTING,
    TURN_CAPTCHA,
    TURN_CAPTCHA_MANUAL,
    TURN_READY,
    VERIFYING_CONNECTION,
    FAILED,
}

internal data class VpnRecoveryState(
    val phase: VpnRecoveryPhase = VpnRecoveryPhase.DISCONNECTED,
    val attempt: Int = 0,
    val maximumAttempts: Int = 0,
    val generation: Long = 0L,
) {
    fun toUiPayload(): String = "${phase.name}|$attempt|$maximumAttempts"
}
