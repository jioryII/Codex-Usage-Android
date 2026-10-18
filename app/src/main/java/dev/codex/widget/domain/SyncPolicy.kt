package dev.codex.widget.domain

object SyncPolicy {
    const val AUTOMATIC_INTERVAL = 300L
    const val MANUAL_INTERVAL = 60L
    fun shouldFetch(manual: Boolean, reminder: Boolean, interactive: Boolean, unlocked: Boolean,
                    idle: Boolean, hasWidgets: Boolean, appVisible: Boolean): Boolean =
        reminder || manual || (interactive && unlocked && !idle && (hasWidgets || appVisible))
    fun recentlyAttempted(last: Long, now: Long, minSeconds: Long) =
        minSeconds > 0 && last > 0 && now >= last && now - last < minSeconds
    fun retryDelay(failures: Int): Long = (60L * (1L shl (failures - 1).coerceIn(0, 6))).coerceAtMost(3600)
}
