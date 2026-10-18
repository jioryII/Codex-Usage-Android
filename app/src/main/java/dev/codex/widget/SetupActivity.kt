package dev.codex.widget

import android.Manifest
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.*
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.codex.widget.data.DeviceAuth
import dev.codex.widget.data.LoginPhase
import dev.codex.widget.data.LoginView
import dev.codex.widget.sync.Scheduler
import dev.codex.widget.widget.CodexWidgetProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Connection, presentation preferences and permission controls. No parallel usage dashboard. */
class SetupActivity : ComponentActivity() {
    private lateinit var badge: TextView
    private lateinit var heading: TextView
    private lateinit var detail: TextView
    private lateinit var disconnected: LinearLayout
    private lateinit var pending: LinearLayout
    private lateinit var connected: LinearLayout
    private lateinit var requesting: ProgressBar
    private lateinit var code: TextView
    private lateinit var expiry: TextView
    private lateinit var pendingMessage: TextView
    private lateinit var authorizationButton: Button
    private lateinit var usageStatus: TextView
    private lateinit var reminderCard: LinearLayout
    private lateinit var reminders: Switch
    private lateinit var signOut: Button
    private var current = LoginView(LoginPhase.REQUESTING)
    private var lastState = JSONObject()
    private var rendering = false
    private var consentShowing = false
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> setReminders(granted) }
    override fun attachBaseContext(base: Context) { super.attachBaseContext(Language.context(base)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    graph.repository.changes.collectLatest {
                        try {
                            val (view, state) = withContext(Dispatchers.IO) { graph.login.view() to graph.repository.readState() }
                            render(view, state)
                        } catch (_: Exception) {
                            badge.setText(R.string.connection_unavailable); heading.setText(R.string.storage_error)
                            detail.setText(R.string.storage_help); requesting.visibility = View.GONE
                            signOut.visibility = View.VISIBLE; signOut.setText(R.string.clear_connection)
                        }
                    }
                }
                launch { while (isActive) { updateCountdown(); delay(1000) } }
            }
        }
        Scheduler.install(this)
        Scheduler.refresh(this, manual = true)
    }
    override fun onResume() { super.onResume(); graph.appVisible = true; graph.resumeLogin(); Scheduler.refresh(this, manual = true) }
    override fun onPause() { graph.appVisible = false; super.onPause() }

    private fun buildScreen() {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(18), dp(20), dp(24)) }
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false; addView(column); setBackgroundColor(Color.rgb(16, 18, 21)) }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()); view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        setContentView(scroll)
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(ImageView(this).apply { setImageResource(R.drawable.ic_codex); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(27), dp(27)))
        header.addView(text("Codex", 25f, WHITE, true), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(10) })
        header.addView(Button(this).apply {
            text = if (Language.code(this@SetupActivity) == "es") "ES ▾" else "EN ▾"
            contentDescription = getString(R.string.language); isAllCaps = false; textSize = 13f; setTextColor(WHITE)
            background = shape("#23262C", 12f); minHeight = dp(48); minimumHeight = dp(48)
            setOnClickListener { chooseLanguage() }
        }, LinearLayout.LayoutParams(dp(76), dp(48)))
        column.addView(header, params(bottom = 20))
        val connection = card(column)
        badge = text(getString(R.string.preparing), 12f, BLUE, true).also(connection::addView)
        heading = text(getString(R.string.restoring), 22f, WHITE, true).also { connection.addView(it, params(top = 12)) }
        detail = text(getString(R.string.moment), 13f, MUTED).also { connection.addView(it, params(top = 8, bottom = 12)) }
        requesting = ProgressBar(this).also { connection.addView(it, LinearLayout.LayoutParams(dp(26), dp(26))) }
        disconnected = group(connection)
        button(disconnected, R.string.continue_chatgpt, primary = true) { beginLogin() }
        disconnected.addView(text(getString(R.string.providers), 12f, MUTED), params(top = 10))
        pending = group(connection)
        pending.addView(text(getString(R.string.your_code), 10f, MUTED, true), params(bottom = 6))
        code = text("", 27f, WHITE, true).apply {
            typeface = Typeface.MONOSPACE; letterSpacing = 0.1f; gravity = Gravity.CENTER
            setTextIsSelectable(true); setPadding(dp(8), dp(16), dp(8), dp(16)); background = shape("#121417", 14f)
        }.also { pending.addView(it, params(bottom = 8)) }
        expiry = text("", 12f, MUTED).also { pending.addView(it, params(bottom = 10)) }
        authorizationButton = button(pending, R.string.copy_open, primary = true) { openAuthorization() }
        pending.addView(text(getString(R.string.auth_steps), 13f, MUTED), params(top = 12))
        pendingMessage = text(getString(R.string.checking), 12f, BLUE).also { pending.addView(it, params(top = 12)) }
        button(pending, R.string.check_auth) { graph.resumeLogin(); pendingMessage.setText(R.string.same_auth) }
        button(pending, R.string.cancel_auth) { graph.cancelLogin() }
        connected = group(connection)
        usageStatus = text("", 13f, MUTED).also { connected.addView(it, params(top = 10, bottom = 8)) }
        button(connected, R.string.add_widget, primary = true) { chooseWidget() }
        button(connected, R.string.refresh) { Scheduler.refresh(this, manual = true); usageStatus.setText(R.string.refresh_requested) }
        reminderCard = card(column, top = 14)
        reminderCard.addView(text(getString(R.string.reminders), 16f, WHITE, true))
        reminders = Switch(this).apply {
            text = getString(R.string.expiry_reminders); textSize = 13f; setTextColor(WHITE)
            setOnCheckedChangeListener { _, checked -> if (!rendering) {
                if (checked && Build.VERSION.SDK_INT >= 33 && !graph.notifications.allowed()) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else setReminders(checked)
            } }
        }.also { reminderCard.addView(it, params(top = 8)) }
        reminderCard.addView(text(getString(R.string.reminder_help), 12f, MUTED), params(top = 6))
        signOut = button(column, R.string.sign_out) {
            AlertDialog.Builder(this).setTitle(R.string.sign_out_question).setMessage(R.string.sign_out_detail)
                .setNegativeButton(R.string.back, null).setPositiveButton(R.string.sign_out) { _, _ -> graph.signOut() }.show()
        }.apply { visibility = View.GONE; setTextColor(Color.rgb(255, 139, 147)); background = shape("#321D24", 14f) }
        button(column, R.string.help) { diagnostics() }
        column.addView(text(getString(R.string.footer), 11f, MUTED), params(top = 14))
        disconnected.visibility = View.GONE; pending.visibility = View.GONE; connected.visibility = View.GONE; reminderCard.visibility = View.GONE
        column.isFocusableInTouchMode = true; column.requestFocus()
    }
    private fun render(view: LoginView, state: JSONObject) {
        current = view; lastState = state; rendering = true
        val signedIn = view.phase == LoginPhase.SIGNED_IN
        val waiting = view.phase == LoginPhase.WAITING || view.phase == LoginPhase.EXCHANGING
        disconnected.visibility = if (view.phase == LoginPhase.SIGNED_OUT || view.phase == LoginPhase.ERROR) View.VISIBLE else View.GONE
        pending.visibility = if (waiting) View.VISIBLE else View.GONE
        connected.visibility = if (signedIn) View.VISIBLE else View.GONE
        reminderCard.visibility = if (signedIn) View.VISIBLE else View.GONE
        requesting.visibility = if (view.phase == LoginPhase.REQUESTING) View.VISIBLE else View.GONE
        signOut.visibility = if (signedIn) View.VISIBLE else View.GONE
        heading.visibility = if (signedIn) View.GONE else View.VISIBLE
        detail.visibility = if (signedIn) View.GONE else View.VISIBLE
        signOut.setText(R.string.sign_out)
        badge.setTextColor(if (signedIn) GREEN else BLUE)
        when (view.phase) {
            LoginPhase.SIGNED_IN -> {
                badge.setText(R.string.signed_in)
                val success = state.optLong("lastSuccess")
                usageStatus.text = if (success > 0) {
                    val time = Instant.ofEpochSecond(success).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM · HH:mm", Language.locale(this)))
                    getString(if (state.optBoolean("lastFailed")) R.string.update_failed else R.string.last_update, time)
                } else getString(R.string.waiting_usage)
                reminders.isChecked = state.optBoolean("reminders")
            }
            LoginPhase.WAITING, LoginPhase.EXCHANGING -> {
                badge.setText(R.string.auth_in_progress)
                heading.setText(if (view.phase == LoginPhase.EXCHANGING) R.string.saving_session else R.string.continue_openai)
                detail.setText(R.string.browser_return)
                if (code.text.toString() != view.code) code.text = view.code
                authorizationButton.isEnabled = view.phase == LoginPhase.WAITING
                authorizationButton.setText(if (view.phase == LoginPhase.EXCHANGING) R.string.authorized else R.string.copy_open)
                pendingMessage.text = view.message?.let { Language.message(this, it) }
                    ?: getString(if (view.phase == LoginPhase.EXCHANGING) R.string.finishing else R.string.checking)
                updateCountdown()
            }
            LoginPhase.REQUESTING -> { badge.setText(R.string.connecting); heading.setText(R.string.preparing_access); detail.setText(R.string.requesting_code) }
            LoginPhase.SIGNED_OUT -> { badge.setText(R.string.disconnected); heading.setText(R.string.connect_account); detail.setText(R.string.authorize_detail) }
            LoginPhase.ERROR -> { badge.setText(R.string.check_connection); heading.setText(R.string.try_again); detail.text = Language.message(this, view.message) }
        }
        rendering = false
    }
    private fun updateCountdown() {
        val deadline = current.expiresAt ?: return
        val left = (deadline - System.currentTimeMillis() / 1000).coerceAtLeast(0)
        expiry.text = if (left > 0) getString(R.string.code_expires, "${left / 60}:${(left % 60).toString().padStart(2, '0')}") else getString(R.string.code_expired)
    }
    private fun beginLogin() {
        if (consentShowing || current.phase == LoginPhase.SIGNED_IN || current.phase == LoginPhase.REQUESTING) return
        if (current.phase == LoginPhase.WAITING || current.phase == LoginPhase.EXCHANGING) { graph.resumeLogin(); return }
        consentShowing = true
        AlertDialog.Builder(this).setTitle(R.string.consent_title).setMessage(R.string.consent)
            .setNegativeButton(R.string.not_now, null).setPositiveButton(R.string.continue_action) { _, _ -> graph.beginLogin() }
            .setOnDismissListener { consentShowing = false }.show()
    }
    private fun openAuthorization() {
        val value = current.code ?: return
        if ((current.expiresAt ?: 0) <= System.currentTimeMillis() / 1000) { Toast.makeText(this, R.string.code_expired, Toast.LENGTH_LONG).show(); return }
        val clip = ClipData.newPlainText(getString(R.string.code_clip), value)
        if (Build.VERSION.SDK_INT >= 33) clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DeviceAuth.VERIFICATION_URL)).addCategory(Intent.CATEGORY_BROWSABLE))
            Toast.makeText(this, R.string.code_copied, Toast.LENGTH_LONG).show()
        } catch (_: ActivityNotFoundException) {
            AlertDialog.Builder(this).setMessage(getString(R.string.open_browser_manually, DeviceAuth.VERIFICATION_URL)).setPositiveButton(R.string.understood, null).show()
        }
    }
    private fun chooseWidget() {
        val labels = intArrayOf(R.string.widget_square, R.string.widget_slim, R.string.widget_mini, R.string.widget_extended)
        AlertDialog.Builder(this).setTitle(R.string.choose_widget).setItems(labels.map { getString(it) }.toTypedArray()) { _, which ->
            val manager = getSystemService(AppWidgetManager::class.java)
            if (manager.isRequestPinAppWidgetSupported) manager.requestPinAppWidget(ComponentName(this, CodexWidgetProvider.variants[which].first), null, null)
            else Toast.makeText(this, R.string.pin_help, Toast.LENGTH_LONG).show()
        }.show()
    }
    private fun chooseLanguage() {
        AlertDialog.Builder(this).setTitle(R.string.language).setSingleChoiceItems(arrayOf("Español", "English"), if (Language.code(this) == "es") 0 else 1) { dialog, which ->
            Language.set(this, if (which == 0) "es" else "en")
            graph.notifications.updateLanguage()
            CodexWidgetProvider.renderAll(this)
            dialog.dismiss(); recreate()
        }.setNegativeButton(R.string.back, null).show()
    }
    private fun setReminders(enabled: Boolean) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { graph.repository.setReminders(enabled) }
            if (enabled) Scheduler.refresh(this@SetupActivity, manual = true)
            else { graph.notifications.clear(); Scheduler.cancelReminders(this@SetupActivity) }
        }
    }
    private fun diagnostics() {
        val last = lastState.optLong("lastSuccess").takeIf { it > 0 }?.let { Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM · HH:mm", Language.locale(this))) } ?: getString(R.string.none)
        val message = getString(R.string.diagnostic_text, Language.phase(this, current.phase), last,
            Language.message(this, lastState.optString("diagnostic")), getString(R.string.diagnostic_help), getString(if (graph.notifications.allowed()) R.string.allowed else R.string.blocked))
        AlertDialog.Builder(this).setTitle(R.string.help).setMessage(message + "\n\n" + getString(R.string.mini_help)).setPositiveButton(R.string.close, null).show()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun params(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top); bottomMargin = dp(bottom) }
    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); includeFontPadding = false
        if (bold) setTypeface(typeface, Typeface.BOLD); setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun shape(color: String, radius: Float) = GradientDrawable().apply {
        setColor(Color.parseColor(color)); cornerRadius = radius * resources.displayMetrics.density; setStroke(dp(1), Color.parseColor("#30343C"))
    }
    private fun card(parent: LinearLayout, top: Int = 0) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = shape("#1D2025", 20f); setPadding(dp(16), dp(18), dp(16), dp(16)); parent.addView(this, params(top))
    }
    private fun group(parent: LinearLayout) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; parent.addView(this, params()) }
    private fun button(parent: LinearLayout, label: Int, primary: Boolean = false, action: () -> Unit): Button = Button(this).apply {
        setText(label); isAllCaps = false; textSize = 14f; setTextColor(if (primary) Color.rgb(17, 27, 40) else WHITE)
        background = shape(if (primary) "#9BC4F5" else "#282C33", 14f); minHeight = dp(48); minimumHeight = dp(48)
        setPadding(dp(12), dp(10), dp(12), dp(10)); parent.addView(this, params(top = 10)); setOnClickListener { action() }
    }
    companion object {
        private val WHITE = Color.rgb(239, 241, 245)
        private val MUTED = Color.rgb(162, 170, 182)
        private val BLUE = Color.rgb(155, 196, 245)
        private val GREEN = Color.rgb(132, 222, 170)
    }
}
