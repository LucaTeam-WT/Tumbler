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
| Core | Add account via QR scanner | ⬜ |
| Core | Add account via manual secret entry | ⬜ |
| Security | Secrets encrypted with Android Keystore | ⬜ |
| Security | Biometric / device-credential app lock | ⬜ |
| Security | `FLAG_SECURE`, hidden codes by default, clipboard auto-clear | ⬜ |
| Backup | Encrypted export / import (AES-256-GCM + password KDF) | ⬜ |
| Backup | Import from Google Authenticator (`otpauth-migration://`) | ⬜ |
| UX | Material 3, dynamic color, dark mode, search, labels | 🚧 theme with dynamic color and dark mode only; no screens, search or labels |

## Tech stack

Current (in the build files today):

- Kotlin, Jetpack Compose (BOM), Material 3
- AndroidX `core-ktx`, `activity-compose`, `lifecycle-runtime-ktx`
- Tests: JUnit 4 (unit); AndroidX Test, Espresso, Compose UI test (instrumented)
- minSdk 26, targetSdk / compileSdk 37, Java 11

Planned (not added yet):

- MVVM + `StateFlow`, Hilt
- Room (+ Keystore-backed encryption), DataStore
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
