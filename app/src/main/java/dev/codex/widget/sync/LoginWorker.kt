package dev.codex.widget.sync

import android.content.Context
import android.os.SystemClock
import androidx.work.*
import dev.codex.widget.data.LoginPhase
import dev.codex.widget.data.LoginStep
import dev.codex.widget.graph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

object LoginWork {
    const val NAME = "codex-device-authorization"
    fun enqueue(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<LoginWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS).build())
    }
    fun cancel(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(NAME) }
}

/** Bounded below WorkManager's ten-minute limit; continuation uses the encrypted pending flow. */
class LoginWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = applicationContext.graph
        val deadline = SystemClock.elapsedRealtime() + TimeUnit.MINUTES.toMillis(8)
        return try {
            while (SystemClock.elapsedRealtime() < deadline) {
                when (val step = graph.login.step()) {
                    LoginStep.Done -> {
                        if (graph.login.view().phase == LoginPhase.SIGNED_IN) {
                            Scheduler.install(applicationContext)
                            Scheduler.refresh(applicationContext)
                        }
                        return Result.success()
                    }
                    LoginStep.Retry -> return Result.retry()
                    is LoginStep.Wait -> delay(step.seconds.coerceIn(1, 30) * 1000)
                }
            }
            Result.retry()
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { Result.failure() }
    }
}
