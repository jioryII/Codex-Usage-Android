package dev.codex.widget

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
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
import java.time.Instant

/** Native production rendering at export resolution, never enlarged from a small screenshot. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DocumentationRenderingTest {
    @Test fun `export sharp English documentation images without changing production bitmap limits`() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        Language.set(context,"en")
        val now=Instant.parse("2026-10-17T12:00:00Z").epochSecond
        val sample=UsageSnapshot(UsageWindow(89,now+7200),UsageWindow(42,now+86400),2,
            listOf(ResetCredit("fixture-a","available",now+3*86400),ResetCredit("fixture-b","available",now+7*86400)),
            now,"SYNTHETIC DOCUMENTATION FIXTURE")
        val gallery=Bitmap.createBitmap(1900,2020,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(gallery).apply { drawColor(0xFF101215.toInt()) }
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=0xFFF1F1F3.toInt();textSize=60f;typeface=Typeface.create("sans-serif",Typeface.BOLD) }
        canvas.drawText("Codex Usage",70f,90f,paint)
        paint.textSize=30f;paint.color=0xFFA2A2AA.toInt();paint.typeface=Typeface.create("sans-serif",Typeface.NORMAL)
        canvas.drawText("Four native Android widgets",70f,145f,paint)
        for ((mode,width,height) in listOf(Triple(WidgetMode.SQUARE,160,152),Triple(WidgetMode.SLIM,180,117),
                Triple(WidgetMode.MINI,188,100),Triple(WidgetMode.EXTENDED,180,180))) {
            val snapshot=sample
            val bitmap=WidgetRenderer.bitmap(context,snapshot,now,width.toFloat(),height.toFloat(),mode,exportScale=4f)
            assertEquals(width*4,bitmap.width);assertEquals(height*4,bitmap.height)
            val x=if(mode==WidgetMode.SQUARE || mode==WidgetMode.MINI) 70f else 1020f
            val top=if(mode==WidgetMode.SQUARE || mode==WidgetMode.SLIM) 225f else 1030f
            val title=when(mode) { WidgetMode.SQUARE -> "Square";WidgetMode.SLIM -> "Slim";WidgetMode.MINI -> "Arc";WidgetMode.EXTENDED -> "Detail" }
            paint.textSize=36f;paint.color=0xFFF1F1F3.toInt()
            canvas.drawText(title,x,top,paint)
            canvas.drawBitmap(bitmap,x,top+45f,null)
            val file=File("build/outputs/documentation/${mode.name.lowercase()}.png").apply { parentFile!!.mkdirs() }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        }
        paint.textSize=25f;paint.color=0xFFA2A2AA.toInt()
        canvas.drawText("Illustrative data · Native 4× rendering",70f,1970f,paint)
        File("build/outputs/documentation/widgets.png").outputStream().use { gallery.compress(Bitmap.CompressFormat.PNG,100,it) }
        val production=WidgetRenderer.bitmap(context,sample,now,560f,560f,WidgetMode.EXTENDED)
        assertTrue(production.width<=512 && production.height<=512)
    }
}
