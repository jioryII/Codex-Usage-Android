<div align="center">

# Codex Usage

Native Android widgets for your Codex plan limits.

[![Download APK](docs/assets/download.svg)](https://github.com/jioryII/Codex-Usage-Android/raw/refs/heads/main/downloads/codex-usage.apk)

**v1.3.0 · Android 8+ · No API key · No analytics**

</div>

![Square, Slim, Arc and Detail widgets](docs/assets/widgets.png)

*Illustrative usage data. Images rendered at 4× resolution.*

## Install

1. Download and install the APK above. Update over the previous version to keep your session.
2. Open **Codex Usage → Continue with ChatGPT → Copy code and open OpenAI**. Sign in, paste the code, authorize, then return.
3. Select **Add widget** and choose a layout. Remove and re-add an existing widget to apply the updated size limits.

English is the initial language. Spanish remains available through **EN/ES**.

## Layouts

| Widget | Content |
| --- | --- |
| **Square** | Usage bars, remaining percentages and reset countdowns. |
| **Slim** | Two usage bars and percentages, without countdowns. |
| **Arc** | Clock for 5 hours; calendar for weekly. Numeric values without `%`. |
| **Detail** | Usage plus saved resets and available expiry dates. |

All layouts share one dark finish. Remaining quota uses blue-to-purple at 75–100%, blue at 50–74%, orange at 25–49% and red below 25%. Icon-sized variants are removed; the launcher controls the final grid dimensions.

## Background updates

Updates target 15-minute intervals and skip routine requests while the phone is locked or idle. Repeated requests are grouped and server cooldowns are respected. Optional expiry reminders revalidate available resets; Android may delay delivery.

## About

Independent, unofficial app. Usage relies on an internal ChatGPT interface that may change. Credentials stay encrypted on the device. No automatic reset redemption. Live account access and Samsung/One UI behavior have not been verified in this development environment.

## Build

JDK 21, Android SDK 36 and Build Tools 36.0.0:

```sh
./gradlew assembleDebug
./gradlew testDebugUnitTest lintDebug assembleDebugAndroidTest
```

[Technical notes](docs/DEVELOPMENT.md) · [Validation](docs/VALIDATION.md) · [Third-party notices](THIRD_PARTY_NOTICES.md)
