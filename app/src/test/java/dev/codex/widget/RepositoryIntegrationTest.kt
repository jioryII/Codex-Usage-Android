package dev.codex.widget

import dev.codex.widget.data.*
import dev.codex.widget.domain.*
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class MemoryStore : StateStore {
    private var state = "{}"
    override fun read() = JSONObject(state)
    override fun save(state: JSONObject) { this.state = state.toString() }
    override fun clear() { state = "{}" }
}

class RepositoryIntegrationTest {
    private val now = 1000L
    private fun session() = Session("access", "refresh", "acct", 100000)
    private fun snapshot(credits: List<ResetCredit>? = emptyList()) = UsageSnapshot(
        UsageWindow(72, 10000), UsageWindow(22, 200000), credits?.size, credits, now, "fake test only")
    private val noHttp = HttpTransport { _, _, _, _ -> throw AssertionError("Unexpected authentication request") }

    @Test fun `real repository reads source caches and clears every local account field on logout`() = runTest {
        val store = MemoryStore()
        var reads = 0
        val repo = UsageRepository(store, DeviceAuth(noHttp), { object : UsageSource {
            override suspend fun read(now: Long): UsageSnapshot { reads++; return snapshot() }
        } }, { now })
        repo.signIn(session())
        assertEquals(RefreshResult.SUCCESS, repo.refresh())
        assertEquals(1, reads)
        assertEquals(72, SnapshotCodec.decode(store.read().getJSONObject("snapshot"))!!.fiveHours!!.remaining)
        repo.signOut()
        assertEquals(0, store.read().length())
        assertEquals(RefreshResult.SIGNED_OUT, repo.refresh())
        assertEquals(1, reads)
    }

    @Test fun `offline preserves cache and cannot notify from old credit data`() = runTest {
        val store = MemoryStore()
        var offline = false
        val repo = UsageRepository(store, DeviceAuth(noHttp), { object : UsageSource {
            override suspend fun read(now: Long): UsageSnapshot {
                if (offline) throw IOException("Offline")
                return snapshot(listOf(ResetCredit("a", "available", now + 100000)))
            }
        } }, { now })
        repo.signIn(session()); repo.refresh()
        val original = store.read().getJSONObject("snapshot").toString()
        offline = true
        var notified = false
        assertEquals(RefreshResult.RETRY, repo.refresh { _, _ -> notified = true })
        assertFalse(notified); assertEquals(original, store.read().getJSONObject("snapshot").toString())
    }

    @Test fun `429 persists retry deadline and prevents a connectivity retry storm`() = runTest {
        val store = MemoryStore()
        var reads = 0
        val repo = UsageRepository(store, DeviceAuth(noHttp), { object : UsageSource {
            override suspend fun read(now: Long): UsageSnapshot { reads++; throw HttpFailure(429, now + 3000) }
        } }, { now })
        repo.signIn(session())
        assertEquals(RefreshResult.COOLDOWN, repo.refresh())
        assertEquals(RefreshResult.COOLDOWN, repo.refresh())
        assertEquals(1, reads); assertEquals(4000, store.read().getLong("retryAt"))
    }

    @Test fun `refresh token rotates and is persisted before usage read`() = runTest {
        val store = MemoryStore()
        val http = HttpTransport { url, body, _, _ ->
            assertTrue(url.endsWith("/oauth/token")); assertTrue(body!!.contains("refresh_token"))
            """{"access_token":"new-access","refresh_token":"rotated-refresh","expires_in":3600}"""
        }
        val repo = UsageRepository(store, DeviceAuth(http), { current -> object : UsageSource {
            override suspend fun read(now: Long): UsageSnapshot {
                assertEquals("rotated-refresh", store.read().getJSONObject("session").getString("refresh"))
                assertEquals("new-access", current.access)
                return snapshot()
            }
        } }, { now })
        repo.signIn(Session("old", "old-refresh", "acct", 1100))
        assertEquals(RefreshResult.SUCCESS, repo.refresh())
    }

    @Test fun `revoked refresh token clears identity quota and reminder ledger`() = runTest {
        val store = MemoryStore()
        val http = HttpTransport { _, _, _, _ -> throw HttpFailure(400) }
        val repo = UsageRepository(store, DeviceAuth(http), { error("Usage must not run") }, { now })
        repo.signIn(Session("old", "old-refresh", "acct", 0))
        store.save(store.read().put("snapshot", SnapshotCodec.encode(snapshot())).put("ledger", JSONObject().put("x", 2)))
        assertEquals(RefreshResult.SIGNED_OUT, repo.refresh())
        assertFalse(store.read().has("session")); assertFalse(store.read().has("snapshot")); assertFalse(store.read().has("ledger"))
    }

