# Validation — 1.4.0 / Claude compatibility

## Executed for this change

- `assembleDebug assembleDebugAndroidTest` — **BUILD SUCCESSFUL**; app and instrumentation APKs compiled.
- **53 JVM tests passed, zero failures/errors/skips**, selecting `UsageTest`, `LoginManagerTest`, `RepositoryIntegrationTest`, `DeviceAuthTest`, `ClaudeTest`, `NetworkTest`, `ReminderPolicyTest` and `PresentationTest`.
- Claude coverage includes overall window parsing, missing/expired values, legacy storage compatibility, orange color policy, provider-specific headers, random state/S256 PKCE, authorization restoration, code validation, cancellation, expiry, HTTP 429 exchange cooldown, token rotation and provider persistence.
- `lintDebug` — zero errors, 36 warnings. `git diff --check` passed.

The environment blocks `gcp.mirrors.maven.org`, which Maven redirects to for `org.robolectric:nativeruntime-dist-compat:1.0.16`. The full test command was attempted but could not resolve that dependency. For the JVM-only run and lint, an external temporary Gradle init script excluded that native runtime from `debugUnitTestRuntimeClasspath`; no project dependency was changed. All test sources compile, but Robolectric/visual tests, including the new Claude UI flow and orange bitmap assertions, were **not executed**. Re-run the complete command below once the mirror is accessible:

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
```

Live Claude authorization, acceptance of the profile-only scope, actual account usage, physical-device rendering and Android Keystore instrumentation remain unverified. The new 1.4.0 APK is distributed as `downloads/codex-usage-claude.apk`; `downloads/codex-usage.apk` retains 1.3.1. Both APKs are signed, but their signing certificates differ, so 1.4.0 cannot update an installation of the old downloadable APK in place.

# Previous release validation — 1.3.1

## Executed

`./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` — **BUILD SUCCESSFUL**.

- **62 JVM/Robolectric tests passed, zero failures, zero skipped**: quota parsing, countdowns, cache, HTTP, token refresh, persistent authorization, Activity recreation, notification permission/deduplication, translations, scheduling and idle-aware workers.
- Fresh installs select English even on a Spanish device. An explicit Spanish preference is retained.
- Provider minimums no longer advertise icon-sized widgets. Resizing does not turn Square/Slim/Detail into Arc.
- Square, Slim, Arc and Detail inflate and render in both languages. Unknown/expired quotas remain unknown. Color boundaries include orange for 25–49% remaining.
- Detail stays square inside tall host cells; Arc is slightly smaller with 50%-height capsule corners.
- Native 4× documentation exports; production bitmaps remain limited to 512 pixels per edge. Picker previews use placeholders.
- Valid APK signature, matching the previous downloadable version for in-place updates.
- Lint: zero errors, 35 warnings; details remain in the generated report.

Toolchain: full JDK 21, Gradle 8.13, SDK 36, Build Tools 36.0.0. Build proxy settings are not included in the app.

## Not verified here

Real OpenAI account authorization/usage, physical Samsung/One UI sizing, Doze/OEM behavior, restart/force-stop and real Android Keystore instrumentation. The instrumentation APK is compiled, not declared executed. Synthetic screenshots and green session fixtures do not prove live account access.

Background work and unlock refreshes are best effort. Exact delivery is not promised. Internal ChatGPT endpoints may change or reject independent clients. A pending code can still expire or require a new attempt after an unrecoverable remote/process interruption.
