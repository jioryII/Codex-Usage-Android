package dev.codex.widget

import android.app.KeyguardManager
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.workDataOf
import androidx.work.testing.TestListenableWorkerBuilder
import dev.codex.widget.data.Session
import dev.codex.widget.sync.SyncWorker
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=ConnectionTestApplication::class)
class SyncWorkerIntegrationTest {
    private val app=ApplicationProvider.getApplicationContext<ConnectionTestApplication>()
    private fun signIn(reminders: Boolean=false)=runBlocking {
        app.graph.repository.signIn(Session("fixture","refresh","acct",System.currentTimeMillis()/1000+7200))
        app.graph.repository.setReminders(reminders)
    }
    @After fun cleanup() {
        WorkManager.getInstance(app).cancelAllWork().result.get(5,TimeUnit.SECONDS)
        app.graph.scope.cancel()
    }
    @Test fun `production worker makes no usage request with screen off and no manual action`()=runBlocking {
        signIn()
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(false)
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        TestListenableWorkerBuilder<SyncWorker>(app).build().doWork()
        assertEquals(0,app.usageRequests.get());assertFalse(app.graph.store.read().has("lastAttempt"))
    }
    @Test fun `opted-in expiry check revalidates during screen off while disabled reminders do not`()=runBlocking {
        signIn()
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(false)
        val input=workDataOf("reminder" to true)
        TestListenableWorkerBuilder<SyncWorker>(app).setInputData(input).build().doWork()
        assertEquals(0,app.usageRequests.get())
        app.graph.repository.setReminders(true)
        TestListenableWorkerBuilder<SyncWorker>(app).setInputData(input).build().doWork()
        assertEquals(1,app.usageRequests.get());assertTrue(app.graph.store.read().has("lastSuccess"))
    }
    @Test fun `several explicit refresh workers share persistent request interval`()=runBlocking {
        signIn()
        repeat(3) {
            TestListenableWorkerBuilder<SyncWorker>(app).setInputData(workDataOf("manual" to true)).build().doWork()
        }
        assertEquals(1,app.usageRequests.get())
    }
}
