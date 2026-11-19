package com.v2ray.ang.enums

import android.Manifest
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Permission types used in the app, handling API level differences.
 */
enum class PermissionType {
    /** Camera permission (used for scanning QR codes) */
    CAMERA {
        override fun getPermission(): String = Manifest.permission.CAMERA
    },

    /** Notification permission (Android 13+) */
    POST_NOTIFICATIONS {
        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        override fun getPermission(): String = Manifest.permission.POST_NOTIFICATIONS
    },

    /** Local network access permission (Android 17+) */
    ACCESS_LOCAL_NETWORK {
        @RequiresApi(Build.VERSION_CODES.CINNAMON_BUN)
        override fun getPermission(): String = Manifest.permission.ACCESS_LOCAL_NETWORK
    },

    /** Fine location (Android 8.0-12, required to start a local-only hotspot) */
    ACCESS_FINE_LOCATION {
        override fun getPermission(): String = Manifest.permission.ACCESS_FINE_LOCATION
    },

    /** Nearby Wi-Fi devices (Android 13+, required to start a local-only hotspot) */
    NEARBY_WIFI_DEVICES {
        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        override fun getPermission(): String = Manifest.permission.NEARBY_WIFI_DEVICES
    };

    /** Return the actual Android permission string */
    abstract fun getPermission(): String

    /** Return a human-readable label for the permission */
    fun getLabel(): String {
        return when (this) {
            CAMERA -> "Camera"
            POST_NOTIFICATIONS -> "Notification"
            ACCESS_LOCAL_NETWORK -> "Local Network"
            ACCESS_FINE_LOCATION -> "Location"
            NEARBY_WIFI_DEVICES -> "Nearby Wi-Fi devices"
        }
    }
}