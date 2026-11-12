package dev.codex.widget

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import dev.codex.widget.domain.*
import dev.codex.widget.widget.WidgetRenderer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClaudeWidgetTest {
    @Test fun `all Claude layouts render orange quota with correct identity in both languages`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sample = UsageSnapshot(UsageWindow(90, 20000), UsageWindow(15, 50000), null, null, 1000, "TEST", UsageProvider.CLAUDE)
        for (language in listOf("en", "es")) {
            Language.set(context, language)
            for (mode in WidgetMode.entries) {
                val bitmap = WidgetRenderer.bitmap(context, sample, 1000, 180f, 180f, mode)
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                assertTrue("Orange quota missing: $language $mode", pixels.count { it == 0xFFFFA164.toInt() } > 40)
                assertFalse(pixels.any { it == 0xFF65B4E5.toInt() || it == 0xFFF16C77.toInt() })
                val description = WidgetRenderer.description(context, sample, 1000, mode)
                assertTrue(description.contains("Claude")); assertFalse(description.contains("Codex"))
                assertFalse(description.contains("Saved resets") || description.contains("Reinicios guardados"))
                assertTrue(description.contains("90 %")); assertTrue(description.contains("15 %"))
                val file = File("build/outputs/claude-fixtures/$language-${mode.name.lowercase()}.png").apply { parentFile!!.mkdirs() }
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        val expired = sample.copy(fiveHours = UsageWindow(90, 999), weekly = null)
        assertTrue(WidgetRenderer.description(context, expired, 1000, WidgetMode.SQUARE).contains("— %"))
        val unknown = WidgetRenderer.bitmap(context, expired, 1000, 180f, 96f, WidgetMode.MINI)
        val pixels = IntArray(unknown.width * unknown.height)
        unknown.getPixels(pixels, 0, unknown.width, 0, 0, unknown.width, unknown.height)
        assertFalse(pixels.any { it == 0xFFFFA164.toInt() })
    }
}
