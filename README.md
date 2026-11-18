# LucaAuth

A privacy-first, offline two-factor authentication (TOTP/HOTP) app for Android.

![CI](https://github.com/phucho0237/LucaAuth/actions/workflows/ci.yml/badge.svg)


## Features

Status legend: ✅ implemented and covered by tests · 🚧 partly done · ⬜ not started

| Area | Feature | Status |
|---|---|---|
| Core | TOTP (RFC 6238), SHA-1/256/512, 6–8 digits | ✅ engine and the view model's live codes (countdown, period boundary) covered by JVM tests with RFC vectors |
| Core | HOTP (RFC 4226) | ✅ engine and the view model's "next code" (stores the next counter) covered by JVM tests with RFC vectors |
| Core | Parse `otpauth://` URIs (Key URI Format) | ✅ |
| Core | Accounts stored locally (Room database, secret encrypted per account) | ✅ wired into the app through `AppContainer` |
| Core | Accounts list with live codes, tap to copy, delete with confirmation | 🚧 view model covered by JVM tests; the Compose UI tests are written but have not been run on a device yet |
| Core | Add account by pasting an `otpauth://` link | 🚧 view model (including storage errors and double submit) covered by JVM tests; the Compose UI tests are written but have not been run on a device yet |
| Core | Add account via QR scanner | ⬜ |
| Core | Add account via manual secret entry | ⬜ |
| Security | Secrets encrypted with Android Keystore | ✅ AES-GCM with a non-exportable Keystore key, bound to the account id; used by the account repository, which the app uses |
| Security | Biometric / device-credential app lock | ⬜ |
| Security | `FLAG_SECURE`, hidden codes by default, clipboard auto-clear | ⬜ later PR (copying a code marks the clip sensitive on Android 13+, but the clipboard is not cleared and codes are always visible) |
| Backup | Encrypted export / import (AES-256-GCM + password KDF) | ⬜ |
| Backup | Import from Google Authenticator (`otpauth-migration://`) | ⬜ |
| UX | Material 3, dynamic color, dark mode, search, labels | 🚧 accounts list and add screens with dynamic color and dark mode; no search or labels |

## Tech stack

Current (in the build files today):

- Kotlin, Jetpack Compose (BOM), Material 3
- AndroidX `core-ktx`, `activity-compose`, `lifecycle-runtime-ktx`, `lifecycle-viewmodel-compose`
- Manual dependency injection: `AppContainer`, created lazily by `LucaAuthApplication`, so the database and Keystore are not opened at app start (no Hilt yet).
- MVVM: `AccountListViewModel` (`ViewModel` + `StateFlow`, obtained with `lifecycle-viewmodel-compose`) holds the decrypted accounts in memory and produces live TOTP/HOTP codes. Screens are switched with simple saved state in `LucaAuthApp`; there is no navigation library.
- `collectAsStateWithLifecycle` comes from `lifecycle-runtime-compose`, which is already on the classpath through the Compose and activity libraries (not declared directly)
- Room 2.8.5 (`room-runtime`, `room-ktx`, `room-compiler` via KSP 2.3.14). Schemas are exported to `app/schemas` and committed.
- Kotlin coroutines `Flow` (transitive through AndroidX, not declared directly)
- Tests: JUnit 4 and `kotlinx-coroutines-test` (unit); AndroidX Test, Espresso, Compose UI test (instrumented)
- minSdk 26, targetSdk / compileSdk 37, Java 11

Planned (not added yet):

- Hilt, navigation library
- DataStore
- CameraX + ML Kit barcode scanning
- `androidx.biometric`

## Getting started

Requirements: Android Studio (stable), JDK 21 (bundled JBR is fine), Android SDK with the latest platform.

```bash
git clone https://github.com/phucho0237/LucaAuth.git
cd LucaAuth
./gradlew assembleDebug      # build
./gradlew testDebugUnitTest  # unit tests
./gradlew lintDebug          # Android lint
```

Or open the folder in Android Studio and press Run.

## Project docs

- [CONTRIBUTING.md](CONTRIBUTING.md) – branching, commits, PR rules
- [SECURITY.md](SECURITY.md) – threat model notes and how to report vulnerabilities
- [CLAUDE.md](CLAUDE.md) – guidance for AI coding assistants working in this repo

## License

Not decided yet. Add a `LICENSE` file before accepting outside contributions.
