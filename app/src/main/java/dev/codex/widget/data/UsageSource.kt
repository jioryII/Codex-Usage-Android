package dev.codex.widget.data

import dev.codex.widget.domain.UsageParser
import dev.codex.widget.domain.UsageSnapshot

interface UsageSource { suspend fun read(now: Long): UsageSnapshot }

/** Local Codex process protocol, not a public web endpoint. No remote server is bundled. */
fun interface JsonRpcTransport { suspend fun request(method: String, params: String): String }
class AppServerUsageSource(private val transport: JsonRpcTransport) : UsageSource {
    override suspend fun read(now: Long): UsageSnapshot = UsageParser.appServer(
        transport.request("account/rateLimits/read", "{\"excludeResetCreditDetails\":false}"), now)
}
