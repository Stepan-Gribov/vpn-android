package com.v2ray.ang.simple

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

internal object PanelSyncScheduler {
    internal const val DUE_AT = "subscriptionDueAt"
    internal const val TAG = "tndtun-panel-automatic-sync"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scheduling = Mutex()

    fun restore(context: Context, catchUp: Boolean = false) {
        val app = context.applicationContext
        scope.launch {
            try {
                val next = reconcile(app) ?: return@launch
                if (catchUp && next <= System.currentTimeMillis() / 1000 &&
                    app.getSystemService(ConnectivityManager::class.java)?.activeNetwork != null
                ) PanelManager.refreshAutomatically(app).await()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Log.w("TnDtun-Panel", "Could not restore subscription update schedule")
            }
        }
    }

    /** The encrypted subscription state is authoritative; a stale job rechecks it before any request. */
    internal suspend fun reconcile(context: Context): Long? = withContext(Dispatchers.IO) {
        scheduling.withLock {
            val next = SimpleStore(context).subscriptions.mapNotNull { it.automaticSyncAt }.minOrNull()
            val workManager = WorkManager.getInstance(context)
            val current = workManager.getWorkInfosByTag(TAG).get()
            val name = next?.let { "$TAG:$it" }
            if (next != null && current.none { name in it.tags && !it.state.isFinished }) {
                val request = OneTimeWorkRequestBuilder<PanelSyncWorker>()
                    .addTag(TAG)
                    .addTag(checkNotNull(name))
                    .setInputData(workDataOf(DUE_AT to next))
                    .setInitialDelay((next - System.currentTimeMillis() / 1000).coerceAtLeast(0), TimeUnit.SECONDS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
                workManager.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request).result.get()
            }
            // A running job can finish normally and may be the caller scheduling its successor.
            for (work in current) {
                if (work.state == WorkInfo.State.ENQUEUED && name !in work.tags) {
                    workManager.cancelWorkById(work.id).result.get()
                }
            }
            next
        }
    }
}

class PanelSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val scheduledAt = inputData.getLong(PanelSyncScheduler.DUE_AT, 0)
        if (scheduledAt <= 0) return Result.success() // Migrated jobs from manual-only builds.
        return try {
            PanelManager.refreshAutomatically(applicationContext).await()
            // When a second batch still has the same deadline, KEEP is protecting this running job.
            if (PanelSyncScheduler.reconcile(applicationContext) == scheduledAt) Result.retry() else Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
