package dev.codex.widget.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class HttpFailure(val status: Int, val retryAt: Long? = null) : IOException("HTTP $status")

fun interface HttpTransport {
    suspend fun request(url: String, body: String?, contentType: String?, headers: Map<String, String>): String
}

class DirectHttp(private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
    .callTimeout(35, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()) : HttpTransport {
    override suspend fun request(url: String, body: String?, contentType: String?, headers: Map<String, String>): String =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).header("Accept", "application/json")
                .header("User-Agent", "CodexUsageWidget-Android/1.0 (unofficial)")
            headers.forEach { (key, value) -> request.header(key, value) }
            if (body != null) request.post(body.toRequestBody((contentType ?: "application/json").toMediaType()))
            client.newCall(request.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    val now = System.currentTimeMillis() / 1000
                    val retry = response.header("Retry-After")?.let { value ->
                        value.toLongOrNull()?.let { now + it.coerceIn(15, 86400) }
                            ?: runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                                .toEpochSecond().coerceIn(now + 15, now + 86400) }.getOrNull()
                    }
                    throw HttpFailure(response.code, retry)
                }
                val reader = response.body?.charStream() ?: throw IOException("Empty server response")
                reader.use {
                    val result = StringBuilder()
                    val buffer = CharArray(8192)
                    while (true) {
                        val count = it.read(buffer)
                        if (count < 0) break
                        if (result.length + count > 2_000_000) throw IOException("Unexpected response size")
                        result.append(buffer, 0, count)
                    }
                    result.toString()
                }
            }
        }
}

internal suspend fun HttpTransport.json(url: String, body: JSONObject): String =
    request(url, body.toString(), "application/json", emptyMap())
