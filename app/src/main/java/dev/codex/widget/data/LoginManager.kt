package dev.codex.widget.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException

enum class LoginPhase { SIGNED_OUT, REQUESTING, WAITING, EXCHANGING, SIGNED_IN, ERROR }
data class LoginView(val phase: LoginPhase, val code: String? = null, val expiresAt: Long? = null,
    val email: String? = null, val message: String? = null)
sealed class LoginStep {
    data object Done : LoginStep()
    data object Retry : LoginStep()
    data class Wait(val seconds: Long) : LoginStep()
}

/** Durable single-account device flow. No Activity or browser lifecycle owns these operations. */
class LoginManager(
    private val repository: UsageRepository,
    private val auth: DeviceAuth,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val operation = Mutex()

    suspend fun view(): LoginView {
        val state = repository.readState()
        state.optJSONObject("session")?.let {
            return LoginView(LoginPhase.SIGNED_IN, email = Session.decode(it).email)
        }
        val pending = state.optJSONObject(PENDING)
        if (pending != null && pending.getLong("expiresAt") > now() && !pending.optBoolean("failed")) {
            return LoginView(if (pending.has("grant")) LoginPhase.EXCHANGING else LoginPhase.WAITING,
                pending.getString("code"), pending.getLong("expiresAt"), message = state.optional("loginMessage"))
        }
        if (state.optBoolean("loginRequesting")) return LoginView(LoginPhase.REQUESTING)
        val error = state.optional("loginError") ?: pending?.let { "login_expired" }
        return LoginView(if (error == null) LoginPhase.SIGNED_OUT else LoginPhase.ERROR, message = error)
    }

    suspend fun recover() = operation.withLock {
        val previous = repository.readState()
        if (!previous.has("loginRequesting") && !(previous.has("session") && previous.has(PENDING))) return@withLock
        repository.transaction { state ->
            state.remove("loginRequesting")
            if (state.has("session")) state.remove(PENDING)
        }
    }

    /** Existing sessions and unexpired codes are reused; never starts a second authorization. */
    suspend fun begin() = operation.withLock {
        val state = repository.readState()
        if (state.has("session")) return@withLock
        val pending = state.optJSONObject(PENDING)
        if (pending != null && pending.getLong("expiresAt") > now() && !pending.optBoolean("failed")) return@withLock
        repository.transaction { it.put("loginRequesting", true); it.remove("loginError"); it.remove("loginMessage"); it.remove(PENDING) }
        try {
            withContext(NonCancellable) {
                val device = auth.begin()
                repository.transaction {
                    if (!it.has("session")) it.put(PENDING, JSONObject().put("id", device.id).put("code", device.code)
                        .put("interval", device.interval).put("expiresAt", now() + 900).put("nextPollAt", now() + device.interval))
                    it.remove("loginRequesting")
                }
            }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            repository.transaction { it.remove("loginRequesting"); it.put("loginError", errorMessage(error, false)) }
        }
    }

    suspend fun cancel() = operation.withLock {
        repository.transaction { it.remove(PENDING); it.remove("loginRequesting"); it.remove("loginError"); it.remove("loginMessage") }
    }
    suspend fun signOut() = operation.withLock { repository.signOut() }

    suspend fun step(): LoginStep = operation.withLock {
        val state = repository.readState()
        if (state.has("session")) return@withLock LoginStep.Done
        val pending = state.optJSONObject(PENDING) ?: return@withLock LoginStep.Done
        if (pending.optBoolean("failed")) return@withLock LoginStep.Done
        if (now() >= pending.getLong("expiresAt")) {
            repository.transaction { it.remove(PENDING); it.put("loginError", "login_expired") }
            return@withLock LoginStep.Done
        }
        val next = pending.optLong("nextPollAt")
        if (!pending.has("grant") && next > now()) return@withLock LoginStep.Wait(next - now())
        try {
            // Save a one-time grant before exchanging it. Cancellation cannot discard a received response.
            withContext(NonCancellable) {
                var grant = pending.optJSONObject("grant")?.let(AuthorizationGrant::decode)
                if (grant == null) {
                    repository.transaction { it.getJSONObject(PENDING).put("nextPollAt", now() + pending.getLong("interval")) }
                    grant = auth.poll(DeviceCode(pending.getString("id"), pending.getString("code"), pending.getLong("interval")))
                    if (grant != null) repository.transaction { it.getJSONObject(PENDING).put("grant", grant.encode()); it.remove("loginMessage") }
                }
                if (grant == null) return@withContext LoginStep.Wait(pending.getLong("interval"))
                val session = auth.exchange(grant)
                repository.transaction {
                    // A cancelled/replaced flow must never resurrect or replace an existing session.
                    if (!it.has("session") && it.optJSONObject(PENDING)?.optString("id") == pending.getString("id")) {
                        it.put("session", session.encode()); it.remove("snapshot"); it.remove("ledger")
                        it.remove("lastSuccess"); it.remove("lastAttempt"); it.remove("retryAt"); it.remove("diagnostic")
                    }
                    it.remove(PENDING); it.remove("loginError"); it.remove("loginMessage"); it.remove("loginRequesting")
                }
                LoginStep.Done
            }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            val temporary = error is IOException && (error !is HttpFailure || error.status == 429 || error.status >= 500)
            repository.transaction {
                if (temporary) {
                    it.put("loginMessage", errorMessage(error, true))
                    if (error is HttpFailure && error.status == 429) it.optJSONObject(PENDING)?.put("nextPollAt", error.retryAt ?: now() + 30)
                } else {
                    it.optJSONObject(PENDING)?.put("failed", true)
                    it.put("loginError", errorMessage(error, false))
                }
            }
            if (temporary) LoginStep.Retry else LoginStep.Done
        }
    }

    private fun errorMessage(error: Exception, retrying: Boolean): String = when {
        error is HttpFailure && error.status == 429 -> "login_wait"
        retrying -> "login_offline"
        error is HttpFailure -> "login_http:${error.status}"
        else -> "login_failed"
    }
    private fun JSONObject.optional(key: String) = optString(key).takeUnless { it.isBlank() || it == "null" }
    companion object { const val PENDING = "pendingLogin" }
}
