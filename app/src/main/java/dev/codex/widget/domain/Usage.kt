package dev.codex.widget.domain

import org.json.JSONObject
import java.time.Instant
import kotlin.math.roundToInt

data class UsageWindow(val remaining: Int, val resetsAt: Long?)
data class ResetCredit(val id: String, val status: String, val expiresAt: Long?)
data class UsageSnapshot(
    val fiveHours: UsageWindow?,
    val weekly: UsageWindow?,
    val availableCount: Int?,
    // null means not fetched/unavailable, empty means a successful authoritative empty read.
    val credits: List<ResetCredit>?,
    val fetchedAt: Long,
    val source: String,
)

object UsageFormat {
    fun remaining(used: Double): Int {
        require(used.isFinite()) { "Invalid usage percentage" }
        // Same order as app-server: round used, then subtract from 100.
        return 100 - used.coerceIn(0.0, 100.0).roundToInt()
    }
    fun percent(window: UsageWindow?, now: Long, spanish: Boolean = false): String {
        val value = if (window == null || window.resetsAt?.let { it <= now } == true) "—" else window.remaining.toString()
        return if (spanish) "$value % restante" else "$value % left"
    }

    fun countdown(resetAt: Long?, now: Long, spanish: Boolean = false): String {
        val prefix = if (spanish) "Reinicia en" else "Resets in"
        if (resetAt == null || resetAt <= now) return "$prefix —"
        val seconds = resetAt - now
        val (value, unit) = when {
            seconds >= 86400 -> (seconds / 86400) to if (spanish) "día" else "day"
            seconds >= 3600 -> (seconds / 3600) to if (spanish) "h" else "hr"
            else -> ((seconds + 59) / 60).coerceAtLeast(1) to "min"
        }
        val plural = value != 1L && unit !in setOf("min", "h")
        return "$prefix $value $unit${if (plural) "s" else ""}"
    }

}

object UsageParser {
    fun internalUsage(body: String, now: Long): UsageSnapshot {
        val root = JSONObject(body)
        require(root.has("rate_limit")) { "Usage contract unavailable" }
        val rate = root.optJSONObject("rate_limit")
        val windows = listOfNotNull(rate?.optJSONObject("primary_window"), rate?.optJSONObject("secondary_window"))
        fun window(seconds: Long) = windows.firstOrNull {
            it.optLong("limit_window_seconds", -1) == seconds
        }?.let { UsageWindow(UsageFormat.remaining(it.getDouble("used_percent")), it.longOrNull("reset_at")) }
        return UsageSnapshot(window(18000), window(604800),
            root.optJSONObject("rate_limit_reset_credits")?.intOrNull("available_count"),
            null, now, "ChatGPT internal (unofficial)")
    }

    fun internalCredits(body: String): Pair<Int, List<ResetCredit>> {
        val root = JSONObject(body)
        val count = root.getInt("available_count").also { require(it >= 0) }
        val rows = root.getJSONArray("credits")
        val credits = (0 until rows.length()).mapNotNull { index ->
            val row = rows.getJSONObject(index)
            if (row.optString("reset_type") != "codex_rate_limits") null
            else ResetCredit(row.getString("id").also { require(it.isNotBlank()) }, row.getString("status"),
                row.optString("expires_at").takeIf { it.isNotBlank() && it != "null" }
                    ?.let { runCatching { Instant.parse(it).epochSecond }.getOrNull() })
        }.distinctBy { it.id }
        require(credits.count { it.status == "available" } <= count) { "Inconsistent reset credit summary" }
        return count to credits
    }

    fun appServer(body: String, now: Long): UsageSnapshot {
        val envelope = JSONObject(body)
        require(!envelope.has("error")) { "App-server rejected read" }
        val root = envelope.optJSONObject("result") ?: envelope
        val byId = root.optJSONObject("rateLimitsByLimitId")
        val rate = if (byId != null) byId.optJSONObject("codex")
        else root.optJSONObject("rateLimits")?.takeIf { it.optString("limitId", "codex") == "codex" }
        val windows = listOfNotNull(rate?.optJSONObject("primary"), rate?.optJSONObject("secondary"))
        fun window(minutes: Long) = windows.firstOrNull {
            it.optLong("windowDurationMins", -1) == minutes
        }?.let { UsageWindow(UsageFormat.remaining(it.getDouble("usedPercent")), it.longOrNull("resetsAt")) }
        val summary = root.optJSONObject("rateLimitResetCredits")
        val rows = summary?.optJSONArray("credits")
        val credits = rows?.let { (0 until it.length()).mapNotNull { index ->
            val row = it.getJSONObject(index)
            if (row.optString("resetType") != "codexRateLimits") null
            else ResetCredit(row.getString("id"), row.getString("status"), row.longOrNull("expiresAt"))
        }.distinctBy { it.id } }
        return UsageSnapshot(window(300), window(10080), summary?.intOrNull("availableCount"),
            credits, now, "Codex app-server")
    }
}

internal fun JSONObject.longOrNull(key: String): Long? =
    if (!has(key) || isNull(key)) null else getLong(key).takeIf { it > 0 }
internal fun JSONObject.intOrNull(key: String): Int? =
    if (!has(key) || isNull(key)) null else getInt(key).takeIf { it >= 0 }