    @Test fun `internal source sends legitimate account bearer and reads details without any redemption`() = runTest {
        val requested = mutableListOf<String>()
        val http = HttpTransport { url, body, _, headers ->
            assertNull(body)
            assertEquals("Bearer access", headers["Authorization"])
            assertEquals("acct", headers["ChatGPT-Account-Id"])
            requested += url
            if (url.endsWith("/usage")) UsageTest.INTERNAL
            else """{"available_count":2,"credits":[{"id":"a","reset_type":"codex_rate_limits","status":"available","expires_at":"2026-07-17T00:00:00Z"}]}"""
        }
        val result = InternalUsageSource(http, session()).read(now)
        assertEquals(72, result.fiveHours!!.remaining); assertEquals(1, result.credits!!.size)
        assertEquals(2, requested.size)
        assertFalse(requested.any { it.contains("consume") })
    }

    @Test fun `missing credit details does not invent expiry or carry old details forward`() = runTest {
        val http = HttpTransport { url, _, _, _ ->
            if (url.endsWith("/usage")) UsageTest.INTERNAL else throw HttpFailure(404)
        }
        val result = InternalUsageSource(http, session()).read(now)
        assertNotNull(result.fiveHours); assertNull(result.credits)
    }

    @Test fun `server response for another account is rejected`() = runTest {
        val http = HttpTransport { _, _, _, _ -> UsageTest.INTERNAL.replace("acct", "other") }
        try { InternalUsageSource(http, session()).read(now); fail("Must reject mismatched account") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun `official adapter calls the documented method and asks for credit detail`() = runTest {
        val source = AppServerUsageSource { method, params ->
            assertEquals("account/rateLimits/read", method)
            assertFalse(JSONObject(params).getBoolean("excludeResetCreditDetails"))
            UsageTest.RPC
        }
        assertEquals(22, source.read(now).weekly!!.remaining)
    }
    @Test fun `production intervals coalesce manual connectivity and periodic requests across repository restarts`() = runTest {
        val store=MemoryStore();var clock=1000L;var reads=0
        fun repository() = UsageRepository(store,DeviceAuth(noHttp),{ object:UsageSource {
            override suspend fun read(now:Long):UsageSnapshot { reads++;return snapshot() }
        } },{clock})
        val repo=repository();repo.signIn(session())
        assertEquals(RefreshResult.SUCCESS,repo.refresh(60))
        repeat(4) { assertEquals(RefreshResult.CACHED,repository().refresh(60)) }
        clock+=60
        assertEquals(RefreshResult.CACHED,repo.refresh(300))
        assertEquals(RefreshResult.SUCCESS,repo.refresh(60))
        clock+=300
        assertEquals(RefreshResult.SUCCESS,repo.refresh(300))
        assertEquals(3,reads)
    }
    @Test fun `production errors persist backoff and forbid rapid retry until server permits`() = runTest {
        val store=MemoryStore();var clock=1000L;var reads=0
        val repo=UsageRepository(store,DeviceAuth(noHttp),{object:UsageSource {
            override suspend fun read(now:Long):UsageSnapshot { reads++;throw IOException("offline") }
        }},{clock})
        repo.signIn(session())
        assertEquals(RefreshResult.COOLDOWN,repo.refresh(60));assertEquals(1060,store.read().getLong("retryAt"))
        assertEquals(RefreshResult.COOLDOWN,repo.refresh(60));assertEquals(1,reads)
        clock=1060
        assertEquals(RefreshResult.COOLDOWN,repo.refresh(60));assertEquals(1180,store.read().getLong("retryAt"))
    }
    @Test fun `blocked independent client is not hammered by repeated manual attempts`() = runTest {
        val store=MemoryStore();var reads=0
        val repo=UsageRepository(store,DeviceAuth(noHttp),{object:UsageSource {
            override suspend fun read(now:Long):UsageSnapshot { reads++;throw HttpFailure(403) }
        }},{now})
        repo.signIn(session())
        repeat(5) { assertEquals(RefreshResult.COOLDOWN,repo.refresh(60)) }
        assertEquals(1,reads);assertEquals(now+3600,store.read().getLong("retryAt"))
        assertTrue(store.read().has("session"))
    }

}
