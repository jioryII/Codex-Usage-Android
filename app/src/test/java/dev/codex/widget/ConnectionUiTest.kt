package dev.codex.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import dev.codex.widget.data.*
import dev.codex.widget.domain.UsageSnapshot
import dev.codex.widget.domain.UsageWindow
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Before
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ConnectionTestApplication : CodexApplication() {
    val deviceRequests = AtomicInteger()
    val usageRequests = AtomicInteger()
    override val graph by lazy {
        AppGraph(this, MemoryStore(), HttpTransport { url, _, _, _ -> when {
            url.endsWith("/usercode") -> { deviceRequests.incrementAndGet(); error("A second login was started") }
            url.endsWith("/deviceauth/token") -> throw HttpFailure(403)
            url.endsWith("/usage") -> {
                usageRequests.incrementAndGet()
                val root = JSONObject(UsageTest.INTERNAL)
                val rate = root.getJSONObject("rate_limit")
                rate.getJSONObject("primary_window").put("reset_at", System.currentTimeMillis() / 1000 + 86400)
                rate.getJSONObject("secondary_window").put("reset_at", System.currentTimeMillis() / 1000 + 7200)
                root.toString()
            }
            url.endsWith("/rate-limit-reset-credits") -> """{"available_count":0,"credits":[]}"""
            else -> throw HttpFailure(404)
        } })
    }
    // Production Application startup is exercised separately. UI tests control persisted fixtures.
    override fun onCreate() { }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ConnectionTestApplication::class, qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConnectionUiTest {
    private val app = ApplicationProvider.getApplicationContext<ConnectionTestApplication>()
    private fun texts(view: View): List<String> = when {
        view.visibility != View.VISIBLE -> emptyList()
        view is TextView -> listOf(view.text.toString())
        view is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }
    private fun await(activity: SetupActivity, part: String) {
        val until = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            if (texts(activity.window.decorView).any { it.contains(part) }) return
            Thread.sleep(10)
        }
        fail("UI did not show expected state: $part. Visible text: ${texts(activity.window.decorView)}")
    }
    private fun capture(activity: SetupActivity, name: String) {
        val view = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        view.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1040, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 360, 1040)
        view.scrollTo(0, 0)
        val bitmap = Bitmap.createBitmap(360, 1040, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val file = File("build/outputs/connection-fixtures/$name.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Before fun language() { Language.set(app,"es") }
    @After fun finish() {
        WorkManager.getInstance(app).cancelAllWork().result.get(5, TimeUnit.SECONDS)
        app.graph.scope.cancel()
    }

    @Test fun `browser return through Activity recreation preserves code and offers a copy and open action`() {
        val now = System.currentTimeMillis() / 1000
        runBlocking { app.graph.repository.transaction {
            it.put(LoginManager.PENDING, JSONObject().put("id", "fixture-device").put("code", "TEST-1234")
                .put("interval", 5).put("expiresAt", now + 900).put("nextPollAt", now + 900))
        } }
        val controller = Robolectric.buildActivity(SetupActivity::class.java).setup()
        try {
            await(controller.get(), "TEST-1234")
            capture(controller.get(), "pending-synthetic")
            controller.recreate()
            await(controller.get(), "TEST-1234")
            val visible = texts(controller.get().window.decorView)
            assertTrue(visible.contains("Copiar código y abrir OpenAI"))
            assertFalse(visible.contains("Continuar con ChatGPT"))
            assertEquals(0, app.deviceRequests.get())
            assertEquals("fixture-device", app.graph.store.read().getJSONObject(LoginManager.PENDING).getString("id"))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `saved session displays connected state and hides all sign-in controls after recreation`() {
        val now = System.currentTimeMillis() / 1000
        runBlocking {
            app.graph.repository.signIn(Session("test-access", "test-refresh", "acct", now + 7200, "persona@example.test"))
            app.graph.repository.transaction {
                it.put("snapshot", SnapshotCodec.encode(UsageSnapshot(UsageWindow(72, now + 7200), UsageWindow(22, now + 86400), 0, emptyList(), now, "TEST FIXTURE")))
                it.put("lastSuccess", now)
            }
        }
        val controller = Robolectric.buildActivity(SetupActivity::class.java).setup()
        try {
            await(controller.get(), "SESIÓN INICIADA")
            capture(controller.get(), "connected-synthetic")
            controller.recreate()
            await(controller.get(), "SESIÓN INICIADA")
            val visible = texts(controller.get().window.decorView)
            assertFalse(visible.any { it.contains("Así se verá") || it.contains("Los valores se muestran") })
            assertTrue(visible.contains("Añadir widget"))
            assertFalse(visible.contains("Continuar con ChatGPT"))
            assertFalse(visible.contains("Copiar código y abrir OpenAI"))
            assertEquals(0, app.deviceRequests.get())
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun `English session screen stays minimal and language survives sign out`() {
        Language.set(app,"en")
        val now=System.currentTimeMillis()/1000
        runBlocking { app.graph.repository.signIn(Session("test","refresh","acct",now+7200)) }
        val controller=Robolectric.buildActivity(SetupActivity::class.java).setup()
        try {
            await(controller.get(),"SIGNED IN")
            val visible=texts(controller.get().window.decorView)
            assertTrue(visible.contains("Add widget"));assertTrue(visible.contains("Sign out"))
            assertFalse(visible.any { it.contains("SESIÓN") || it.contains("preview") })
            capture(controller.get(),"connected-en-synthetic")
            runBlocking { app.graph.repository.signOut() }
            assertEquals("en",Language.code(app))
        } finally { controller.pause().stop().destroy() }
    }

}
