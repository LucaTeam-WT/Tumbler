# LucaAuth

A privacy-first, offline two-factor authentication (TOTP/HOTP) app for Android.

![CI](https://github.com/phucho0237/LucaAuth/actions/workflows/ci.yml/badge.svg)


## Features

Status legend: ✅ implemented and covered by tests · 🚧 partly done · ⬜ not started

| Area | Feature | Status |
|---|---|---|
| Core | TOTP (RFC 6238), SHA-1/256/512, 6–8 digits | ✅ engine only, no UI yet |
| Core | HOTP (RFC 4226) | ✅ engine only, no UI yet |
| Core | Parse `otpauth://` URIs (Key URI Format) | ✅ |
| Core | Accounts stored locally (Room database, secret encrypted per account) | ✅ storage layer only; not wired into the app yet, no UI |
| Core | Add account via QR scanner | ⬜ |
| Core | Add account via manual secret entry | ⬜ |
| Security | Secrets encrypted with Android Keystore | ✅ AES-GCM with a non-exportable Keystore key, bound to the account id; used by the account repository (not wired into the app yet) |
| Security | Biometric / device-credential app lock | ⬜ |
| Security | `FLAG_SECURE`, hidden codes by default, clipboard auto-clear | ⬜ |
| Backup | Encrypted export / import (AES-256-GCM + password KDF) | ⬜ |
| Backup | Import from Google Authenticator (`otpauth-migration://`) | ⬜ |
| UX | Material 3, dynamic color, dark mode, search, labels | 🚧 theme with dynamic color and dark mode only; no screens, search or labels |

## Tech stack

Current (in the build files today):

- Kotlin, Jetpack Compose (BOM), Material 3
- AndroidX `core-ktx`, `activity-compose`, `lifecycle-runtime-ktx`, `lifecycle-viewmodel-compose`
- Manual dependency injection: `AppContainer`, created lazily by `LucaAuthApplication`, so the database and Keystore are not opened at app start (no Hilt yet). `lifecycle-viewmodel-compose` is declared but not used yet.
- MVVM: `AccountListViewModel` (`ViewModel` + `StateFlow`) holds the decrypted accounts in memory and produces live TOTP/HOTP codes; no screen uses it yet
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
