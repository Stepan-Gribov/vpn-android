package com.v2ray.ang.helper

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.v2ray.ang.R
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.extension.toast

/**
 * Helper for requesting permissions.
 */
class PermissionHelper(private val activity: AppCompatActivity) {
    private var permissionCallback: ((Boolean) -> Unit)? = null
    private var requestedPermission: String? = null

    private val permissionLauncher: ActivityResultLauncher<Array<String>> =
        activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val callback = permissionCallback
            val permission = requestedPermission
            permissionCallback = null
            requestedPermission = null
            callback?.invoke(permission != null && grants[permission] == true)
        }

    /**
     * Check the permission and request it if not granted.
     *
     * @param permissionType the type of permission
     * @param onGranted called when permission is granted (called immediately if already granted)
     */
    fun request(permissionType: PermissionType, onGranted: () -> Unit) {
        requestResult(permissionType) { isGranted ->
            if (isGranted) onGranted()
        }
    }

    /** Request a permission and report both grant and denial to the caller. */
    fun requestResult(permissionType: PermissionType, onResult: (Boolean) -> Unit) {
        val permission = permissionType.getPermission()
        if (ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED) {
            onResult(true)
        } else {
            permissionCallback = { isGranted ->
                if (isGranted) {
                    onResult(true)
                } else {
                    val message = "${activity.getString(R.string.toast_permission_denied)}  ${permissionType.getLabel()}"
                    activity.toast(message)
                    onResult(false)
                }
            }
            requestedPermission = permission
            // Android 12+ ignores a fine-location request made without coarse
            // location. A coarse-only grant still cannot start the hotspot.
            val permissions = if (permission == Manifest.permission.ACCESS_FINE_LOCATION) {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            } else {
                arrayOf(permission)
            }
            permissionLauncher.launch(permissions)
        }
    }
}
