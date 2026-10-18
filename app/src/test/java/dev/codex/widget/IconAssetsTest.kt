package dev.codex.widget

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IconAssetsTest {
    @Test fun `notification mark is white on transparency and launcher has a black background`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for ((qualifier, size) in listOf("mdpi" to 24,"hdpi" to 36,"xhdpi" to 48,"xxhdpi" to 72,"xxxhdpi" to 96)) {
            val bitmap = Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888)
            ContextCompat.getDrawable(context,R.drawable.ic_notification)!!.apply { setBounds(0,0,size,size);draw(Canvas(bitmap)) }
            assertEquals(0,Color.alpha(bitmap.getPixel(0,0)))
            var visible=0
            for(y in 0 until size) for(x in 0 until size) if(Color.alpha(bitmap.getPixel(x,y))>128) {
                val pixel=bitmap.getPixel(x,y)
                // Canvas premultiplication can round white down by one channel level.
                assertTrue(Color.red(pixel)>=250 && Color.green(pixel)>=250 && Color.blue(pixel)>=250);visible++
            }
            assertTrue(visible>20)
            val file=File("build/outputs/icon-fixtures/$qualifier.png").apply { parentFile!!.mkdirs() }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        }
        val icon = ContextCompat.getDrawable(context,R.mipmap.ic_launcher)!!
        val bitmap=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888)
        icon.setBounds(0,0,128,128);icon.draw(Canvas(bitmap))
        assertEquals(Color.BLACK,bitmap.getPixel(64,22))
    }
}
