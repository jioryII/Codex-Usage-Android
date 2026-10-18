package dev.codex.widget

import android.os.Looper
import org.robolectric.Shadows.shadowOf
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = CodexApplication::class)
class StartupTest {
    private fun activityGraphCleanup(activity: SetupActivity) { activity.graph.scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
    @Test fun `real application starts settings while disconnected without fake data`() {
        Language.set(androidx.test.core.app.ApplicationProvider.getApplicationContext(), "es")
        val controller = Robolectric.buildActivity(SetupActivity::class.java).setup()
        try {
            val activity = controller.get()
            fun collect(view: View): List<String> = when (view) {
                is TextView -> listOf(view.text.toString())
                is ViewGroup -> (0 until view.childCount).flatMap { collect(view.getChildAt(it)) }
                else -> emptyList()
            }
            val until = System.currentTimeMillis() + 8000
            while (System.currentTimeMillis() < until && !collect(activity.window.decorView).contains("SIN CONECTAR")) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
            val content = collect(activity.window.decorView).joinToString("\n")
            assertTrue(content.contains("Continuar con ChatGPT"))
            assertTrue(content.contains("SIN CONECTAR"))
            assertFalse(activity.graph.store.read().has("snapshot"))
        } finally { controller.pause().stop().destroy(); activityGraphCleanup(controller.get()) }
    }
}
