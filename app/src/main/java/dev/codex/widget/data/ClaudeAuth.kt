package dev.codex.widget.data

import dev.codex.widget.domain.UsageParser
import dev.codex.widget.domain.UsageProvider
import dev.codex.widget.domain.UsageSnapshot
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Compatibility with Claude Code's public PKCE client, not an approved third-party registration. */
class ClaudeAuth(private val http: HttpTransport, private val now: () -> Long = { System.currentTimeMillis() / 1000 }) {
    fun begin(): JSONObject {
        fun nonce() = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
        return JSONObject().put("provider", UsageProvider.CLAUDE.name).put("id", nonce())
            .put("verifier", nonce()).put("code", "").put("expiresAt", now() + 900)
    }

    suspend fun exchange(pending: JSONObject): Session {
        require(pending.getLong("expiresAt") > now()) { "Authorization expired" }
        return parse(JSONObject(http.json(TOKEN_URL, JSONObject()
            .put("grant_type", "authorization_code").put("client_id", CLIENT_ID)
            .put("code", pending.getJSONObject("grant").getString("code"))
            .put("state", pending.getString("id")).put("code_verifier", pending.getString("verifier"))
            .put("redirect_uri", REDIRECT_URL))), null)
    }

    suspend fun refresh(session: Session): Session {
        require(session.provider == UsageProvider.CLAUDE)
        return parse(JSONObject(http.json(TOKEN_URL, JSONObject().put("grant_type", "refresh_token")
            .put("refresh_token", session.refresh).put("client_id", CLIENT_ID))), session)
    }

    private fun parse(root: JSONObject, previous: Session?): Session {
        val access = root.getString("access_token").also { require(it.isNotBlank()) }
        val refresh = root.optString("refresh_token").takeUnless { it.isBlank() || it == "null" }
            ?: previous?.refresh ?: error("No refresh token")
        val account = root.optJSONObject("account")
        val id = account?.optString("uuid")?.takeIf { it.isNotBlank() }
            ?: previous?.accountId ?: error("No Claude account in authorization")
        require(previous == null || id == previous.accountId) { "Authorization account changed" }
        if (root.has("scope")) require("user:profile" in root.getString("scope").split(' ')) { "Missing usage scope" }
        val expires = root.getLong("expires_in").also { require(it > 0 && it <= 366L * 86400) }
        return Session(access, refresh, id, now() + expires,
            account?.optString("email_address")?.takeUnless { it.isBlank() || it == "null" } ?: previous?.email,
            UsageProvider.CLAUDE)
    }

    companion object {
        const val CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
        const val TOKEN_URL = "https://platform.claude.com/v1/oauth/token"
        const val REDIRECT_URL = "https://platform.claude.com/oauth/code/callback"
        fun authorizationUrl(pending: JSONObject): String {
            val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(pending.getString("verifier").toByteArray(Charsets.US_ASCII)))
            val params = listOf("code" to "true", "client_id" to CLIENT_ID, "response_type" to "code",
                "redirect_uri" to REDIRECT_URL, "scope" to "user:profile", "code_challenge" to challenge,
                "code_challenge_method" to "S256", "state" to pending.getString("id"))
            return "https://claude.ai/oauth/authorize?" + params.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        }

        fun validateCode(value: String, pending: JSONObject): String {
            val parts = value.trim().split('#')
            require(parts.size == 2 && parts[0].isNotBlank() && parts[1] == pending.getString("id")) { "Invalid authorization code or state" }
            return parts[0]
        }
    }
}

class ClaudeUsageSource(private val http: HttpTransport, private val session: Session) : UsageSource {
    override suspend fun read(now: Long): UsageSnapshot {
        require(session.provider == UsageProvider.CLAUDE)
        return UsageParser.claude(http.request("https://api.anthropic.com/api/oauth/usage", null, null,
            mapOf("Authorization" to "Bearer ${session.access}", "anthropic-beta" to "oauth-2025-04-20")), now)
    }
}
