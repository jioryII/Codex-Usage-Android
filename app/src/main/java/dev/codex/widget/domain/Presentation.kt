package dev.codex.widget.domain

/** Colors describe remaining quota, never consumed quota. Unknown data is neutral. */
enum class QuotaTone { FULL, BLUE, ORANGE, RED, UNKNOWN }
enum class WidgetMode { SQUARE, SLIM, MINI, EXTENDED }
object WidgetPolicy {
    fun tone(remaining: Int?) = when {
        remaining == null -> QuotaTone.UNKNOWN
        remaining >= 75 -> QuotaTone.FULL
        remaining >= 50 -> QuotaTone.BLUE
        remaining >= 25 -> QuotaTone.ORANGE
        else -> QuotaTone.RED
    }
    fun remaining(window: UsageWindow?, now: Long): Int? = window?.takeIf { it.resetsAt == null || it.resetsAt > now }?.remaining
    // Preserve each format when resized; there is no icon-size fallback variant.
    fun mode(preferred: WidgetMode, width: Float, height: Float): WidgetMode =
        if (preferred != WidgetMode.MINI && width >= 224 && height >= 224) WidgetMode.EXTENDED else preferred
    /** Only authoritative zero or individually available, nonexpired credits are displayed. */
    fun credits(snapshot: UsageSnapshot?, now: Long): List<ResetCredit>? = when {
        snapshot?.availableCount == 0 -> emptyList()
        else -> snapshot?.credits?.filter { it.status == "available" && (it.expiresAt == null || it.expiresAt > now) }
            ?.sortedWith(compareBy<ResetCredit> { it.expiresAt ?: Long.MAX_VALUE }.thenBy { it.id })
    }
}
