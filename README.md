# LucaAuth

A privacy-first, offline two-factor authentication (TOTP/HOTP) app for Android.

![CI](https://github.com/phucho0237/LucaAuth/actions/workflows/ci.yml/badge.svg)


## Features

Status legend: ✅ done · 🚧 in progress · ⬜ planned

| Area | Feature | Status |
|---|---|---|
| Core | TOTP (RFC 6238), SHA-1/256/512, 6–8 digits | ⬜ |
| Core | HOTP (RFC 4226) | ⬜ |
| Core | Add account via QR (`otpauth://`) or manual secret | ⬜ |
| Security | Secrets encrypted with Android Keystore | ⬜ |
| Security | Biometric / device-credential app lock | ⬜ |
| Security | `FLAG_SECURE`, hidden codes by default, clipboard auto-clear | ⬜ |
| Backup | Encrypted export / import (AES-256-GCM + password KDF) | ⬜ |
| Backup | Import from Google Authenticator (`otpauth-migration://`) | ⬜ |
| UX | Material 3, dynamic color, dark mode, search, labels | ⬜ |

## Tech stack

- Kotlin, Jetpack Compose, Material 3
- MVVM + `StateFlow`, Hilt
- Room (+ Keystore-backed encryption), DataStore
- CameraX + ML Kit barcode scanning
- `androidx.biometric`
- minSdk 26

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
