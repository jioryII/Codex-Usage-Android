# Development

Kotlin, RemoteViews/Canvas, Coroutines, WorkManager and OkHttp. Min SDK 26, target SDK 36, Gradle 8.13, AGP 8.11.1, Kotlin 2.1.21. Use a full JDK 21.

## Data and authorization

The Android source uses `GET https://chatgpt.com/backend-api/wham/usage` and optional `rate-limit-reset-credits` details with an OAuth bearer and account ID. This is an **unofficial, unstable internal interface**, not an API Platform quota endpoint. Windows are selected by duration: 300 and 10,080 minutes. Remaining quota is `100 − round(usedPercent)`. Unknown or expired windows never imply 100% available.

`AppServerUsageSource` implements the official Codex JSON-RPC `account/rateLimits/read` contract, including optional `rateLimitResetCredits` details, with an injectable transport. It is not connected to a publicly available standalone Android runtime. The app does not need a running PC or paid backend.

Authorization follows the public Codex CLI device flow using its public client ID, an external browser and PKCE. This is not a separately approved third-party OAuth registration; OpenAI may reject independent clients. Google/Apple/email are selected on OpenAI, never handled as native replacement credentials. No API keys, WebViews, cookie extraction or password collection.

Tokens, pending authorization, cache and reminder deduplication are encrypted with Android Keystore AES-GCM in no-backup storage. Pending codes last 15 minutes. WorkManager continues authorization outside the Activity; received grants and rotating refresh tokens are saved before further requests. Logout removes local account data. Language is a separate, non-sensitive preference.

References: [account contract](https://github.com/openai/codex/blob/1cc7dc8f2acc63ce6c1cb72271e6b90a76136c2b/codex-rs/app-server-protocol/src/protocol/v2/account.rs), [backend client](https://github.com/openai/codex/blob/1cc7dc8f2acc63ce6c1cb72271e6b90a76136c2b/codex-rs/backend-client/src/client/rate_limit_resets.rs), [device authorization](https://github.com/openai/codex/blob/1cc7dc8f2acc63ce6c1cb72271e6b90a76136c2b/codex-rs/login/src/device_code_auth.rs).

## Scheduling

Periodic work targets 15 minutes. Routine HTTP is skipped when locked, idle or without a widget/visible app. Unlock events request a refresh while the process survives; otherwise Android schedules the next opportunity. Explicit requests have a persistent 60-second minimum; automatic attempts have a 5-minute minimum. HTTP 429 respects `Retry-After`, with a 15-minute fallback. Other failures use persistent backoff; rejected clients pause for an hour.

Opted-in saved-reset reminders revalidate the server even with the screen off. Only identified, available units with reliable expiry dates qualify. Eligibility starts seven days before expiry, followed by one daily slot anchored to expiry. Persistent per-unit high-water marks prevent replay after clock changes/restarts. Consumption or expiry cancels notices after a fresh read. Offline state cannot prove consumption. Android delivery and notification/state persistence are not an atomic transaction, so exact timing or exactly-once delivery across every process failure is not promised.

There are no exact alarms, permanent foreground services, analytics or redemption calls. Notification permission is requested only when enabling reminders on Android 13+.

## Rendering and releases

Square, Slim, Arc and Detail share a background and quota colors. Arc uses clock/calendar icons and bare numeric values; TalkBack retains descriptive percentage semantics. Resizing does not convert cards into an icon-sized Arc. Provider IDs stay compatible with existing installations.

Production responsive bitmaps are bounded to 512 pixels per edge. Offline documentation exports use a separate 4× scale; README images are not enlarged low-resolution screenshots. Picker previews contain unknown values, not synthetic quotas.

The downloadable APK is a signed debug build. Preserve its signing key to update existing installations. CI runners generate their own debug key; their APKs may not update over the downloadable build. No private key is committed.

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
# Real phone/emulator required for Keystore instrumentation:
./gradlew connectedDebugAndroidTest
```
