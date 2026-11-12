package dev.codex.widget

import dev.codex.widget.data.*
import dev.codex.widget.domain.*
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64

class ClaudeTest {
    private val now = 1000L
    private val noHttp = HttpTransport { _, _, _, _ -> error("Unexpected HTTP request") }
    private fun session(expires: Long = 100000) = Session("claude-access", "claude-refresh", "claude-account", expires, provider = UsageProvider.CLAUDE)
    private fun tokens() = """{"access_token":"new-access","refresh_token":"new-refresh","expires_in":7200,"scope":"user:profile","account":{"uuid":"claude-account","email_address":"person@example.test"}}"""

    @Test fun `Claude uses overall windows and converts utilization to remaining percent`() {
        val snapshot = UsageParser.claude(USAGE, now)
        assertEquals(72, snapshot.fiveHours!!.remaining)
        assertEquals(22, snapshot.weekly!!.remaining)
        assertEquals(Instant.parse("2026-11-13T12:00:00Z").epochSecond, snapshot.fiveHours!!.resetsAt)
        assertEquals(UsageProvider.CLAUDE, snapshot.provider)
        assertNull(snapshot.credits); assertNull(snapshot.availableCount)
        assertEquals(snapshot, SnapshotCodec.decode(SnapshotCodec.encode(snapshot)))
    }

    @Test fun `missing and expired windows never invent quota and invalid contracts fail`() {
        val snapshot = UsageParser.claude("""{"five_hour":null,"seven_day":{"utilization":null}}""", now)
        assertNull(snapshot.fiveHours); assertNull(snapshot.weekly)
        val expired = UsageParser.claude("""{"five_hour":{"utilization":0,"resets_at":"1970-01-01T00:01:00Z"}}""", now)
        assertNull(WidgetPolicy.remaining(expired.fiveHours, now))
        for (body in listOf("{}", """{"error":"unauthorized"}""", """{"five_hour":{"utilization":"NaN"}}""",
            """{"five_hour":{"utilization":10,"resets_at":"bad-date"}}""")) {
            assertTrue(runCatching { UsageParser.claude(body, now) }.isFailure)
        }
    }

    @Test fun `old sessions and snapshots retain Codex provider and Claude colors stay orange`() {
        val legacy = session().encode().apply { remove("provider") }
        assertEquals(UsageProvider.CODEX, Session.decode(legacy).provider)
        val snapshot = SnapshotCodec.encode(UsageParser.claude(USAGE, now)).apply { remove("provider") }
        assertEquals(UsageProvider.CODEX, SnapshotCodec.decode(snapshot)!!.provider)
        for (remaining in listOf(0, 24, 25, 49, 50, 74, 75, 100))
            assertEquals(QuotaTone.ORANGE, WidgetPolicy.tone(remaining, UsageProvider.CLAUDE))
        assertEquals(QuotaTone.UNKNOWN, WidgetPolicy.tone(null, UsageProvider.CLAUDE))
        assertEquals(QuotaTone.FULL, WidgetPolicy.tone(100))
    }

    @Test fun `usage sends Claude bearer only to Claude and never requests Codex credits`() = runTest {
        var reads = 0
        val source = ClaudeUsageSource(HttpTransport { url, body, _, headers ->
            reads++
            assertEquals("https://api.anthropic.com/api/oauth/usage", url)
            assertNull(body); assertEquals("Bearer claude-access", headers["Authorization"])
            assertEquals("oauth-2025-04-20", headers["anthropic-beta"])
            assertFalse(headers.containsKey("ChatGPT-Account-Id"))
            USAGE
        }, session())
        assertEquals(UsageProvider.CLAUDE, source.read(now).provider)
        assertEquals(1, reads)
    }

