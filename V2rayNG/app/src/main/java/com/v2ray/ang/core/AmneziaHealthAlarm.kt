package com.v2ray.ang.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import java.util.UUID

/** One backup wakeup for the running daemon, never permission to start a VPN. */
internal object AmneziaHealthAlarm {
    private var alarmManager: AlarmManager? = null
    private var pending: PendingIntent? = null
    private var token: String? = null
    private var handoff: PowerManager.WakeLock? = null

    @Synchronized fun schedule(context: Context, delayMillis: Long): Boolean {
        cancel()
        return try {
            val manager = context.getSystemService(AlarmManager::class.java) ?: return false
            val nextToken = UUID.randomUUID().toString()
            val intent = Intent(context, AmneziaHealthAlarmReceiver::class.java)
                .setData(Uri.parse("tndtun-health://wake/$nextToken"))
            val operation = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            alarmManager = manager
            pending = operation
            token = nextToken
            val at = SystemClock.elapsedRealtime() + delayMillis
            var exact = false
            try {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()) {
                    manager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, operation)
                    exact = true
                }
            } catch (_: SecurityException) { /* Permission can change between check and set. */ }
            // No new permission dialog. Restricted devices retain a best-effort
            // wakeup as well as the regular coroutine timer and network events.
            if (!exact) manager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, operation)
            LogUtil.i(AppConfig.TAG, "[AMNEZIA-WAKE] Scheduled ${if (exact) "exact" else "inexact"} wakeup at $at")
            true
        } catch (e: Exception) {
            cancel()
            LogUtil.w(AppConfig.TAG, "[AMNEZIA-WAKE] Wakeup unavailable; retaining timer", e)
            false
        }
    }

    /** RAM token dies with the daemon. An old broadcast cannot revive a session. */
    @Synchronized fun consume(context: Context, incoming: String?): Boolean {
        if (incoming == null || incoming != token) return false
        cancelPending()
        releaseHandoff()
        try {
            handoff = context.getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${AppConfig.ANG_PACKAGE}:health-handoff")
                ?.apply { setReferenceCounted(false); acquire(10_000L) }
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "[AMNEZIA-WAKE] Wake handoff unavailable", e)
        }
        LogUtil.i(AppConfig.TAG, "[AMNEZIA-WAKE] Delivered at ${SystemClock.elapsedRealtime()}")
        return true
    }

    /** Keep a delivered handoff lock until the loop owns its recovery lock. */
    @Synchronized fun cancelPending() {
        token = null
        pending?.let { operation ->
            runCatching { alarmManager?.cancel(operation) }
            runCatching { operation.cancel() }
        }
        pending = null
        alarmManager = null
    }

    @Synchronized fun releaseHandoff() {
        handoff?.let { lock -> if (lock.isHeld) runCatching { lock.release() } }
        handoff = null
    }

    @Synchronized fun cancel() {
        cancelPending()
        releaseHandoff()
    }
}

/** Explicit, non-exported and in the same process as CoreVpnService. */
class AmneziaHealthAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (AmneziaHealthAlarm.consume(context, intent.data?.lastPathSegment)) {
            CoreServiceManager.onAmneziaHealthAlarm()
        }
    }
}
