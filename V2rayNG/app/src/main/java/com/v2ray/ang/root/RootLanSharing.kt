package com.v2ray.ang.root

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.MessageUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking


object RootLanSharing {

    private var lanSharingStarted = false
    private var lanShareJob: Job? = null
    private var startGeneration = 0L

    /**
     * Optional root feature: share the proxy with tethered LAN/USB clients while the
     * device itself stays on the VpnService. Runs a dedicated client hev-socks5-tunnel
     * off the main thread so the su calls don't block service startup. Root state is
     * process-local, so the VPN service process performs its own probe before setup.
     * The cheap, usually-false preference is checked first so the common path avoids it.
     */
    @Synchronized
    fun startClientSharing(context: Context): Boolean {
        if (!MmkvManager.decodeSettingsBool(AppConfig.PREF_ROOT_LAN_SHARING)) return false

        if (lanSharingStarted) {
            MessageUtil.sendMsg2UI(context, AppConfig.MSG_ROOT_LAN_SHARING_READY, "")
            return true
        }
        if (lanShareJob?.isActive == true) return true

        startGeneration += 1
        val generation = startGeneration
        lanShareJob = CoroutineScope(Dispatchers.IO).launch {
            val success = RootManager.isRootAvailable() &&
                RootProxyManager.startClientSharing(context)
            val current = synchronized(this@RootLanSharing) {
                if (generation != startGeneration) {
                    false
                } else {
                    lanSharingStarted = success
                    lanShareJob = null
                    true
                }
            }
            if (current) {
                MessageUtil.sendMsg2UI(
                    context,
                    if (success) {
                        AppConfig.MSG_ROOT_LAN_SHARING_READY
                    } else {
                        AppConfig.MSG_ROOT_LAN_SHARING_FAILURE
                    },
                    "",
                )
            }
        }
        return true
    }

    /**
     * Remove LAN/tethering sharing rules + helper before stopping the core. Wait for the
     * async setup to finish first, otherwise a stop during setup tears down before the
     * rules are installed and they leak (orphan FORWARD/policy-routing rules + client tun).
     */
    fun stopClientSharing(context: Context) {
        val job = synchronized(this) {
            if (!lanSharingStarted && lanShareJob == null) return
            startGeneration += 1
            lanSharingStarted = false
            lanShareJob.also { lanShareJob = null }
        }
        runBlocking { job?.cancelAndJoin() }
        RootProxyManager.stop(context)
    }
}
