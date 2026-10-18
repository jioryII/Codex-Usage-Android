package dev.codex.widget

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import dev.codex.widget.domain.*
import dev.codex.widget.notify.CreditNotifications
import dev.codex.widget.widget.WidgetRenderer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "mdpi")
class AndroidIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun texts(root: View): List<String> = when (root) {
        is TextView -> listOf(root.text.toString()).filter { it.isNotBlank() }
        is ViewGroup -> (0 until root.childCount).flatMap { texts(root.getChildAt(it)) }
        else -> emptyList()
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `four production widget variants inflate in both languages at supported compact sizes`() {
        val sample = UsageSnapshot(UsageWindow(92, 7200), UsageWindow(22, 86400), 3,
            listOf(ResetCredit("a","available",700000),ResetCredit("b","available",800000),ResetCredit("c","available",null)),0,"TEST FIXTURE")
        for(lang in listOf("en","es")) {
            Language.set(context,lang)
            for ((width,height,mode) in listOf(Triple(100,100,WidgetMode.SQUARE),Triple(120,114,WidgetMode.SQUARE),
                Triple(100,70,WidgetMode.SLIM),Triple(120,78,WidgetMode.SLIM),Triple(120,64,WidgetMode.MINI),Triple(160,86,WidgetMode.MINI),Triple(170,180,WidgetMode.EXTENDED),Triple(220,300,WidgetMode.EXTENDED))) {
                val view = WidgetRenderer.views(context,sample,0,width.toFloat(),height.toFloat(),mode).apply(context,null)
                view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
                view.layout(0,0,width,height)
                val image=view.findViewById<android.widget.ImageView>(R.id.widget_image)
                assertNotNull(image.drawable)
                val accessible=image.contentDescription.toString()
                assertTrue(accessible.contains(if(lang=="es") "92 % restante" else "92 % left"))
                assertEquals(mode==WidgetMode.SQUARE || mode==WidgetMode.EXTENDED,accessible.contains(if(lang=="es") "Reinicia" else "Resets in"))
                assertEquals(mode!=WidgetMode.MINI,accessible.contains("Codex"))
                assertEquals(mode==WidgetMode.EXTENDED,accessible.contains(if(lang=="es") "Reinicios guardados" else "Saved resets"))
                val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                val file=File("build/outputs/widget-fixtures/${mode.name.lowercase()}-${lang}-${width}x${height}.png").apply { parentFile!!.mkdirs() }
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            }
        }
        for(percent in listOf(100,75,74,50,49,25,24,0)) for(mode in listOf(WidgetMode.SLIM,WidgetMode.MINI)) {
            val value=sample.copy(fiveHours=UsageWindow(percent,7200),weekly=UsageWindow(percent,86400))
            val bitmap=WidgetRenderer.bitmap(context,value,0,160f,if(mode==WidgetMode.MINI) 85f else 104f,mode)
            val file=File("build/outputs/widget-fixtures/color-${mode.name.lowercase()}-$percent.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `picker previews contain placeholders in both languages and never pretend to be real account data`() {
        for (lang in listOf("en","es")) {
            Language.set(context,lang)
            for ((mode,width,height) in listOf(Triple(WidgetMode.SQUARE,240,228),Triple(WidgetMode.SLIM,240,156),
                    Triple(WidgetMode.MINI,240,128),Triple(WidgetMode.EXTENDED,280,300))) {
                val bitmap=WidgetRenderer.bitmap(context,null,0,width.toFloat(),height.toFloat(),mode)
                val file=File("build/outputs/widget-previews/$lang-${mode.name.lowercase()}.png").apply { parentFile!!.mkdirs() }
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                assertFalse(WidgetRenderer.description(context,null,0,mode).contains("100 %"))
            }
        }
    }

    @Test fun `unconnected and expired widgets contain unknown values without fake quota or expiry`() {
        Language.set(context,"en")
        val description=WidgetRenderer.description(context,null,100,WidgetMode.SQUARE)
        assertEquals("Codex. 5 hours: — % left. Resets in —. Weekly: — % left. Resets in —",description)
        val snapshot=UsageSnapshot(UsageWindow(100,50),null,1,null,0,"test")
        assertFalse(WidgetRenderer.description(context,snapshot,100,WidgetMode.MINI).contains("100"))
        val lines=WidgetRenderer.creditLines(context,snapshot,100,3)
        assertTrue(lines.contains("Expiry unavailable"))
        assertFalse(lines.any { it.contains("1970") })
        val stale=snapshot.copy(availableCount=3,credits=listOf(ResetCredit("expired","available",50),ResetCredit("valid","available",300)))
        val staleLines=WidgetRenderer.creditLines(context,stale,100,3)
        assertTrue(staleLines.contains("Availability unknown"))
        assertFalse(staleLines.contains("3 available"))
        Language.set(context,"es")
        assertTrue(WidgetRenderer.creditLines(context,snapshot,100,3).contains("1 disponible"))
    }

    @Test fun `real Android notification manager deduplicates persists and cancels on redemption`() {
        val notifications = CreditNotifications(context)
        notifications.createChannel()
        shadowOf(context as Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(notifications.allowed())
        val manager = context.getSystemService(NotificationManager::class.java)
        val start = 2_000_000L - ReminderPolicy.WINDOW
        val credits = listOf(ResetCredit("a", "available", 2_000_000), ResetCredit("b", "available", 2_000_100))
        val snapshot = UsageSnapshot(null, null, 2, credits, start, "TEST FIXTURE")
        val store = MemoryStore()
        var state = JSONObject().put("reminders", true)
        notifications.reconcile(snapshot, state, start + 100) { store.save(state) }
        assertEquals(2, manager.activeNotifications.size)
        assertEquals(2, state.getJSONObject("ledger").length())
        // A fresh manager/state instance emulates process restart; no duplicate is delivered.
        state = store.read()
        CreditNotifications(context).reconcile(snapshot, state, start + 200) { store.save(state) }
        assertEquals(2, manager.activeNotifications.size)
        notifications.reconcile(snapshot.copy(credits = listOf(credits[1])), state, start + 86401) { store.save(state) }
        assertEquals(1, manager.activeNotifications.size)
        notifications.reconcile(snapshot.copy(credits = emptyList()), state, start + 86402) { store.save(state) }
        assertEquals(0, manager.activeNotifications.size)
    }

    @Test fun `missing expiry and disabled permission preference never produce a notification`() {
        val notifications = CreditNotifications(context)
        notifications.createChannel()
        shadowOf(context as Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(notifications.allowed())
        val snapshot = UsageSnapshot(null, null, 1, listOf(ResetCredit("a", "available", null)), 100, "TEST FIXTURE")
        notifications.reconcile(snapshot, JSONObject().put("reminders", true), 100) { }
        val manager = context.getSystemService(NotificationManager::class.java)
        assertEquals(0, manager.activeNotifications.size)
        notifications.reconcile(snapshot.copy(credits = listOf(ResetCredit("a", "available", 200))),
            JSONObject().put("reminders", false), 100) { }
        assertEquals(0, manager.activeNotifications.size)
    }
    @Test fun `notification language changes in place without duplicating delivered credits`() {
        Language.set(context,"es")
        val notifications=CreditNotifications(context);notifications.createChannel()
        shadowOf(context as Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val time=2_000_000L-ReminderPolicy.WINDOW
        val snapshot=UsageSnapshot(null,null,1,listOf(ResetCredit("locale","available",2_000_000)),time,"test")
        val state=JSONObject().put("reminders",true)
        notifications.reconcile(snapshot,state,time) { }
        val manager=context.getSystemService(NotificationManager::class.java)
        assertEquals(1,manager.activeNotifications.size)
        assertEquals("Un reinicio de Codex vence pronto",manager.activeNotifications.single().notification.extras.getString(android.app.Notification.EXTRA_TITLE))
        val ledger=state.getJSONObject("ledger").toString()
        Language.set(context,"en");notifications.updateLanguage()
        assertEquals(1,manager.activeNotifications.size)
        assertEquals("Saved Codex reset expires soon",manager.activeNotifications.single().notification.extras.getString(android.app.Notification.EXTRA_TITLE))
        assertEquals(ledger,state.getJSONObject("ledger").toString())
    }

    @Test fun `fresh installs start in English even on a Spanish device and retain an explicit preference`() {
        context.getSharedPreferences("presentation",Context.MODE_PRIVATE).edit().clear().commit()
        val configuration=android.content.res.Configuration(context.resources.configuration).apply { setLocale(java.util.Locale.forLanguageTag("es")) }
        val spanishDevice=context.createConfigurationContext(configuration)
        assertEquals("en",Language.code(spanishDevice))
        assertEquals("en",Language.context(spanishDevice).resources.configuration.locales[0].language)
        Language.set(context,"es")
        assertEquals("es",Language.code(spanishDevice))
    }
    @Test fun `all advertised variants reject the old icon-size minimum`() {
        val namespace="http://schemas.android.com/apk/res/android"
        for(id in listOf(R.xml.codex_widget_info,R.xml.codex_widget_slim_info,R.xml.codex_widget_mini_info,R.xml.codex_widget_extended_info)) {
            context.resources.getXml(id).use { xml ->
                while(xml.eventType!=org.xmlpull.v1.XmlPullParser.START_TAG) xml.next()
                assertTrue(xml.getAttributeValue(namespace,"minWidth").removeSuffix("dip").removeSuffix("dp").toFloat()>=100)
                assertTrue(xml.getAttributeValue(namespace,"minHeight").removeSuffix("dip").removeSuffix("dp").toFloat()>=64)
                assertTrue(xml.getAttributeIntValue(namespace,"targetCellWidth",0)>=2)
            }
        }
    }

}
