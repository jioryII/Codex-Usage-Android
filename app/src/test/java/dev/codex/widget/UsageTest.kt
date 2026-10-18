package dev.codex.widget

import dev.codex.widget.domain.*
import dev.codex.widget.data.SnapshotCodec
import org.junit.Assert.*
import org.junit.Test

class UsageTest {
    @Test fun `remaining reflects used percentage with app-server rounding`() {
        assertEquals(72, UsageFormat.remaining(28.0))
        assertEquals(22, UsageFormat.remaining(78.0))
        assertEquals(71, UsageFormat.remaining(28.5))
        assertEquals(0, UsageFormat.remaining(140.0))
        assertEquals(100, UsageFormat.remaining(-1.0))
        assertThrows(IllegalArgumentException::class.java) { UsageFormat.remaining(Double.NaN) }
    }
    @Test fun `countdown never fabricates a reset`() {
        assertEquals("Resets in 2 hrs", UsageFormat.countdown(7200, 0))
        assertEquals("Resets in 1 day", UsageFormat.countdown(86400, 0))
        assertEquals("Resets in 2 days", UsageFormat.countdown(172800, 0))
        assertEquals("Resets in 1 hr", UsageFormat.countdown(3600, 0))
        assertEquals("Resets in 1 min", UsageFormat.countdown(1, 0))
        assertEquals("Resets in —", UsageFormat.countdown(null, 0))
        assertEquals("Resets in —", UsageFormat.countdown(10, 10))
        assertEquals("— % left", UsageFormat.percent(UsageWindow(72, 10), 10))
        assertEquals("72 % left", UsageFormat.percent(UsageWindow(72, null), 10))
    }
    @Test fun `internal parser selects durations not primary position or additional quotas`() {
        val result = UsageParser.internalUsage(INTERNAL, 100)
        assertEquals(72, result.fiveHours!!.remaining)
        assertEquals(22, result.weekly!!.remaining)
        assertEquals(200000L, result.weekly!!.resetsAt)
        assertNull(result.credits)
        assertEquals(2, result.availableCount)
        val noWeekly = UsageParser.internalUsage(INTERNAL.replace("604800", "86400"), 100)
        assertNull(noWeekly.weekly)
    }
    @Test fun `missing windows and missing fields are never made up`() {
        val snapshot = UsageParser.internalUsage("""{"rate_limit":null}""", 100)
        assertNull(snapshot.weekly); assertNull(snapshot.fiveHours)
        assertThrows(Exception::class.java) { UsageParser.internalUsage("{}", 100) }
        assertThrows(Exception::class.java) { UsageParser.internalUsage(
            """{"rate_limit":{"primary_window":{"limit_window_seconds":18000}}}""", 100) }
    }
    @Test fun `app-server account bucket and reset credits preserve official schema`() {
        val snapshot = UsageParser.appServer(RPC, 100)
        assertEquals(72, snapshot.fiveHours!!.remaining)
        assertEquals(22, snapshot.weekly!!.remaining)
        assertEquals(2, snapshot.availableCount)
        assertEquals(999999L, snapshot.credits!!.first().expiresAt)
        assertNull(snapshot.credits!![1].expiresAt)
        assertEquals(snapshot, SnapshotCodec.decode(SnapshotCodec.encode(snapshot)))
        assertThrows(IllegalArgumentException::class.java) { UsageParser.appServer("""{"error":{"code":-32603}}""", 0) }
    }
    @Test fun `count-only credits never gain guessed expiry`() {
        val snapshot = UsageParser.appServer("""{"rateLimits":{},"rateLimitResetCredits":{"availableCount":3}}""", 100)
        assertEquals(3, snapshot.availableCount); assertNull(snapshot.credits)
    }
    @Test fun `backend credit expiry must be valid and associated with actual row`() {
        val (_, rows) = UsageParser.internalCredits("""{"available_count":3,"credits":[
          {"id":"a","reset_type":"codex_rate_limits","status":"available","expires_at":"2026-07-17T00:00:00Z"},
          {"id":"b","reset_type":"codex_rate_limits","status":"available","expires_at":"invalid"},
          {"id":"c","reset_type":"unknown","status":"available","expires_at":"2026-07-17T00:00:00Z"}] }""")
        assertEquals(2, rows.size); assertEquals(1784246400L, rows[0].expiresAt); assertNull(rows[1].expiresAt)
    }
    companion object {
        const val INTERNAL = """{"account_id":"acct","rate_limit":{"primary_window":{"used_percent":78,"limit_window_seconds":604800,"reset_at":200000},"secondary_window":{"used_percent":28,"limit_window_seconds":18000,"reset_at":10000}},"rate_limit_reset_credits":{"available_count":2},"additional_rate_limits":[{"metered_feature":"spark","rate_limit":{"primary_window":{"used_percent":99,"limit_window_seconds":18000}}}]}"""
        const val RPC = """{"id":1,"result":{"rateLimits":{},"rateLimitsByLimitId":{"codex":{"primary":{"usedPercent":28,"windowDurationMins":300,"resetsAt":10000},"secondary":{"usedPercent":78,"windowDurationMins":10080,"resetsAt":200000}},"spark":{"primary":{"usedPercent":99,"windowDurationMins":300}}},"rateLimitResetCredits":{"availableCount":2,"credits":[{"id":"a","resetType":"codexRateLimits","status":"available","expiresAt":999999},{"id":"b","resetType":"codexRateLimits","status":"available","expiresAt":null}]}}}"""
    }
}
