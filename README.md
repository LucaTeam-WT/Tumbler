# Tumbler: 2FA authenticator

Tumbler is a privacy-first, offline two-factor authentication (TOTP/HOTP) app for Android.

![CI](https://github.com/LucaTeam-WT/Tumbler/actions/workflows/ci.yml/badge.svg)


## Features

Status legend: ✅ implemented and covered by tests · 🚧 partly done · ⬜ not started

| Area | Feature | Status |
|---|---|---|
| Core | TOTP (RFC 6238), SHA-1/256/512, 6–8 digits | ✅ engine and the view model's live codes (countdown, period boundary) covered by JVM tests with RFC vectors |
| Core | HOTP (RFC 4226) | ✅ engine and "next code" covered by JVM tests with RFC vectors. "Next code" is one database transaction on the stored account: it makes the code for the stored counter and stores the next counter (only if the row is unchanged), so the code never comes from the list's cached copy and overlapping taps get consecutive counters; its device tests are written and compile but have not been run yet. Known limit: an account whose counter is `Long.MAX_VALUE` (an `otpauth://` link can set it; importing it works) cannot make another code, because the counter cannot be advanced. Its row shows a short "counter limit reached" message instead of a code and the stored counter is not changed. The Compose test for that row is written and compiles but has not been run on a device yet |
| Core | Parse `otpauth://` URIs (Key URI Format) | ✅ rejects an issuer or account name above 1024 UTF-8 bytes or with an unpaired surrogate and a secret above 1024 bytes (the backup's limits, so every added account can be exported); the add screen shows such a link as an invalid link |
| Core | Accounts stored locally (Room database, secret encrypted per account) | ✅ wired into the app through `AppContainer` |
| Core | Accounts list: icon-only "+" add button (floating action button named "Add account" for TalkBack), compact rows with live codes and countdown, tap to copy, long-press menu (copy, next code, delete with confirmation), TalkBack custom actions; a TOTP row also shows the next code in the last 10 seconds of a step (half the period for periods of 20 s or less), computed on every tick and never stored, not copyable; the current code is always vertically centered and the next-code line animates in (fade, expand, slide up; 400 ms) and out (fade, shrink, slide up; 300 ms) and follows the system animation scale | 🚧 view model (including the next-code window) covered by JVM tests; the Compose UI tests (compact rows, long-press menu, next code) are written and compile but have not been run on a device yet |
| Core | Add account by pasting an `otpauth://` link | 🚧 view model (including storage errors and double submit) covered by JVM tests; the Compose UI tests are written but have not been run on a device yet |
| Core | Add account via QR scanner | ⬜ |
| Core | Add account via manual secret entry | ⬜ |
| Security | Secrets encrypted with Android Keystore | ✅ AES-GCM with a non-exportable Keystore key, bound to the account id; used by the account repository, which the app uses |
| Security | Biometric / device-credential app lock | ⬜ |
| Security | `FLAG_SECURE`, hidden codes by default, clipboard auto-clear | ⬜ later PR (copying a code marks the clip sensitive on Android 13+, but the clipboard is not cleared and codes are always visible) |
| Backup | Encrypted export / import (AES-256-GCM + scrypt) | 🚧 not usable yet: no screen calls it. In `backup/`: the file format ([docs/backup-format.md](docs/backup-format.md)), its encryption, a strict reader (requests at most 4 MiB + 1 bytes from a stream, caps scrypt at 64 MiB, returns a closed result for every input), the export (names the first account that blocks it and why), the password policy (12 to 1024 characters, entered twice), the duplicate planning for import, and `BackupService`, which connects them to the stored accounts. Export decrypts every account and writes the file to a stream. Import reads a file from a stream, plans it against the accounts on the device and writes the user's decisions in one database transaction; inside that transaction it plans again and writes nothing if the accounts changed in a way that changes the plan. Import planning offers Skip / Keep both / Replace per conflict; an imported HOTP account is matched first to an existing HOTP account with the same secret (preferring the one with the same label), and Keep both is not offered for that pair; on Replace, two HOTP accounts with the same secret keep the larger counter, decided against the counter stored when the write happens, not when the plan was made. Exception: two HOTP accounts with one secret inside the same backup are both imported, as on the device that wrote it. Every outcome (wrong password or damaged file, not enough memory, an unreadable account, database and stream errors) is a typed result, and passwords and decrypted secrets are wiped after use (best-effort). Covered by JVM tests (RFC 7914 scrypt vectors, a golden file, tamper, truncation and bounded-read tests, seeded fuzz tests of the reader and the payload decoder, the service and its transaction with fakes, one of which runs the transaction on a thread of its own as Room does). Device tests (round trip, conflicts, the abort path, a cancellation inside the import's last insert, Conscrypt with damaged files, real Room transactions) are written and compile but have not been run on a device yet. Missing: export and import screens, file picker, and a round trip on a release build |
| Backup | Import from Google Authenticator (`otpauth-migration://`) | ⬜ |
| UX | Material 3, dynamic color, dark mode, search, labels | 🚧 accounts list and add screens with icons, dynamic color and dark mode; no search or labels |
| UX | Motion polish: rows animate on add, delete and reorder (`animateItem`, 200–300 ms); the accounts list and the add screen switch with a short horizontal slide and fade; the countdown ring sweeps continuously within each second and fades to the error color in the last 5 s; the empty state and the list crossfade. All follow the system animation scale (off means no motion) | 🚧 ring snap/sweep logic covered by JVM tests; the Compose UI tests for row removal and the empty/list switch are written and compile but have not been run on a device yet; the screen transition has no automated test |

## Tech stack

Current (in the build files today):

- Kotlin, Jetpack Compose (BOM), Material 3
- AndroidX `core-ktx`, `activity-compose`, `lifecycle-runtime-ktx`, `lifecycle-viewmodel-compose`
- Manual dependency injection: `AppContainer`, created lazily by `TumblerApplication`, so the database and Keystore are not opened at app start (no Hilt yet).
- MVVM: `AccountListViewModel` (`ViewModel` + `StateFlow`, obtained with `lifecycle-viewmodel-compose`) holds the decrypted accounts in memory and produces live TOTP/HOTP codes. Screens are switched with simple saved state in `TumblerApp`; there is no navigation library.
- Icons are seven small vector drawables in `res/drawable` (`ic_*.xml`, from Material Icons, Apache-2.0); there is no `material-icons` dependency
- `collectAsStateWithLifecycle` comes from `lifecycle-runtime-compose`, which is already on the classpath through the Compose and activity libraries (not declared directly)
- Room 2.8.5 (`room-runtime`, `room-ktx`, `room-compiler` via KSP 2.3.14). Schemas are exported to `app/schemas` and committed. The backup import writes in one `withTransaction` block, which Room 2.8.5 ships in `room-runtime` (its `room-runtime-android` artifact); `room-ktx` is still declared but in 2.8.5 contains no classes.
- Kotlin coroutines `Flow` (transitive through AndroidX, not declared directly)
- Bouncy Castle `bcprov-jdk18on` 1.87, used only for scrypt (`SCrypt.generate`) in the backup format; the Bouncy Castle JCA provider is not registered. No screen calls the backup service yet (`AppContainer.backupService` is unused), so R8 removes all Bouncy Castle classes and the backup code from release builds; only two of its Java resource files and its license are packaged (about 16 KB)
- Tests: JUnit 4 and `kotlinx-coroutines-test` (unit); AndroidX Test, Espresso, Compose UI test, `room-testing` (instrumented). `MigrationTest` is a migration harness for the committed schemas; there is no migration yet (database version 1). It has not been run on a device yet.
- minSdk 26, targetSdk / compileSdk 37, Java 11

Planned (not added yet):

- Hilt, navigation library
- DataStore
- CameraX + ML Kit barcode scanning
- `androidx.biometric`

## Security

Tumbler is **beta** and **not audited**: do not keep accounts in it whose loss would be
critical. Secrets are encrypted with a non-exportable Android Keystore key; the app has no
`INTERNET` permission and no telemetry. **Backups are disabled by design**
(`allowBackup="false"` plus data extraction rules that exclude cloud backup and device
transfer), so accounts do not move to a new phone and there is no export yet (the
encrypted backup format and an export/import service exist in code, but no screen calls
them, so they are not reachable from the app). See
[SECURITY.md](SECURITY.md) for the threat model, limitations and how to report a
vulnerability.

CodeQL analysis runs on pushes to `main`, on pull requests and weekly.

The release build uses R8 minification and resource shrinking
(`app/proguard-rules.pro`); no signing configuration is committed.

## Getting started

Requirements: Android Studio (stable), JDK 21 (bundled JBR is fine), Android SDK with the latest platform.

```bash
git clone https://github.com/LucaTeam-WT/Tumbler.git
cd Tumbler
./gradlew assembleDebug      # build
./gradlew testDebugUnitTest  # unit tests
./gradlew lintDebug          # Android lint
```

Or open the folder in Android Studio and press Run.

> **Note:** the application id changed from `com.phucho0237.lucaauth` to `io.github.lucateam_wt.tumbler` before the first release. An older LucaAuth install is a different app and must be uninstalled by hand; it shares no data with Tumbler.

## Project docs

- [CONTRIBUTING.md](CONTRIBUTING.md) – branching, commits, PR rules
- [SECURITY.md](SECURITY.md) – threat model, known limitations and how to report vulnerabilities
- [docs/backup-format.md](docs/backup-format.md) – byte-level specification of the encrypted backup file, the export/import service, and what the screens still need (not reachable from the app yet)
- [CLAUDE.md](CLAUDE.md) – guidance for AI coding assistants working in this repo

## License

Tumbler is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version (GPL-3.0-or-later). It is distributed without any warranty. See [LICENSE](LICENSE) for the full text.

Copyright (C) 2026 phucho0237
