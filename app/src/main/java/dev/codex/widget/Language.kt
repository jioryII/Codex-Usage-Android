package dev.codex.widget

import android.content.Context
import android.content.res.Configuration
import dev.codex.widget.data.LoginPhase
import java.util.Locale

/** Non-sensitive presentation preference, independent of encrypted account data and logout. */
object Language {
    private const val PREFS = "presentation"
    fun code(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("language", null)
        ?: "en"
    fun locale(context: Context) = Locale.forLanguageTag(code(context))
    fun context(context: Context): Context = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
        setLocale(locale(context))
    })
    fun set(context: Context, code: String) {
        require(code == "es" || code == "en")
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("language", code).apply()
    }
    fun message(context: Context, key: String?): String {
        val c = context(context)
        return when {
            key == null || key.isBlank() -> c.getString(R.string.usage_none)
            key == "login_expired" || key.startsWith("El código caducó") -> c.getString(R.string.code_expired)
            key == "login_wait" || key.startsWith("OpenAI pidió esperar") -> c.getString(R.string.login_wait)
            key == "login_offline" || key.startsWith("Sin conexión con OpenAI") -> c.getString(R.string.login_offline)
            key.startsWith("login_http:") -> c.getString(R.string.login_rejected, key.substringAfter(':').toIntOrNull() ?: 0)
            key == "login_failed" || key.startsWith("No se pudo completar") -> c.getString(R.string.login_failed)
            key == "usage_ok" || key == "Usage updated." -> c.getString(R.string.usage_ok)
            key == "usage_partial" || key.startsWith("Usage updated;") -> c.getString(R.string.usage_partial)
            key == "session_expired" || key.startsWith("Session expired.") -> c.getString(R.string.session_expired)
            key.startsWith("http:") -> c.getString(R.string.http_error, key.substringAfter(':').toIntOrNull() ?: 0)
            key == "offline" || key.startsWith("Offline or") -> c.getString(R.string.offline)
            else -> c.getString(R.string.incompatible)
        }
    }
    fun phase(context: Context, phase: LoginPhase): String = context(context).getString(when(phase) {
        LoginPhase.SIGNED_IN -> R.string.signed_in
        LoginPhase.SIGNED_OUT -> R.string.disconnected
        LoginPhase.REQUESTING -> R.string.connecting
        LoginPhase.WAITING, LoginPhase.EXCHANGING -> R.string.auth_in_progress
        LoginPhase.ERROR -> R.string.check_connection
    })
}