    @Test fun `browser authorization has random state and PKCE with only usage scope`() {
        val auth = ClaudeAuth(noHttp) { now }
        val pending = auth.begin()
        assertNotEquals(pending.getString("id"), auth.begin().getString("id"))
        val url = URI(ClaudeAuth.authorizationUrl(pending))
        assertEquals("claude.ai", url.host)
        val params = url.rawQuery.split('&').associate { part ->
            val pair = part.split('=', limit = 2); pair[0] to URLDecoder.decode(pair[1], "UTF-8")
        }
        assertEquals("user:profile", params["scope"])
        assertEquals("S256", params["code_challenge_method"])
        val expected = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
            .digest(pending.getString("verifier").toByteArray(Charsets.US_ASCII)))
        assertEquals(expected, params["code_challenge"])
        assertEquals(ClaudeAuth.REDIRECT_URL, params["redirect_uri"])
        assertEquals("code", ClaudeAuth.validateCode("code#${pending.getString("id")}", pending))
        assertTrue(runCatching { ClaudeAuth.validateCode("code#other-state", pending) }.isFailure)
    }

    @Test fun `pending Claude authorization survives restart without polling and exchanges validated code`() = runTest {
        val store = MemoryStore()
        val http = HttpTransport { url, body, _, _ ->
            assertEquals(ClaudeAuth.TOKEN_URL, url)
            val request = JSONObject(body!!)
            val pending = store.read().getJSONObject(LoginManager.PENDING)
            assertEquals("authorization_code", request.getString("grant_type"))
            assertEquals("returned-code", request.getString("code"))
            assertEquals(pending.getString("id"), request.getString("state"))
            assertEquals(pending.getString("verifier"), request.getString("code_verifier"))
            tokens()
        }
        val repository = UsageRepository(store, DeviceAuth(noHttp), { error("Unexpected usage") }, { now })
        fun login() = LoginManager(repository, DeviceAuth(noHttp), ClaudeAuth(http) { now }) { now }
        login().begin(UsageProvider.CLAUDE)
        val originalUrl = login().view().authorizationUrl
        login().recover()
        assertEquals(originalUrl, login().view().authorizationUrl)
        assertEquals(UsageProvider.CLAUDE, login().view().provider)
        assertEquals(LoginStep.Done, login().step())
        assertEquals(LoginPhase.WAITING, login().view().phase)
        assertTrue(runCatching { login().submitClaudeCode("wrong#state") }.isFailure)
        assertFalse(store.read().getJSONObject(LoginManager.PENDING).has("grant"))
        login().submitClaudeCode("returned-code#${store.read().getJSONObject(LoginManager.PENDING).getString("id")}")
        assertEquals(LoginPhase.EXCHANGING, login().view().phase)
        assertEquals(LoginStep.Done, login().step())
        assertEquals(LoginPhase.SIGNED_IN, login().view().phase)
        assertEquals(UsageProvider.CLAUDE, Session.decode(store.read().getJSONObject("session")).provider)
        assertFalse(store.read().has(LoginManager.PENDING))
    }

    @Test fun `cancelled and expired authorization cannot resurrect a Claude session`() = runTest {
        var clock = now
        val repo = UsageRepository(MemoryStore(), DeviceAuth(noHttp), { error("Unexpected usage") })
        val login = LoginManager(repo, DeviceAuth(noHttp), ClaudeAuth(noHttp) { clock }) { clock }
        login.begin(UsageProvider.CLAUDE)
        val code = "code#${repo.readState().getJSONObject(LoginManager.PENDING).getString("id") }"
        clock += 901
        assertTrue(runCatching { login.submitClaudeCode(code) }.isFailure)
        assertEquals(LoginStep.Done, login.step())
        login.begin(UsageProvider.CLAUDE); login.cancel()
        assertTrue(runCatching { login.submitClaudeCode(code) }.isFailure)
        assertEquals(LoginPhase.SIGNED_OUT, login.view().phase)
    }

    @Test fun `Claude exchange honors server cooldown across restarts with a saved grant`() = runTest {
        var clock = now
        var exchanges = 0
        val http = HttpTransport { _, _, _, _ ->
            exchanges++
            if (exchanges == 1) throw HttpFailure(429, clock + 120)
            tokens()
        }
        val repo = UsageRepository(MemoryStore(), DeviceAuth(noHttp), { error("Unexpected usage") })
        fun login() = LoginManager(repo, DeviceAuth(noHttp), ClaudeAuth(http) { clock }) { clock }
        login().begin(UsageProvider.CLAUDE)
        login().submitClaudeCode("code#${repo.readState().getJSONObject(LoginManager.PENDING).getString("id")}")
        assertEquals(LoginStep.Retry, login().step())
        assertEquals(LoginStep.Wait(120), login().step())
        assertEquals(1, exchanges)
        clock += 120
        assertEquals(LoginStep.Done, login().step())
        assertEquals(LoginPhase.SIGNED_IN, login().view().phase)
    }

    @Test fun `Claude token refresh is routed correctly persisted before usage and not diagnosed as partial`() = runTest {
        val store = MemoryStore()
        val http = HttpTransport { url, body, _, _ ->
            assertEquals(ClaudeAuth.TOKEN_URL, url)
            assertEquals("claude-refresh", JSONObject(body!!).getString("refresh_token"))
            tokens()
        }
        val repo = UsageRepository(store, DeviceAuth(http), { current -> object : UsageSource {
            override suspend fun read(now: Long): UsageSnapshot {
                assertEquals("new-refresh", Session.decode(store.read().getJSONObject("session")).refresh)
                assertEquals("new-access", current.access)
                assertEquals(UsageProvider.CLAUDE, current.provider)
                return UsageParser.claude(USAGE, now)
            }
        } }, { now })
        repo.signIn(session(now + 10))
        assertEquals(RefreshResult.SUCCESS, repo.refresh())
        assertEquals("usage_ok", store.read().getString("diagnostic"))
        repo.signOut(); assertEquals(0, store.read().length())
    }

    companion object {
        const val USAGE = """{"five_hour":{"utilization":27.6,"resets_at":"2026-11-13T12:00:00.000000+00:00"},"seven_day":{"utilization":78,"resets_at":"2026-11-19T12:00:00Z"},"seven_day_sonnet":{"utilization":5,"resets_at":null}}"""
    }
}
