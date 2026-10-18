package dev.codex.widget.data

import dev.codex.widget.domain.UsageParser
import dev.codex.widget.domain.UsageSnapshot
import dev.codex.widget.domain.SyncPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.IOException

class InternalUsageSource(private val http: HttpTransport, private val session: Session) : UsageSource {
    override suspend fun read(now: Long): UsageSnapshot {
        val headers = mapOf("Authorization" to "Bearer ${session.access}", "ChatGPT-Account-Id" to session.accountId)
        val body = http.request("$BASE/usage", null, null, headers)
        val account = JSONObject(body).optString("account_id").takeIf { it.isNotBlank() && it != "null" }
        require(account == null || account == session.accountId) { "Usage account mismatch" }
        val usage = UsageParser.internalUsage(body, now)
        if (usage.availableCount == 0) return usage.copy(credits = emptyList())
        return try {
            val (count, credits) = UsageParser.internalCredits(http.request("$BASE/rate-limit-reset-credits", null, null, headers))
            usage.copy(availableCount = count, credits = credits)
        } catch (error: CancellationException) { throw error }
        catch (error: HttpFailure) {
            if (error.status == 401 || error.status == 429) throw error
            usage.copy(credits = null)
        } catch (_: Exception) { usage.copy(credits = null) }
    }
    companion object { private const val BASE = "https://chatgpt.com/backend-api/wham" }
}

enum class RefreshResult { SUCCESS, RETRY, SIGNED_OUT, COOLDOWN, CACHED }

class UsageRepository(
    val store: StateStore,
    private val auth: DeviceAuth,
    private val source: (Session) -> UsageSource,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val mutex = Mutex()
    private val revision = MutableStateFlow(0L)
    val changes = revision.asStateFlow()
    private fun save(state: JSONObject) { store.save(state); revision.value++ }
    suspend fun readState(): JSONObject = mutex.withLock { store.read() }
    suspend fun <T> transaction(block: (JSONObject) -> T): T = mutex.withLock {
        val state = store.read()
        val result = block(state)
        save(state)
        result
    }

    suspend fun signIn(session: Session) = mutex.withLock {
        store.clear()
        save(JSONObject().put("session", session.encode()).put("reminders", false))
    }
    suspend fun signOut() = mutex.withLock { store.clear(); revision.value++ }
    suspend fun setReminders(enabled: Boolean) = mutex.withLock {
        save(store.read().put("reminders", enabled))
    }

    suspend fun refresh(minIntervalSeconds: Long = 0, afterFresh: suspend (UsageSnapshot, JSONObject) -> Unit = { _, _ -> }): RefreshResult = mutex.withLock {
        val state = store.read()
        var session = state.optJSONObject("session")?.let(Session::decode) ?: return@withLock RefreshResult.SIGNED_OUT
        val time = now()
        if (state.optLong("retryAt") > time) { revision.value++; return@withLock RefreshResult.COOLDOWN }
        if (SyncPolicy.recentlyAttempted(state.optLong("lastAttempt"), time, minIntervalSeconds)) {
            revision.value++
            return@withLock RefreshResult.CACHED
        }
        state.put("lastAttempt", time)
        save(state)
        try {
            suspend fun renew() = withContext(NonCancellable) {
                try { session = auth.refresh(session) }
                catch (error: HttpFailure) {
                    if (error.status == 400 || error.status == 401 || error.status == 403) {
                        state.remove("session"); state.remove("snapshot"); state.remove("ledger")
                    }
                    throw error
                }
                // Persist rotating refresh tokens before any further request.
                state.put("session", session.encode()); save(state)
            }
            if (session.expires <= time + 300) renew()
            val snapshot = try { source(session).read(time) }
            catch (error: HttpFailure) {
                if (error.status != 401) throw error
                renew()
                source(session).read(time)
            }
            state.put("snapshot", SnapshotCodec.encode(snapshot)).put("lastSuccess", time)
                .put("diagnostic", if (snapshot.credits == null) "usage_partial" else "usage_ok")
                .put("retryAt", 0).put("failures", 0).put("lastFailed", false)
            save(state)
            afterFresh(snapshot, state)
            save(state)
            RefreshResult.SUCCESS
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            val signedOut = !state.has("session")
            val failures = (state.optInt("failures") + 1).coerceAtMost(7)
            val cooldown = when {
                signedOut -> 0L
                error is HttpFailure && error.status == 429 -> error.retryAt ?: time + 900
                error is HttpFailure && error.status in 400..499 -> time + 3600
                minIntervalSeconds > 0 -> time + SyncPolicy.retryDelay(failures)
                else -> 0L
            }
            state.put("lastFailed", true).put("failures", failures)
            state.put("retryAt", cooldown).put("diagnostic", when {
                signedOut -> "session_expired"
                error is HttpFailure -> "http:${error.status}"
                error is IOException -> "offline"
                else -> "incompatible"
            })
            save(state)
            when { signedOut -> RefreshResult.SIGNED_OUT; cooldown > 0 -> RefreshResult.COOLDOWN; else -> RefreshResult.RETRY }
        }
    }
}
