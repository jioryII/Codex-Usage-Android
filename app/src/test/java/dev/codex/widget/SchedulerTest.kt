package dev.codex.widget

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dev.codex.widget.sync.Scheduler
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SchedulerTest {
    @Test fun `periodic and expiry work remain unique and respect network constraints`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        val manager = WorkManager.getInstance(context)
        Scheduler.install(context)
        val first = manager.getWorkInfosForUniqueWork("codex-sync").get().single()
        Scheduler.install(context)
        assertEquals(first.id, manager.getWorkInfosForUniqueWork("codex-sync").get().single().id)
        assertEquals(NetworkType.CONNECTED, first.constraints.requiredNetworkType)
        assertEquals(900_000L, first.periodicityInfo!!.repeatIntervalMillis)
        Scheduler.at(context, 2000, 1000)
        Scheduler.at(context, 2000, 1000)
        assertEquals(1, manager.getWorkInfosForUniqueWork("codex-at-2000").get().size)
        Scheduler.cancelReminders(context)
        assertEquals(WorkInfo.State.CANCELLED,
            manager.getWorkInfosForUniqueWork("codex-at-2000").get().single().state)
    }
}
