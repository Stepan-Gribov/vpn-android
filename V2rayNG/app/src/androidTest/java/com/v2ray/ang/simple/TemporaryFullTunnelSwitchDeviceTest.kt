package com.v2ray.ang.simple

import android.app.Service
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.ref.SoftReference
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class TemporaryFullTunnelSwitchDeviceTest {
    @Test fun turnRoutingRestartsInSameServiceInBothDirections() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("temporaryFullTunnel") == "true")
        check(android.os.Build.MODEL.contains("sdk"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertFalse(DaemonConnectionState.hasLiveService())
        val store = SimpleStore(context)
        val oldSplit = store.splitTunneling
        val oldExcluded = store.excludedApplications
        val oldProfile = MmkvManager.decodeSettingsString(SimpleRuntime.ACTIVE_ID)
        val oldServer = MmkvManager.getSelectServer()
        val oldDesired = SettingsManager.isConnectionDesired()
        val savedApps = listOf("com.example.excluded")
        val savedSplit = SplitTunnelSettings(SplitTunnelSettings.BYPASS).withEntries(
            SplitTunnelSettings.BYPASS, SplitTunnelImport.manual("203.0.113.0/24"),
        )
        val profile = SimpleConfiguration(
            mode = "turn", server = "192.0.2.14:56010", callLink = "https://vk.com/call/join/fixture",
            vlessID = "00000000-0000-4000-8000-000000000001",
        )
        val manager = CoreServiceManager
        val active = manager.javaClass.getDeclaredField("activeConnection").apply {
            isAccessible = true
        }.get(manager) as AtomicBoolean
        val switch = manager.javaClass.getDeclaredMethod("switchTemporaryFullTunnel", String::class.java).apply {
            isAccessible = true
        }
        val service = object : Service() {
            init { attachBaseContext(context) }
            override fun onBind(intent: Intent?) = null
        }
        data class Applied(val mode: String, val ips: List<String>, val exclusions: Set<String>)
        val applied = LinkedBlockingQueue<Applied>()
        val destroyed = AtomicBoolean(false)
        val failNextStart = AtomicBoolean(false)
        val control = object : ServiceControl {
            override fun getService() = service
            override fun startService() = Unit
            override fun stopService() {
                destroyed.set(true)
                manager.stopCoreLoop()
                manager.serviceControl = null
            }
            override fun prepareForRecovery() = manager.stopCoreLoop(keepForegroundForRecovery = true)
            override fun startRecoveryAttempt(): Boolean {
                if (failNextStart.getAndSet(false)) return false
                SimpleRuntime.refreshPreparedConfiguration(context)
                val split = Gson().fromJson(
                    MmkvManager.decodeSettingsString(SimpleRuntime.SPLIT_SETTINGS), SplitTunnelSettings::class.java,
                )
                val exclusions = MmkvManager.decodeSettingsStringSet(AppVpnExclusions.STAGED_KEY).orEmpty()
                active.set(true)
                applied.add(Applied(split.mode, split.networks, exclusions))
                return true
            }
            override fun vpnProtect(socket: Int) = true
        }
        try {
            store.save(profile)
            store.saveSettings(store.autoCaptcha, store.autoRecovery, savedSplit, excludedApps = savedApps)
            TemporaryFullTunnel.prepare("", false, 0)
            SimpleRuntime.stage(profile, store)
            manager.serviceControl = SoftReference(control)
            SettingsManager.setConnectionDesired(true)
            active.set(true)
            val full = Applied(SplitTunnelSettings.DISABLED, emptyList(), emptySet())
            val split = Applied(SplitTunnelSettings.BYPASS, listOf("203.0.113.0/24"), savedApps.toSet())
            for ((command, failFirst, expected) in listOf(
                Triple("on", false, full),
                Triple("off", true, full),
                Triple("off", false, split),
                Triple("on", true, split),
            )) {
                failNextStart.set(failFirst)
                val previous = TemporaryFullTunnel.currentLease(profile.id)
                instrumentation.runOnMainSync { switch.invoke(manager, command) }
                val result = applied.poll(12, TimeUnit.SECONDS)
                assertNotNull("TURN routing must restart after $command", result)
                assertEquals(expected, result)
                assertSame(control, manager.serviceControl?.get())
                assertFalse(destroyed.get())
                val deadline = SystemClock.elapsedRealtime() + 2_000
                while (manager.isSwitchingTemporaryFullTunnel() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(10)
                assertFalse(manager.isSwitchingTemporaryFullTunnel())
                assertEquals(expected == full, TemporaryFullTunnel.active(profile.id))
                if (failFirst) assertEquals(previous, TemporaryFullTunnel.currentLease(profile.id))
                assertTrue(SettingsManager.isConnectionDesired())
            }
        } finally {
            SettingsManager.setConnectionDesired(oldDesired)
            active.set(false)
            manager.serviceControl = null
            TemporaryFullTunnel.prepare("", false, 0)
            SimpleRuntime.clearPreparedConfiguration(oldProfile)
            oldServer?.let(MmkvManager::setSelectServer)
            store.saveSettings(store.autoCaptcha, store.autoRecovery, oldSplit, excludedApps = oldExcluded)
            store.remove(profile.id)
        }
    }
}
