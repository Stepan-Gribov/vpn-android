package com.v2ray.ang.contracts

import android.app.Service
import android.os.ParcelFileDescriptor

interface ServiceControl {
    /**
     * Gets the service instance.
     * @return The service instance.
     */
    fun getService(): Service

    /**
     * Starts the service.
     */
    fun startService()

    /**
     * Stops the service.
     */
    fun stopService()

    /**
     * Tears down the current tunnel internals while keeping the foreground service alive.
     * VPN-backed services override this so Android cannot kill the reconnect scheduler.
     */
    fun prepareForRecovery(): Boolean = false

    /** Recreates the VPN interface, transport helper and core after [prepareForRecovery]. */
    fun startRecoveryAttempt(): Boolean = false

    /**
     * Protects the VPN socket.
     * @param socket The socket to protect.
     * @return True if the socket is protected, false otherwise.
     */
    fun vpnProtect(socket: Int): Boolean

    /**
     * Installs or removes forwarding for hotspot clients without restarting the core.
     * Only VPN-backed services support this; the defaults keep the other service modes
     * source-compatible and make an unsupported request fail explicitly.
     */
    fun startLanSharing(): Boolean = false

    fun stopLanSharing() = Unit

    /** Starts a non-Xray engine over the VPN interface owned by this service. */
    fun startAmneziaWgTunnel(vpnInterface: ParcelFileDescriptor, config: String): Boolean = false

    /** Stops the AmneziaWG engine, if one is active. */
    fun stopAmneziaWgTunnel() = Unit
}
