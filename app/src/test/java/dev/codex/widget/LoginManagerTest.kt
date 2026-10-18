package dev.codex.widget

import dev.codex.widget.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.Base64

class LoginManagerTest {
    private var clock = 1000L
    private fun tokens(): String {
        val claims = """{"exp":999999,"email":"person@example.test","https://api.openai.com/auth":{"chatgpt_account_id":"acct"}}"""
        val jwt = "e30." + Base64.getUrlEncoder().withoutPadding().encodeToString(claims.toByteArray()) + ".test"
        return JSONObject().put("access_token", jwt).put("id_token", jwt).put("refresh_token", "test-refresh").toString()
    }
    private fun repository(store: StateStore, auth: DeviceAuth) = UsageRepository(store, auth, { error("Usage not part of login tests") }, { clock })
    private fun manager(store: StateStore, http: HttpTransport): LoginManager {
        val auth = DeviceAuth(http)
        return LoginManager(repository(store, auth), auth) { clock }
    }
    private val device = """{"device_auth_id":"device-test","user_code":"TEST-CODE","interval":"5"}"""
    private val grant = """{"authorization_code":"test-grant","code_verifier":"test-verifier"}"""

    @Test fun `double taps request only one device code and reuse the existing flow`() = runTest {
        val store = MemoryStore(); var requests = 0
        val http = HttpTransport { url, _, _, _ -> assertTrue(url.endsWith("/usercode")); requests++; delay(10); device }
        val login = manager(store, http)
        listOf(async { login.begin() }, async { login.begin() }, async { login.begin() }).awaitAll()
        assertEquals(1, requests)
        assertEquals("TEST-CODE", login.view().code)
        assertEquals(LoginPhase.WAITING, login.view().phase)
    }

    @Test fun `new process restores the same code and completes without another login request`() = runTest {
        val store = MemoryStore(); var requests = 0; var polls = 0; var exchanges = 0
        val http = HttpTransport { url, _, _, _ -> when {
            url.endsWith("/usercode") -> { requests++; device }
            url.endsWith("/deviceauth/token") -> { polls++; grant }
            else -> {
                exchanges++
                assertTrue("one-time grant persisted before exchange", store.read().getJSONObject(LoginManager.PENDING).has("grant"))
                tokens()
            }
        } }
        manager(store, http).begin()
        val restored = manager(store, http)
        restored.recover(); restored.begin()
        assertEquals("TEST-CODE", restored.view().code)
        assertEquals(1900L, restored.view().expiresAt)
        clock += 5
        assertEquals(LoginStep.Done, restored.step())
        assertEquals(LoginPhase.SIGNED_IN, restored.view().phase)
        assertEquals("person@example.test", restored.view().email)
        assertFalse(store.read().has(LoginManager.PENDING))
        restored.begin()
        assertEquals(1, requests); assertEquals(1, polls); assertEquals(1, exchanges)
    }

    @Test fun `exchange connection failure retains grant across process death without consuming another code`() = runTest {
        val store = MemoryStore(); var polls = 0; var exchanges = 0
        val http = HttpTransport { url, _, _, _ -> when {
            url.endsWith("/usercode") -> device
            url.endsWith("/deviceauth/token") -> { polls++; grant }
            else -> { exchanges++; if (exchanges == 1) throw IOException("offline"); tokens() }
        } }
        val first = manager(store, http)
        first.begin(); clock += 5
        assertEquals(LoginStep.Retry, first.step())
        assertEquals(LoginPhase.EXCHANGING, first.view().phase)
        val restored = manager(store, http)
        restored.recover()
        assertEquals(LoginStep.Done, restored.step())
        assertEquals(LoginPhase.SIGNED_IN, restored.view().phase)
        assertEquals(1, polls); assertEquals(2, exchanges)
    }

