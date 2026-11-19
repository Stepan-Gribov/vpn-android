package com.v2ray.ang.core

/** Common watchdog/retry policy, separate from transport keepalive and manual startup. */
internal object TurnRecoveryPolicy {
    fun enabled(setting: Boolean,currentTurn: Boolean,selectedTurn: Boolean)=setting && currentTurn && selectedTurn

    enum class FailedRepairAction { IGNORE, KEEP_TUNNEL, RECONNECT }

    /** Re-read the setting after the bounded repair: turning automation off is
     * not a user request to disconnect the existing VPN. */
    fun afterFailedRepair(requestCurrent: Boolean, recoveryEnabled: Boolean): FailedRepairAction = when {
        !requestCurrent -> FailedRepairAction.IGNORE
        recoveryEnabled -> FailedRepairAction.RECONNECT
        else -> FailedRepairAction.KEEP_TUNNEL
    }
}
