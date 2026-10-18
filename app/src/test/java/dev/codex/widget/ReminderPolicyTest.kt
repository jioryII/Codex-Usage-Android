package dev.codex.widget

import dev.codex.widget.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class ReminderPolicyTest {
    private val expiry = 2_000_000L
    private val credit = ResetCredit("a", "available", expiry)
    private val start = expiry - ReminderPolicy.WINDOW
    @Test fun `first eligibility is exactly seven days before expiry`() {
        assertTrue(ReminderPolicy.due(listOf(credit), start - 1, emptyMap()).isEmpty())
        assertEquals(0L, ReminderPolicy.due(listOf(credit), start, emptyMap()).single().slot)
        assertEquals(start, ReminderPolicy.next(listOf(credit), start - 1))
    }
    @Test fun `daily reminder uses persistent high water and never catches up with a burst`() {
        val first = ReminderPolicy.due(listOf(credit), start, emptyMap()).single()
        val ledger = mapOf(first.key to 0L)
        assertTrue(ReminderPolicy.due(listOf(credit), start + 86399, ledger).isEmpty())
        assertEquals(1L, ReminderPolicy.due(listOf(credit), start + 86400, ledger).single().slot)
        assertEquals(5L, ReminderPolicy.due(listOf(credit), start + 5 * 86400, ledger).single().slot)
        assertTrue(ReminderPolicy.due(listOf(credit), start + 86400, mapOf(first.key to 5L)).isEmpty())
        assertEquals(start + 86400, ReminderPolicy.next(listOf(credit), start))
    }
    @Test fun `consumed expired unknown expiry and future credits never notify`() {
        assertTrue(ReminderPolicy.due(listOf(credit), expiry, emptyMap()).isEmpty())
        assertTrue(ReminderPolicy.due(listOf(credit.copy(status="redeemed")), start, emptyMap()).isEmpty())
        assertTrue(ReminderPolicy.due(listOf(credit.copy(expiresAt=null)), start, emptyMap()).isEmpty())
        assertNull(ReminderPolicy.next(listOf(credit), expiry))
    }
    @Test fun `multiple credits and expiries have independent deduplication`() {
        val other = credit.copy(id="b", expiresAt=expiry + 30)
        val due = ReminderPolicy.due(listOf(credit, other, credit), start + 60, emptyMap())
        assertEquals(2, due.size)
        val remaining = ReminderPolicy.due(listOf(credit, other), start + 60, mapOf(due[0].key to 0L))
        assertEquals("b", remaining.single().credit.id)
        assertEquals(1L, ReminderPolicy.due(listOf(other), start + 86430, emptyMap()).single().slot)
    }
    @Test fun `timezone and daylight saving do not change expiry slots`() {
        val old = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Lima"))
            val a = ReminderPolicy.due(listOf(credit), start + 86400, emptyMap())
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
            assertEquals(a, ReminderPolicy.due(listOf(credit), start + 86400, emptyMap()))
        } finally { TimeZone.setDefault(old) }
    }
}