    @Test fun `existing session blocks any new sign-in request`() = runTest {
        val store = MemoryStore()
        val http = HttpTransport { _, _, _, _ -> error("must never authenticate twice") }
        val auth = DeviceAuth(http); val repo = repository(store, auth)
        repo.signIn(Session("test", "test-refresh", "acct", 999999))
        val login = LoginManager(repo, auth) { clock }
        login.begin(); assertEquals(LoginStep.Done, login.step())
        assertEquals(LoginPhase.SIGNED_IN, login.view().phase)
    }

    @Test fun `pending authorization is retained on offline and respects rate limit retry time`() = runTest {
        val store = MemoryStore(); var polls = 0
        val http = HttpTransport { url, _, _, _ ->
            if (url.endsWith("/usercode")) device else { polls++; throw HttpFailure(429, clock + 30) }
        }
        val login = manager(store, http)
        login.begin(); clock += 5
        assertEquals(LoginStep.Retry, login.step())
        assertEquals(LoginStep.Wait(30), login.step())
        assertEquals(1, polls); assertEquals("TEST-CODE", login.view().code)
        assertEquals(LoginPhase.WAITING, login.view().phase)
    }

    @Test fun `expired code is visible as an error and recovery never extends its deadline`() = runTest {
        val store = MemoryStore()
        val http = HttpTransport { _, _, _, _ -> device }
        val login = manager(store, http); login.begin()
        clock += 901
        val restored = manager(store, http); restored.recover()
        assertEquals(LoginStep.Done, restored.step())
        assertEquals(LoginPhase.ERROR, restored.view().phase)
        assertFalse(store.read().has("session"))
        restored.begin()
        assertEquals(clock + 900, restored.view().expiresAt)
    }

    @Test fun `cancel removes encrypted pending credentials and no late poll can log in`() = runTest {
        val store = MemoryStore()
        val login = manager(store, HttpTransport { _, _, _, _ -> device })
        login.begin(); login.cancel(); clock += 5
        assertEquals(LoginStep.Done, login.step())
        assertEquals(LoginPhase.SIGNED_OUT, login.view().phase)
        assertFalse(store.read().has(LoginManager.PENDING))
    }

    @Test fun `logout during exchange is serialized and cannot resurrect a session`() = runTest {
        val store = MemoryStore(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val http = HttpTransport { url, _, _, _ -> when {
            url.endsWith("/usercode") -> device
            url.endsWith("/deviceauth/token") -> grant
            else -> { entered.complete(Unit); release.await(); tokens() }
        } }
        val login = manager(store, http); login.begin(); clock += 5
        val exchange = async { login.step() }; entered.await()
        val logout = async { login.signOut() }
        release.complete(Unit); exchange.await(); logout.await()
        assertEquals(LoginPhase.SIGNED_OUT, login.view().phase)
        assertEquals(0, store.read().length())
    }

    @Test fun `worker cancellation after authorization cannot discard issued tokens`() = runTest {
        val store = MemoryStore(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val http = HttpTransport { url, _, _, _ -> when {
            url.endsWith("/usercode") -> device
            url.endsWith("/deviceauth/token") -> grant
            else -> { entered.complete(Unit); release.await(); tokens() }
        } }
        val login = manager(store, http); login.begin(); clock += 5
        val worker = launch { login.step() }; entered.await()
        worker.cancel(); release.complete(Unit); worker.join()
        assertEquals(LoginPhase.SIGNED_IN, login.view().phase)
    }

    @Test fun `rejected exchange is not presented as an open session`() = runTest {
        val store = MemoryStore()
        val login = manager(store, HttpTransport { url, _, _, _ -> when {
            url.endsWith("/usercode") -> device
            url.endsWith("/deviceauth/token") -> grant
            else -> throw HttpFailure(400)
        } })
        login.begin(); clock += 5
        assertEquals(LoginStep.Done, login.step())
        assertEquals(LoginPhase.ERROR, login.view().phase)
        assertNull(login.view().code)
        assertFalse(store.read().has("session"))
    }
}
