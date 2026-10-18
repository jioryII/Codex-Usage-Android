package dev.codex.widget.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import dev.codex.widget.Language
import dev.codex.widget.R
import dev.codex.widget.SetupActivity
import dev.codex.widget.domain.*
import java.time.Instant
import java.time.format.DateTimeFormatter

/** Canvas-backed RemoteViews: no WebView or process/service is needed after rendering.
 * Drawing in dp keeps small Samsung cells usable and gives both tracks identical geometry. */
object WidgetRenderer {
    private const val WHITE = 0xFFF1F1F3.toInt()
    private const val MUTED = 0xFFA2A2AA.toInt()
    private const val TRACK = 0xFF45454C.toInt()
    fun views(context: Context, snapshot: UsageSnapshot?, now: Long, compact: Boolean = false): RemoteViews =
        views(context, snapshot, now, if (compact) 110f else 145f, if (compact) 110f else 145f, WidgetMode.SQUARE)

    fun views(context: Context, snapshot: UsageSnapshot?, now: Long, width: Float, height: Float, preferred: WidgetMode): RemoteViews {
        val mode = WidgetPolicy.mode(preferred, width, height)
        return RemoteViews(context.packageName, R.layout.widget_bitmap).apply {
            setImageViewBitmap(R.id.widget_image, bitmap(context, snapshot, now, width, height, mode))
            setContentDescription(R.id.widget_image, description(context, snapshot, now, mode))
            setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, 0,
                Intent(context, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
    }

    fun description(context: Context, snapshot: UsageSnapshot?, now: Long, mode: WidgetMode): String {
        val c = Language.context(context)
        val es = Language.code(context) == "es"
        val sections = listOf(c.getString(R.string.five_hours) to snapshot?.fiveHours, c.getString(R.string.weekly) to snapshot?.weekly)
        return buildList {
            if (mode != WidgetMode.MINI) add("Codex")
            sections.forEach { (label, window) ->
                add("$label: ${UsageFormat.percent(window, now, es)}")
                if (mode == WidgetMode.SQUARE || mode == WidgetMode.EXTENDED) add(UsageFormat.countdown(window?.resetsAt, now, es))
            }
            if (mode == WidgetMode.EXTENDED) addAll(creditLines(context, snapshot, now, 100))
        }.joinToString(". ")
    }

    fun creditLines(context: Context, snapshot: UsageSnapshot?, now: Long, maxRows: Int): List<String> {
        val c = Language.context(context)
        val credits = WidgetPolicy.credits(snapshot, now)
        // A server summary is shown as read. Never infer dates or manufacture units.
        val staleCount = snapshot?.availableCount?.let { it > 0 && snapshot.credits.orEmpty().any { row -> row.status == "available" && row.expiresAt?.let { expiry -> expiry <= now } == true } } == true
        val count = snapshot?.availableCount
        val summary = when {
            staleCount -> c.getString(R.string.unknown_credits)
            count == 0 -> c.getString(R.string.no_resets)
            count != null -> c.resources.getQuantityString(R.plurals.available_resets, count, count)
            credits != null -> c.resources.getQuantityString(R.plurals.available_resets, credits.size, credits.size)
            else -> c.getString(R.string.unknown_credits)
        }
        if (count == 0) return listOf(c.getString(R.string.saved_resets), summary)
        val groups = credits.orEmpty().groupBy { it.expiresAt }.entries.sortedBy { it.key ?: Long.MAX_VALUE }
        val shown = if (groups.size > maxRows) (maxRows - 1).coerceAtLeast(0) else maxRows
        val dates = groups.take(shown).map { (date, units) ->
                val label = date?.let {
                    val formatted = Instant.ofEpochSecond(it).atZone(java.time.ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("d MMM · HH:mm", Language.locale(context)))
                    c.getString(R.string.expires_on, formatted)
                } ?: c.getString(R.string.expiry_unknown)
                "${units.size} · $label"
            }
        val detail = if (groups.size > maxRows) dates + c.resources.getQuantityString(R.plurals.more_expiries, groups.size - shown, groups.size - shown)
            else if (dates.isEmpty()) listOf(c.getString(R.string.expiry_unknown)) else dates
        return listOf(c.getString(R.string.saved_resets), summary) + detail
    }

    fun bitmap(context: Context, snapshot: UsageSnapshot?, now: Long, width: Float, height: Float, mode: WidgetMode, exportScale: Float? = null): Bitmap {
        val w = width.coerceIn(48f, 560f)
        val h = height.coerceIn(40f, 560f)
        // Bound bitmap memory even for several Android 12 responsive sizes and high-density screens.
        val density = exportScale?.coerceIn(1f, 4f)
            ?: minOf(context.resources.displayMetrics.density.coerceIn(1f, 3f), 512f / maxOf(w, h))
        val bitmap = Bitmap.createBitmap((w * density).toInt(), (h * density).toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(density, density)
        val (baseW, baseH) = when (mode) {
            WidgetMode.MINI -> 120f to 64f
            WidgetMode.SLIM -> 120f to 78f
            WidgetMode.SQUARE -> 120f to 114f
            WidgetMode.EXTENDED -> 180f to 180f
        }
        val breathingRoom = when(mode) { WidgetMode.MINI -> 0.94f; WidgetMode.EXTENDED -> 0.96f; else -> 1f }
        val scale = minOf(w / baseW, h / baseH) * breathingRoom
        canvas.translate((w - baseW * scale) / 2f, (h - baseH * scale) / 2f)
        canvas.scale(scale, scale)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 0f, baseH, 0xFF323235.toInt(), 0xFF202023.toInt(), Shader.TileMode.CLAMP)
        val cornerRadius = if (mode == WidgetMode.MINI) baseH / 2f else 17f
        canvas.drawRoundRect(0.5f, 0.5f, baseW - 0.5f, baseH - 0.5f, cornerRadius, cornerRadius, paint)
        paint.shader = null; paint.style = Paint.Style.STROKE; paint.strokeWidth = 0.6f; paint.color = 0xFF4B4B52.toInt()
        canvas.drawRoundRect(0.5f, 0.5f, baseW - 0.5f, baseH - 0.5f, cornerRadius, cornerRadius, paint)
        paint.style = Paint.Style.FILL
        val c = Language.context(context)
        val es = Language.code(context) == "es"
        fun text(value: String, x: Float, y: Float, size: Float, color: Int = WHITE, bold: Boolean = false, maxWidth: Float = baseW - x - 9, align: Paint.Align = Paint.Align.LEFT) {
            paint.shader = null; paint.color = color; paint.typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
            paint.textAlign = align; paint.textSize = size
            if (paint.measureText(value) > maxWidth) paint.textSize *= maxWidth / paint.measureText(value)
            canvas.drawText(value, x, y, paint)
            paint.textAlign = Paint.Align.LEFT
        }
        fun quotaPaint(remaining: Int?, left: Float, right: Float) {
            paint.shader = null
            paint.color = when (WidgetPolicy.tone(remaining)) {
                QuotaTone.FULL -> 0xFF956AF1.toInt()
                QuotaTone.BLUE -> 0xFF65B4E5.toInt()
                QuotaTone.ORANGE -> 0xFFFFA164.toInt()
                QuotaTone.RED -> 0xFFF16C77.toInt()
                QuotaTone.UNKNOWN -> MUTED
            }
            if (WidgetPolicy.tone(remaining) == QuotaTone.FULL)
                paint.shader = LinearGradient(left, 0f, right, 0f, 0xFF65B4E5.toInt(), 0xFFA276F4.toInt(), Shader.TileMode.CLAMP)
        }
        fun bar(window: UsageWindow?, y: Float) {
            val left = if (mode == WidgetMode.EXTENDED) 12f else 9f
            val right = baseW - left
            paint.shader = null; paint.color = TRACK
            canvas.drawRoundRect(left, y, right, y + 2.7f, 1.35f, 1.35f, paint)
            val remaining = WidgetPolicy.remaining(window, now)
            if (remaining != null && remaining > 0) {
                val end = left + (right - left) * remaining / 100f
                quotaPaint(remaining, left, end)
                canvas.drawRoundRect(left, y, end, y + 2.7f, 1.35f, 1.35f, paint)
                paint.shader = null
            }
        }
        fun gauge(window: UsageWindow?, center: Float, weekly: Boolean) {
            val rect = RectF(center - 21, 7f, center + 21, 49f)
            paint.shader = null; paint.color = TRACK; paint.style = Paint.Style.STROKE; paint.strokeWidth = 5.5f; paint.strokeCap = Paint.Cap.ROUND
            canvas.drawArc(rect, 150f, 240f, false, paint)
            val remaining = WidgetPolicy.remaining(window, now)
            if (remaining != null && remaining > 0) {
                quotaPaint(remaining, rect.left, rect.right)
                canvas.drawArc(rect, 150f, 240f * remaining / 100f, false, paint)
            }
            paint.shader = null; paint.style = Paint.Style.FILL; paint.strokeCap = Paint.Cap.BUTT
            // A clock marks the 5-hour window; a calendar marks the weekly window.
            paint.shader = null; paint.color = WHITE; paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.25f; paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
            if (weekly) {
                canvas.drawRoundRect(center - 6, 23f, center + 6, 34f, 1.5f, 1.5f, paint)
                canvas.drawLine(center - 6, 26.5f, center + 6, 26.5f, paint)
                canvas.drawLine(center - 3, 21f, center - 3, 24.5f, paint)
                canvas.drawLine(center + 3, 21f, center + 3, 24.5f, paint)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(center, 30f, 1f, paint)
            } else {
                canvas.drawCircle(center, 28f, 6f, paint)
                canvas.drawLine(center, 28f, center, 24.5f, paint)
                canvas.drawLine(center, 28f, center + 2.8f, 29.5f, paint)
            }
            paint.style = Paint.Style.FILL; paint.strokeCap = Paint.Cap.BUTT
            text(remaining?.toString() ?: "—", center, 57f, 12f, bold = true, maxWidth = 48f, align = Paint.Align.CENTER)
        }
        if (mode == WidgetMode.MINI) {
            gauge(snapshot?.fiveHours, 31f, false); gauge(snapshot?.weekly, 89f, true)
        } else {
            val extended = mode == WidgetMode.EXTENDED
            val pad = if (extended) 12f else 9f
            val titleY = if (extended) 19f else 20f
            ContextCompat.getDrawable(c, R.drawable.ic_codex)?.apply {
                setBounds(pad.toInt(), (titleY - 10).toInt(), (pad + 11).toInt(), (titleY + 1).toInt()); draw(canvas)
            }
            text("Codex", pad + 15, titleY, 11f)
            val square = mode != WidgetMode.SLIM
            fun section(label: String, window: UsageWindow?, y: Float) {
                text(label, pad, y, if (extended) 11f else 9f, MUTED, maxWidth = if (extended) 60f else 34f)
                text(UsageFormat.percent(window, now, es), baseW - pad, y, if (extended) 16f else 13f, bold = true,
                    maxWidth = baseW - pad * 2 - if (extended) 65f else 37f, align = Paint.Align.RIGHT)
                bar(window, y + 5)
                if (square) text(UsageFormat.countdown(window?.resetsAt, now, es), pad, y + if (extended) 16f else 18f, if (extended) 8.5f else 7.7f, MUTED)
            }
            section(c.getString(R.string.five_hours), snapshot?.fiveHours, if (extended) 40f else 39f)
            section(c.getString(R.string.weekly), snapshot?.weekly, if (extended) 75f else if (square) 86f else 64f)
            if (extended) {
                paint.color = TRACK; canvas.drawRect(12f, 101f, baseW - 12f, 101.6f, paint)
                val lines = creditLines(context, snapshot, now, 3)
                lines.forEachIndexed { index, value -> text(value, 12f, 116f + index * 13f, if (index == 0) 10f else 9f, if (index <= 1) WHITE else MUTED, bold = index == 0) }
            }
        }
        return bitmap
    }
}
