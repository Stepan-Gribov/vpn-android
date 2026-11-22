package com.v2ray.ang

import android.content.Context
import android.os.Build
import android.webkit.WebView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.multidex.MultiDexApplication
import androidx.work.Configuration
import androidx.work.WorkManager
import com.tencent.mmkv.MMKV
import com.v2ray.ang.AppConfig.ANG_PACKAGE
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.simple.PanelSyncScheduler

class AngApplication : MultiDexApplication() {
    companion object {
        lateinit var application: AngApplication
    }

    /**
     * Attaches the base context to the application.
     * @param base The base context.
     */
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        application = this
    }

    private val workManagerConfiguration: Configuration = Configuration.Builder()
        // WorkManager's restart/force-stop recovery must run in the actual main process.
        .setDefaultProcessName(ANG_PACKAGE)
        .build()

    /**
     * Initializes the application.
     */
    override fun onCreate() {
        super.onCreate()

        // WebView is used by both the UI and VPN daemon for background VK verification.
        // Android 9+ requires a separate storage directory for every secondary process.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val processName = getProcessName()
            if (processName != packageName) {
                WebView.setDataDirectorySuffix(
                    processName.substringAfter(':').replace(Regex("[^A-Za-z0-9_.-]"), "_"),
                )
            }
        }

        MMKV.initialize(this)

        // Ensure critical preference defaults are present in MMKV early
        // SimpleBLT sends all traffic through the selected tunnel. Both the UI and
        // VPN process must avoid restoring the full client's geodata routing presets.
        SettingsManager.initApp(this, initializeRoutingPresets = false)
        val mainProcess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) getProcessName() == packageName
        else runCatching { java.io.File("/proc/self/cmdline").readText().substringBefore('\u0000') == packageName }
            .getOrDefault(false)
        if (mainProcess) {
            WorkManager.initialize(this, workManagerConfiguration)
            WorkManager.getInstance(this).cancelUniqueWork("tndtun-panel-sync")
            PanelSyncScheduler.restore(this)
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    PanelSyncScheduler.restore(this@AngApplication, catchUp = true)
                }
            })
        }
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO)

        es.dmoral.toasty.Toasty.Config.getInstance()
            .setGravity(android.view.Gravity.BOTTOM, 0, 300)
            .apply()
    }
}
