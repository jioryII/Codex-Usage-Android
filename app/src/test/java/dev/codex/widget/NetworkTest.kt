package dev.codex.widget

import dev.codex.widget.data.*
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class NetworkTest {
    @Test fun `network transports JSON and headers and honors retry after`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"ok\":true}"))
            val http = DirectHttp()
            assertEquals("{\"ok\":true}", http.request(server.url("/usage").toString(), null, null,
                mapOf("Authorization" to "Bearer test-only")))
            val request = server.takeRequest()
            assertEquals("GET", request.method); assertEquals("Bearer test-only", request.getHeader("Authorization"))
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "600"))
            val before = System.currentTimeMillis() / 1000
            try { http.request(server.url("/usage").toString(), null, null, emptyMap()); fail("Expected 429") }
            catch (error: HttpFailure) {
                assertEquals(429, error.status); assertTrue(error.retryAt!! in before + 600..before + 603)
            }
        }
    }
    @Test fun `redirects are not followed with account credentials`() = runTest {
        MockWebServer().use { first -> MockWebServer().use { other ->
            first.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/steal")))
            try { DirectHttp().request(first.url("/").toString(), null, null,
                mapOf("Authorization" to "Bearer test-only")); fail("Expected rejected redirect") }
            catch (error: HttpFailure) { assertEquals(302, error.status) }
            assertEquals(0, other.requestCount)
        } }
    }
}
