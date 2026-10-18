package dev.codex.widget

import dev.codex.widget.data.*
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DeviceAuthTest {
    private fun jwt() = "e30." + Base64.getUrlEncoder().withoutPadding().encodeToString(
        """{"exp":999999,"https://api.openai.com/auth":{"chatgpt_account_id":"acct"}}""".toByteArray()) + ".test-only"

    @Test fun `device flow waits for user authorization and exchanges code with supplied verifier`() = runTest {
        var polls = 0
        var exchanges = 0
        val http = HttpTransport { url, body, type, _ ->
            when {
                url.endsWith("/usercode") -> {
                    assertEquals(DeviceAuth.CLIENT_ID, JSONObject(body!!).getString("client_id"))
                    """{"device_auth_id":"test-device","user_code":"TEST-CODE","interval":"5"}"""
                }
                url.endsWith("/deviceauth/token") -> {
                    polls++
                    if (polls == 1) throw HttpFailure(403)
                    assertEquals("test-device", JSONObject(body!!).getString("device_auth_id"))
                    """{"authorization_code":"test-code","code_verifier":"test-verifier"}"""
                }
                url.endsWith("/oauth/token") -> {
                    exchanges++
                    assertEquals("application/x-www-form-urlencoded", type)
                    assertTrue(body!!.contains("code_verifier=test-verifier"))
                    JSONObject().put("access_token", jwt()).put("refresh_token", "test-refresh").toString()
                }
                else -> error("Unexpected route")
            }
        }
        val auth = DeviceAuth(http)
        val code = auth.begin()
        assertEquals("TEST-CODE", code.code)
        val session = auth.await(code) { testScheduler.currentTime }
        assertEquals("acct", session.accountId); assertEquals(2, polls); assertEquals(1, exchanges)
        assertEquals(10_000L, testScheduler.currentTime)
    }

    @Test fun `device authorization expires without acquiring a session`() = runTest {
        var exchanges = 0
        val http = HttpTransport { url, _, _, _ ->
            if (url.endsWith("/oauth/token")) exchanges++
            throw HttpFailure(404)
        }
        try { DeviceAuth(http).await(DeviceCode("test", "TEST", 60)) { testScheduler.currentTime }; fail("Must expire") }
        catch (_: java.io.IOException) { }
        assertEquals(900_000L, testScheduler.currentTime)
        assertEquals(0, exchanges)
    }
}
