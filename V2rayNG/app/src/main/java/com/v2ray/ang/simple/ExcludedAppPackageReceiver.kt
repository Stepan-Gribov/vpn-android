package com.v2ray.ang.simple

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.handler.MmkvManager

/** An uninstall/reinstall can change UID. Rebuild only our active VPN's UID exclusions. */
class ExcludedAppPackageReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        if(intent.action !in listOf(Intent.ACTION_PACKAGE_ADDED,Intent.ACTION_PACKAGE_REMOVED) ||
            intent.getBooleanExtra(Intent.EXTRA_REPLACING,false)) return
        val name=intent.data?.schemeSpecificPart ?: return
        if(MmkvManager.getSelectServer()!=SimpleRuntime.STAGED_GUID || !DaemonConnectionState.hasLiveService()) return
        // Use the active TUN's staged list; a Cancelled settings draft has no effect.
        if(name in MmkvManager.decodeSettingsStringSet(AppVpnExclusions.STAGED_KEY).orEmpty()) {
            CoreServiceManager.restartVService(context)
        }
    }
}
