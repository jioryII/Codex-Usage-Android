package dev.codex.widget

import dev.codex.widget.domain.*
import org.junit.Assert.*
import org.junit.Test

class PresentationTest {
    @Test fun `four remaining states share inclusive thresholds and unknown is neutral`() {
        assertEquals(QuotaTone.FULL, WidgetPolicy.tone(100)); assertEquals(QuotaTone.FULL, WidgetPolicy.tone(75))
        assertEquals(QuotaTone.BLUE, WidgetPolicy.tone(74)); assertEquals(QuotaTone.BLUE, WidgetPolicy.tone(50))
        assertEquals(QuotaTone.ORANGE, WidgetPolicy.tone(49)); assertEquals(QuotaTone.ORANGE, WidgetPolicy.tone(25))
        assertEquals(QuotaTone.RED, WidgetPolicy.tone(24)); assertEquals(QuotaTone.RED, WidgetPolicy.tone(0))
        assertEquals(QuotaTone.UNKNOWN, WidgetPolicy.tone(null))
        assertNull(WidgetPolicy.remaining(UsageWindow(100, 50), 50))
    }
    @Test fun `formats do not collapse into icon-size variants when resized`() {
        assertEquals(WidgetMode.SQUARE, WidgetPolicy.mode(WidgetMode.SQUARE, 96f, 96f))
        assertEquals(WidgetMode.SLIM, WidgetPolicy.mode(WidgetMode.SLIM, 96f, 72f))
        assertEquals(WidgetMode.MINI, WidgetPolicy.mode(WidgetMode.MINI, 110f, 62f))
        assertEquals(WidgetMode.SQUARE, WidgetPolicy.mode(WidgetMode.SQUARE, 110f, 50f))
        assertEquals(WidgetMode.EXTENDED, WidgetPolicy.mode(WidgetMode.SQUARE, 240f, 240f))
    }
    @Test fun `both languages preserve remaining semantics and plural countdowns`() {
        assertEquals("72 % restante", UsageFormat.percent(UsageWindow(72, 8000), 0, true))
        assertEquals("Reinicia en 2 h", UsageFormat.countdown(7200, 0, true))
        assertEquals("Reinicia en 2 días", UsageFormat.countdown(172800, 0, true))
        assertEquals("Reinicia en 1 min", UsageFormat.countdown(1, 0, true))
        assertEquals("— % restante", UsageFormat.percent(UsageWindow(72, 8000), 8000, true))
        assertEquals("Resets in 1 day", UsageFormat.countdown(86400, 0))
    }
    @Test fun `extended credits discard consumed and expired units without guessing dates`() {
        val sample = UsageSnapshot(null, null, 3, listOf(ResetCredit("a","available",100), ResetCredit("b","available",300),
            ResetCredit("c","consumed",400), ResetCredit("d","available",null)), 0,"test")
        assertEquals(listOf("b","d"), WidgetPolicy.credits(sample, 200)!!.map { it.id })
        assertEquals(emptyList<ResetCredit>(), WidgetPolicy.credits(sample.copy(availableCount=0), 200))
        assertNull(WidgetPolicy.credits(sample.copy(credits=null), 200))
    }
    @Test fun `interactive policy sleeps without requests except opted-in credit revalidation or explicit action`() {
        assertFalse(SyncPolicy.shouldFetch(false,false,false,false,true,true,false))
        assertFalse(SyncPolicy.shouldFetch(false,false,true,false,false,true,false))
        assertFalse(SyncPolicy.shouldFetch(false,false,true,true,false,false,false))
        assertTrue(SyncPolicy.shouldFetch(false,false,true,true,false,true,false))
        assertTrue(SyncPolicy.shouldFetch(false,true,false,false,true,false,false))
        assertTrue(SyncPolicy.shouldFetch(true,false,false,false,true,false,true))
    }
    @Test fun `coalescing honors intervals tolerates a backward clock and errors back off`() {
        assertTrue(SyncPolicy.recentlyAttempted(1000,1059,60)); assertFalse(SyncPolicy.recentlyAttempted(1000,1060,60))
        assertFalse(SyncPolicy.recentlyAttempted(1000,999,60))
        assertEquals(60,SyncPolicy.retryDelay(1)); assertEquals(120,SyncPolicy.retryDelay(2)); assertEquals(3600,SyncPolicy.retryDelay(9))
    }
}
