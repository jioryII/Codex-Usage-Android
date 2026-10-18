# Validation — 1.3.0

## Executed

`./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` — **BUILD SUCCESSFUL**.

- **62 JVM/Robolectric tests passed, zero failures, zero skipped**: quota parsing, countdowns, cache, HTTP, token refresh, persistent authorization, Activity recreation, notification permission/deduplication, translations, scheduling and idle-aware workers.
- Fresh installs select English even on a Spanish device. An explicit Spanish preference is retained.
- Provider minimums no longer advertise icon-sized widgets. Resizing does not turn Square/Slim/Detail into Arc.
- Square, Slim, Arc and Detail inflate and render in both languages. Unknown/expired quotas remain unknown. Color boundaries include orange for 25–49% remaining.
- Native 4× documentation exports; production bitmaps remain limited to 512 pixels per edge. Picker previews use placeholders.
- Valid APK signature, matching the previous downloadable version for in-place updates.
- Lint: zero errors, 35 warnings; details remain in the generated report.

Toolchain: full JDK 21, Gradle 8.13, SDK 36, Build Tools 36.0.0. Build proxy settings are not included in the app.

## Not verified here

Real OpenAI account authorization/usage, physical Samsung/One UI sizing, Doze/OEM behavior, restart/force-stop and real Android Keystore instrumentation. The instrumentation APK is compiled, not declared executed. Synthetic screenshots and green session fixtures do not prove live account access.

Background work and unlock refreshes are best effort. Exact delivery is not promised. Internal ChatGPT endpoints may change or reject independent clients. A pending code can still expire or require a new attempt after an unrecoverable remote/process interruption.
