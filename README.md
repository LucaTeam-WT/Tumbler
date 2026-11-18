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
| Core | Accounts list: icon-only "+" add button (floating action button named "Add account" for TalkBack), compact rows with live codes and countdown, tap to copy, long-press menu (copy, next code, delete with confirmation), TalkBack custom actions; a TOTP row also shows the next code in the last 10 seconds of a step (half the period for periods of 20 s or less), computed on every tick and never stored, not copyable; the current code is always vertically centered and the next-code line animates in (fade, expand, slide up; 400 ms) and out (fade, shrink, slide up; 300 ms) and follows the system animation scale | 🚧 view model (including the next-code window) covered by JVM tests; the Compose UI tests (compact rows, long-press menu, next code) are written and compile but have not been run on a device yet |
| Core | Add account by pasting an `otpauth://` link | 🚧 view model (including storage errors and double submit) covered by JVM tests; the Compose UI tests are written but have not been run on a device yet |
| Core | Add account via QR scanner | ⬜ |
| Core | Add account via manual secret entry | ⬜ |
| Security | Secrets encrypted with Android Keystore | ✅ AES-GCM with a non-exportable Keystore key, bound to the account id; used by the account repository, which the app uses |
| Security | Biometric / device-credential app lock | ⬜ |
| Security | `FLAG_SECURE`, hidden codes by default, clipboard auto-clear | ⬜ later PR (copying a code marks the clip sensitive on Android 13+, but the clipboard is not cleared and codes are always visible) |
| Backup | Encrypted export / import (AES-256-GCM + password KDF) | ⬜ |
| Backup | Import from Google Authenticator (`otpauth-migration://`) | ⬜ |
| UX | Material 3, dynamic color, dark mode, search, labels | 🚧 accounts list and add screens with icons, dynamic color and dark mode; no search or labels |
| UX | Motion polish: rows animate on add, delete and reorder (`animateItem`, 200–300 ms); the accounts list and the add screen switch with a short horizontal slide and fade; the countdown ring sweeps continuously within each second and fades to the error color in the last 5 s; the empty state and the list crossfade. All follow the system animation scale (off means no motion) | 🚧 ring snap/sweep logic covered by JVM tests; the Compose UI tests for row removal and the empty/list switch are written and compile but have not been run on a device yet; the screen transition has no automated test |

## Tech stack

Current (in the build files today):

- Kotlin, Jetpack Compose (BOM), Material 3
- AndroidX `core-ktx`, `activity-compose`, `lifecycle-runtime-ktx`, `lifecycle-viewmodel-compose`
- Manual dependency injection: `AppContainer`, created lazily by `LucaAuthApplication`, so the database and Keystore are not opened at app start (no Hilt yet).
- MVVM: `AccountListViewModel` (`ViewModel` + `StateFlow`, obtained with `lifecycle-viewmodel-compose`) holds the decrypted accounts in memory and produces live TOTP/HOTP codes. Screens are switched with simple saved state in `LucaAuthApp`; there is no navigation library.
- Icons are seven small vector drawables in `res/drawable` (`ic_*.xml`, from Material Icons, Apache-2.0); there is no `material-icons` dependency
- `collectAsStateWithLifecycle` comes from `lifecycle-runtime-compose`, which is already on the classpath through the Compose and activity libraries (not declared directly)
- Room 2.8.5 (`room-runtime`, `room-ktx`, `room-compiler` via KSP 2.3.14). Schemas are exported to `app/schemas` and committed.
- Kotlin coroutines `Flow` (transitive through AndroidX, not declared directly)
- Tests: JUnit 4 and `kotlinx-coroutines-test` (unit); AndroidX Test, Espresso, Compose UI test, `room-testing` (instrumented). `MigrationTest` is a migration harness for the committed schemas; there is no migration yet (database version 1). It has not been run on a device yet.
- minSdk 26, targetSdk / compileSdk 37, Java 11

Planned (not added yet):

- Hilt, navigation library
- DataStore
- CameraX + ML Kit barcode scanning
- `androidx.biometric`

## Security

LucaAuth is **beta** and **not audited**: do not keep accounts in it whose loss would be
critical. Secrets are encrypted with a non-exportable Android Keystore key; the app has no
`INTERNET` permission and no telemetry. **Backups are disabled by design**
(`allowBackup="false"` plus data extraction rules that exclude cloud backup and device
transfer), so accounts do not move to a new phone and there is no export yet. See
[SECURITY.md](SECURITY.md) for the threat model, limitations and how to report a
vulnerability.

The release build uses R8 minification and resource shrinking
(`app/proguard-rules.pro`); no signing configuration is committed.

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
- [SECURITY.md](SECURITY.md) – threat model, known limitations and how to report vulnerabilities
- [CLAUDE.md](CLAUDE.md) – guidance for AI coding assistants working in this repo

## License

Not decided yet. Add a `LICENSE` file before accepting outside contributions.
