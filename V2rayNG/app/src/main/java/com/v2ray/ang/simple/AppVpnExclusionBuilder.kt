package com.v2ray.ang.simple

import android.content.Context
import android.content.pm.PackageManager
import android.net.VpnService

internal object AppVpnExclusionBuilder {
    @Suppress("DEPRECATION")
    fun apply(builder: VpnService.Builder, context: Context, packages: List<String>): List<String> {
        val self=context.packageName
        val applied=mutableListOf<String>()
        (AppVpnExclusions.normalized(packages)+self).distinct().forEach { name ->
            try {
                // Some Android versions accept a missing name despite the Builder contract.
                context.packageManager.getApplicationInfo(name,0)
                builder.addDisallowedApplication(name); applied+=name
            }
            catch(_:PackageManager.NameNotFoundException) { /* Keep saved identity for reinstallation. */ }
        }
        return applied
    }
}
