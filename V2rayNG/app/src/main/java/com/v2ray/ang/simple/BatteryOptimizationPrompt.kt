package com.v2ray.ang.simple

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** One explanation per installation; only the user can grant the system exemption. */
internal object BatteryOptimizationPrompt {
    fun showOnce(activity: AppCompatActivity) {
        val preferences = activity.getSharedPreferences("simple-ui", Context.MODE_PRIVATE)
        if (preferences.getBoolean("battery_optimization_explained", false)) return
        val power = activity.getSystemService(PowerManager::class.java)
        preferences.edit().putBoolean("battery_optimization_explained", true).apply()
        if (power.isIgnoringBatteryOptimizations(activity.packageName)) return
        AlertDialog.Builder(activity)
            .setTitle("Работа VPN в фоне")
            .setMessage("Разрешите TnDtun работать без оптимизации батареи, чтобы Android реже прерывал VPN и восстановление соединения при выключенном экране. Это может увеличить расход заряда. Разрешение можно изменить позже в настройках Android.")
            .setPositiveButton("Разрешить") { _, _ -> openSystemRequest(activity) }
            .setNegativeButton("Не сейчас", null)
            .show()
    }

    private fun openSystemRequest(activity: AppCompatActivity) {
        val packageUri = Uri.parse("package:${activity.packageName}")
        val candidates = listOf(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri),
        )
        for (intent in candidates) {
            try {
                activity.startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) { /* Try this device's settings fallback. */ }
            catch (_: SecurityException) { /* Vendor does not allow this specific settings intent. */ }
        }
    }
}
