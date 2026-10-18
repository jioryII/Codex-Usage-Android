package dev.codex.widget.notify

import android.Manifest
import android.app.Notification
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import androidx.core.content.ContextCompat
import dev.codex.widget.Language
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.codex.widget.R
import dev.codex.widget.SetupActivity
import dev.codex.widget.domain.ReminderPolicy
import dev.codex.widget.domain.UsageSnapshot
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class CreditNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    fun createChannel() {
        manager.createNotificationChannel(NotificationChannel(CHANNEL, Language.context(context).getString(R.string.channel_name), NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = Language.context(context).getString(R.string.channel_description) })
    }
    fun allowed(): Boolean = (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context,
        Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE

    fun clear() { manager.activeNotifications.filter { it.tag?.startsWith(PREFIX) == true }.forEach { manager.cancel(it.tag, it.id) } }

    fun reconcile(snapshot: UsageSnapshot, state: JSONObject, now: Long, persist: () -> Unit) {
        val credits = snapshot.credits
        if (!state.optBoolean("reminders") || !allowed() || credits == null) { clear(); return }
        val ledger = state.optJSONObject("ledger") ?: JSONObject().also { state.put("ledger", it) }
        val delivered = ledger.keys().asSequence().associateWith { ledger.getLong(it) }
        val due = ReminderPolicy.due(credits, now, delivered)
        val current = credits.filter { it.status == "available" && (it.expiresAt ?: 0) > now }
            .map { ReminderPolicy.key(it) }.toSet()
        manager.activeNotifications.filter { it.tag?.startsWith(PREFIX) == true }.forEach { notification ->
            val key = notification.tag.removePrefix(PREFIX).substringBefore(':')
            val sameCreditDue = due.any { it.key == key && notification.tag != tag(it.key, it.slot) }
            if (key !in current || sameCreditDue) manager.cancel(notification.tag, notification.id)
        }
        for (reminder in due) {
            val c = Language.context(context)
            val expiry = expiry(reminder.credit.expiresAt!!)
            val pending = PendingIntent.getActivity(context, 0, Intent(context, SetupActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification)
                .setLargeIcon(largeIcon())
                .addExtras(Bundle().apply { putLong("creditExpiresAt", reminder.credit.expiresAt) })
                .setContentTitle(c.getString(R.string.notification_title))
                .setContentText(c.getString(R.string.notification_text, expiry))
                .setStyle(NotificationCompat.BigTextStyle().bigText(c.getString(R.string.notification_text, expiry)))
                .setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true)
                .setTimeoutAfter(((reminder.credit.expiresAt - now) * 1000).coerceAtLeast(1))
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
            try { manager.notify(tag(reminder.key, reminder.slot), 1, notification) }
            catch (_: SecurityException) { return }
            // Stable notification tag prevents a second entry/alert if killed after notify, before commit.
            ledger.put(reminder.key, reminder.slot)
            persist()
        }
        // Keep high-water entries until logout: a backwards clock must not replay older slots.
    }
    private fun expiry(time: Long): String = Instant.ofEpochSecond(time).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("d MMM · HH:mm z", Language.locale(context)))
    private fun largeIcon(): Bitmap {
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.BLACK)
        ContextCompat.getDrawable(context, R.drawable.ic_codex)?.apply { setBounds(30, 30, 98, 98); draw(canvas) }
        return bitmap
    }
    /** Update existing notifications without replaying their reminder slots or alerts. */
    fun updateLanguage() {
        createChannel()
        val c = Language.context(context)
        if (!allowed()) return
        manager.activeNotifications.filter { it.tag?.startsWith(PREFIX) == true }.forEach {
            val time = it.notification.extras.getLong("creditExpiresAt")
            if (time > 0) {
                val updated = Notification.Builder.recoverBuilder(c, it.notification)
                    .setContentTitle(c.getString(R.string.notification_title))
                    .setContentText(c.getString(R.string.notification_text, expiry(time)))
                    .setStyle(Notification.BigTextStyle().bigText(c.getString(R.string.notification_text, expiry(time))))
                    .setOnlyAlertOnce(true).build()
                try { manager.notify(it.tag, it.id, updated) } catch (_: SecurityException) { }
            }
        }
    }
    private fun tag(key: String, slot: Long) = "$PREFIX$key:$slot"
    companion object {
        const val CHANNEL = "saved_reset_expiry"
        private const val PREFIX = "codex-expiry:"
    }
}
