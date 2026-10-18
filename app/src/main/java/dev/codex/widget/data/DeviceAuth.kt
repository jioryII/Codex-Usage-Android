package dev.codex.widget.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Base64
import kotlin.coroutines.coroutineContext

class DeviceCode(val id: String, val code: String, val interval: Long)
class AuthorizationGrant(val code: String, val verifier: String) {
    fun encode(): JSONObject = JSONObject().put("code", code).put("verifier", verifier)
    companion object {
        fun decode(root: JSONObject) = AuthorizationGrant(root.getString("code"), root.getString("verifier"))
    }
}
class Session(val access: String, val refresh: String, val accountId: String, val expires: Long, val email: String? = null) {
    fun encode(): JSONObject = JSONObject().put("access", access).put("refresh", refresh)
        .put("account", accountId).put("expires", expires).put("email", email ?: JSONObject.NULL)
    companion object {
        fun decode(json: JSONObject) = Session(json.getString("access"), json.getString("refresh"),
            json.getString("account"), json.getLong("expires"), json.optString("email").takeUnless { it.isBlank() || it == "null" })
    }
}

/** Compatibility with the public Codex CLI device flow, not a third-party OAuth registration. */
class DeviceAuth(private val http: HttpTransport) {
    suspend fun begin(): DeviceCode {
        val root = JSONObject(http.json("$ISSUER/api/accounts/deviceauth/usercode",
            JSONObject().put("client_id", CLIENT_ID)))
        val code = root.optString("user_code").ifBlank { root.getString("usercode") }
        require(code.isNotBlank()) { "Empty device code" }
        return DeviceCode(root.getString("device_auth_id"), code,
            root.optString("interval", "5").toLongOrNull()?.coerceIn(1, 60) ?: 5)
    }

    suspend fun await(code: DeviceCode, elapsedMillis: () -> Long): Session {
        val deadline = elapsedMillis() + 15 * 60_000
        while (elapsedMillis() < deadline) {
            delay(code.interval * 1000)
            coroutineContext.ensureActive()
            if (elapsedMillis() >= deadline) break
            val pending = poll(code) ?: continue
            return exchange(pending)
        }
        throw java.io.IOException("Device code expired; start sign-in again")
    }

    suspend fun poll(code: DeviceCode): AuthorizationGrant? = try {
        val pending = JSONObject(http.json("$ISSUER/api/accounts/deviceauth/token", JSONObject()
            .put("device_auth_id", code.id).put("user_code", code.code)))
        AuthorizationGrant(pending.getString("authorization_code"), pending.getString("code_verifier"))
    } catch (error: HttpFailure) {
        if (error.status == 403 || error.status == 404) null else throw error
    }

    suspend fun exchange(grant: AuthorizationGrant): Session {
        val form = listOf("grant_type" to "authorization_code", "client_id" to CLIENT_ID,
            "code" to grant.code, "code_verifier" to grant.verifier,
            "redirect_uri" to "$ISSUER/deviceauth/callback")
            .joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        return parse(JSONObject(http.request("$ISSUER/oauth/token", form,
            "application/x-www-form-urlencoded", emptyMap())), null)
    }

    suspend fun refresh(session: Session): Session = parse(JSONObject(http.json("$ISSUER/oauth/token",
        JSONObject().put("client_id", CLIENT_ID).put("grant_type", "refresh_token")
            .put("refresh_token", session.refresh))), session)

    private fun parse(root: JSONObject, previous: Session?): Session {
        val access = root.getString("access_token").also { require(it.isNotBlank()) }
        val idClaims = root.optString("id_token").takeIf { it.isNotBlank() }?.let(::claims)
        val accessClaims = claims(access)
        val account = idClaims?.optJSONObject("https://api.openai.com/auth")?.optString("chatgpt_account_id")
            ?.takeIf { it.isNotBlank() }
            ?: accessClaims.optJSONObject("https://api.openai.com/auth")?.optString("chatgpt_account_id")
                ?.takeIf { it.isNotBlank() }
            ?: previous?.accountId ?: error("No ChatGPT account in authorization")
        require(previous == null || account == previous.accountId) { "Authorization account changed" }
        val refresh = root.optString("refresh_token").takeIf { it.isNotBlank() }
            ?: previous?.refresh ?: error("No refresh token")
        val expires = accessClaims.optLong("exp", 0).takeIf { it > 0 }
            ?: (System.currentTimeMillis() / 1000 + root.optLong("expires_in", 3600))
        val email = idClaims?.optString("email")?.takeIf { it.isNotBlank() }
            ?: accessClaims.optString("email").takeIf { it.isNotBlank() } ?: previous?.email
        return Session(access, refresh, account, expires, email)
    }

    private fun claims(token: String): JSONObject = runCatching {
        JSONObject(String(Base64.getUrlDecoder().decode(token.split('.')[1]), Charsets.UTF_8))
    }.getOrDefault(JSONObject())

    companion object {
        const val ISSUER = "https://auth.openai.com"
        const val VERIFICATION_URL = "$ISSUER/codex/device"
        // Public (non-secret) Codex CLI client id. Server may reject independent clients.
        const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    }
}
