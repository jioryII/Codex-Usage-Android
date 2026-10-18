package dev.codex.widget.domain

import java.security.MessageDigest

data class Reminder(val credit: ResetCredit, val key: String, val slot: Long)

object ReminderPolicy {
    const val DAY = 86400L
    const val WINDOW = 7 * DAY

    fun key(credit: ResetCredit): String = hash("${credit.id}:${credit.expiresAt}")
    fun hash(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    fun due(credits: List<ResetCredit>, now: Long, delivered: Map<String, Long>): List<Reminder> =
        credits.distinctBy { it.id }.mapNotNull { credit ->
            val expiry = credit.expiresAt ?: return@mapNotNull null
            val start = expiry - WINDOW
            if (credit.status != "available" || now < start || now >= expiry) return@mapNotNull null
            val slot = (now - start) / DAY
            val key = key(credit)
            if (slot <= (delivered[key] ?: -1)) null else Reminder(credit, key, slot)
        }

    // Anchored to the server's expiry instant, so timezone/DST changes cannot duplicate a day.
    fun next(credits: List<ResetCredit>, now: Long): Long? = credits.mapNotNull { credit ->
        val expiry = credit.expiresAt ?: return@mapNotNull null
        if (credit.status != "available" || now >= expiry) return@mapNotNull null
        val start = expiry - WINDOW
        if (now < start) start else (start + ((now - start) / DAY + 1) * DAY).takeIf { it < expiry }
    }.minOrNull()
}
