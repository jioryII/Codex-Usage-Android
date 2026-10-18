package dev.codex.widget.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.KeyguardManager
import android.os.PowerManager
import dev.codex.widget.domain.SyncPolicy
import androidx.work.*
import dev.codex.widget.domain.ReminderPolicy
import dev.codex.widget.domain.UsageSnapshot
import dev.codex.widget.graph
import dev.codex.widget.data.RefreshResult
import dev.codex.widget.widget.CodexWidgetProvider
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

object Scheduler {
    private fun connected() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    fun install(context: Context) {
        val manager = WorkManager.getInstance(context)
        manager.enqueueUniquePeriodicWork("codex-sync", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(connected())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        manager.enqueueUniquePeriodicWork("codex-labels", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RenderWorker>(15, TimeUnit.MINUTES).build())
    }
    fun refresh(context: Context, manual: Boolean = false) {
        WorkManager.getInstance(context).enqueueUniqueWork(if (manual) "codex-refresh-user" else "codex-refresh", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(connected())
                .setInputData(workDataOf("manual" to manual))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
    fun at(context: Context, time: Long, now: Long, reminder: Boolean = true) {
        // Names by server instant collapse repeated plans. Every job revalidates with the server.
        WorkManager.getInstance(context).enqueueUniqueWork("codex-${if (reminder) "at" else "retry"}-$time", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(connected())
                .setInitialDelay((time - now).coerceAtLeast(1), TimeUnit.SECONDS)
                .setInputData(workDataOf("reminder" to reminder))
                .addTag(if (reminder) "codex-expiry-check" else "codex-cooldown-check").build())
    }
    fun reminder(context: Context, snapshot: UsageSnapshot, now: Long) {
        snapshot.credits?.let { ReminderPolicy.next(it, now) }?.let { at(context, it, now) }
    }
    fun cancelReminders(context: Context) { WorkManager.getInstance(context).cancelAllWorkByTag("codex-expiry-check") }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        val graph = context.graph
        return try {
            val manual = inputData.getBoolean("manual", false)
            val reminder = inputData.getBoolean("reminder", false) && graph.repository.readState().optBoolean("reminders")
            val power = context.getSystemService(PowerManager::class.java)
            val unlocked = !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
            if (!SyncPolicy.shouldFetch(manual, reminder, power.isInteractive, unlocked, power.isDeviceIdleMode,
                    CodexWidgetProvider.hasWidgets(context), graph.appVisible)) {
                CodexWidgetProvider.renderAll(context)
                return Result.success()
            }
            val result = graph.repository.refresh(if (manual) SyncPolicy.MANUAL_INTERVAL else SyncPolicy.AUTOMATIC_INTERVAL) { snapshot, state ->
                val now = System.currentTimeMillis() / 1000
                graph.notifications.reconcile(snapshot, state, now) { graph.store.save(state) }
                if (state.optBoolean("reminders")) Scheduler.reminder(context, snapshot, now)
            }
            CodexWidgetProvider.renderAll(context)
            if (result != RefreshResult.SUCCESS && result != RefreshResult.CACHED) graph.notifications.clear()
            when (result) {
                RefreshResult.RETRY -> if (runAttemptCount < 4) Result.retry() else Result.success()
                RefreshResult.COOLDOWN -> {
                    Scheduler.at(context, graph.store.read().optLong("retryAt"), System.currentTimeMillis() / 1000, reminder = reminder)
                    Result.success()
                }
                RefreshResult.CACHED -> {
                    if (reminder) Scheduler.at(context, graph.repository.readState().optLong("lastAttempt") + SyncPolicy.AUTOMATIC_INTERVAL, System.currentTimeMillis() / 1000)
                    Result.success()
                }
                else -> Result.success()
            }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { CodexWidgetProvider.renderAll(context); Result.failure() }
    }
}

class RenderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result { CodexWidgetProvider.renderAll(applicationContext); return Result.success() }
}

class SystemReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)) return
        // WorkManager persists across boot; reinstall is idempotent and recalculates changed clocks.
        Scheduler.install(context)
        CodexWidgetProvider.renderAll(context)
        Scheduler.refresh(context)
    }
}
