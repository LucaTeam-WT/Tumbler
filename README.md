# Tumbler: 2FA authenticator

Tumbler is a privacy-first, offline two-factor authentication (TOTP/HOTP) app for Android.

![CI](https://github.com/LucaTeam-WT/Tumbler/actions/workflows/ci.yml/badge.svg)


## Features

Status legend: ✅ implemented and covered by tests · 🚧 partly done · ⬜ not started

| Area | Feature | Status |
|---|---|---|
| Core | TOTP (RFC 6238), SHA-1/256/512, 6–8 digits | ✅ engine and the view model's live codes (countdown, period boundary) covered by JVM tests with RFC vectors |
| Core | HOTP (RFC 4226) | ✅ engine and "next code" covered by JVM tests with RFC vectors. "Next code" is one database transaction on the stored account: it makes the code for the stored counter and stores the next counter (only if the row is unchanged), so the code never comes from the list's cached copy and overlapping taps get consecutive counters; its device tests passed on one Android 17 phone. Known limit: an account whose counter is `Long.MAX_VALUE` (an `otpauth://` link can set it; importing it works) cannot make another code, because the counter cannot be advanced. Its row shows a short "counter limit reached" message instead of a code and the stored counter is not changed. The Compose test for that row passed on the same phone. A "next code" cut short when the screen's view model is cleared (for example the activity finishes) may have stored the next counter without showing its code, which is harmless for HOTP |
| Core | Parse `otpauth://` URIs (Key URI Format) | ✅ rejects an issuer or account name above 1024 UTF-8 bytes or with an unpaired surrogate and a secret above 1024 bytes (the backup's limits, so every added account can be exported); the add screen says when a link's label or secret is too long for a backup, and shows every other bad link as an invalid link |
| Core | Accounts stored locally (Room database, secret encrypted per account) | ✅ wired into the app through `AppContainer` |
| Core | Accounts list: icon-only "+" add button (floating action button named "Add account" for TalkBack), compact rows with live codes and countdown, tap to copy, long-press menu (copy, next code, delete with confirmation), TalkBack custom actions, an overflow menu (three dots) with Export backup and Import backup; a TOTP row also shows the next code in the last 10 seconds of a step (half the period for periods of 20 s or less), computed on every tick and never stored, not copyable; the current code is always vertically centered and the next-code line animates in (fade, expand, slide up; 400 ms) and out (fade, shrink, slide up; 300 ms) and follows the system animation scale | ✅ view model (including the next-code window) covered by JVM tests; the Compose UI tests (compact rows, long-press menu, next code) passed on one Android 17 phone |
| Core | Add account by pasting an `otpauth://` link | ✅ view model (including storage errors and double submit) covered by JVM tests; the Compose UI tests passed on one Android 17 phone |
| Core | Add account via QR scanner | ⬜ |
| Core | Add account via manual secret entry | ⬜ |
| Security | Secrets encrypted with Android Keystore | ✅ AES-GCM with a non-exportable Keystore key, bound to the account id; used by the account repository, which the app uses |
| Security | Envelope encryption (one Keystore-wrapped data key, secrets sealed in software; [docs/storage-format.md](docs/storage-format.md)) | 🚧 groundwork only: the data-key format, its key handling and the `data_keys` table exist, and both repositories can read rows in either format; covered by JVM tests (AES-256-GCM known answers, tampering, every key state, one shared unwrap, cancellation, wiping, the 1→2 migration SQL against the exported schema). Nothing writes the new format yet and no data key is created: every account is still written and stored in the Keystore format. Missing: converting existing rows (with progress), writing new rows in the new format, the "accounts unreadable" banner, and a device run of the migration tests |
| Security | Biometric / device-credential app lock | ⬜ |
| Security | `FLAG_SECURE`, hidden codes by default, clipboard auto-clear | ⬜ later PR (copying a code marks the clip sensitive on Android 13+, but the clipboard is not cleared and codes are always visible) |
| Backup | Encrypted export / import (AES-256-GCM + scrypt) | 🚧 in the accounts screen's menu (three dots). Export asks for a password twice (12 to 1024 characters) and saves one encrypted `.lucabkup` file where you choose (system file picker, no storage permission). Import opens such a file, shows how many accounts are new and each conflict with only the choices that are safe for it (Skip, Keep both, Replace; Skip is the default), and writes everything in one database transaction after a final confirmation. The format is specified in [docs/backup-format.md](docs/backup-format.md); the password is never stored, and a wrong password and a damaged file give the same message by design. The chosen file is opened for writing only once the encrypted backup is ready, so an export that fails before then leaves it unchanged; a write that fails halfway deletes the incomplete file. Covered by JVM tests: the format (RFC 7914 scrypt vectors, a golden file, tamper, truncation and bounded-read tests, seeded fuzz tests of the reader and the payload decoder), the service and its transaction with fakes, and the screens' view model (password rules, every result, partial-file deletion, the wiping of passwords and secrets on every path). The Compose UI tests and the device tests (round trips with Room and the Keystore at the default scrypt parameters, password normalization, 1000 accounts, "next code" during an import) passed on one Android 17 phone: after three test fixes and the following review fixes, the whole suite ran there with 111 tests and no failures. On that phone each Keystore operation takes about 0.33 s per account (StrongBox), so export and import take seconds per ten accounts (about 5.5 minutes each for 1000; measurements in docs/backup-format.md); the time to show the account list was not measured. Known limit: the 1000-account cap is checked only on export, so an import can take a device over 1000 accounts, after which every export fails with a clear message (nothing is lost). Missing: a device run of the test changed with the latest review fixes (the export warning), and an export/import round trip on a release build |
| Backup | Import from Google Authenticator (`otpauth-migration://`) | ⬜ |
| UX | Material 3, dynamic color, dark mode, search, labels | 🚧 accounts list and add screens with icons, dynamic color and dark mode; no search or labels |
| UX | Motion polish: rows animate on add, delete and reorder (`animateItem`, 200–300 ms); the accounts list and the add screen switch with a short horizontal slide and fade; the countdown ring sweeps continuously within each second and fades to the error color in the last 5 s; the empty state and the list crossfade. All follow the system animation scale (off means no motion) | 🚧 ring snap/sweep logic covered by JVM tests; the Compose UI tests for row removal and the empty/list switch passed on one Android 17 phone; the screen transition has no automated test |

## Tech stack

Current (in the build files today):

- Kotlin, Jetpack Compose (BOM), Material 3
- AndroidX `core-ktx`, `activity-compose`, `lifecycle-runtime-ktx`, `lifecycle-viewmodel-compose`
- Manual dependency injection: `AppContainer`, created lazily by `TumblerApplication`, so the database and Keystore are not opened at app start (no Hilt yet).
- MVVM: `AccountListViewModel` (`ViewModel` + `StateFlow`, obtained with `lifecycle-viewmodel-compose`) keeps each TOTP account's code generator (with its copy of the secret) in memory and produces live TOTP codes; HOTP rows keep no secret, and their codes come from the repository's "next code" transaction. `BackupViewModel` runs export and import as a sealed state machine and keeps the backup passwords in memory only (never in saved state). Screens are switched with simple saved state in `TumblerApp`; there is no navigation library.
- System file pickers through `activity-compose`'s `ActivityResultContracts.CreateDocument` and `OpenDocument` (Storage Access Framework, no storage permission)
- Icons are twelve small vector drawables in `res/drawable` (`ic_*.xml`, from Material Icons, Apache-2.0); there is no `material-icons` dependency
- `collectAsStateWithLifecycle` comes from `lifecycle-runtime-compose`, which is already on the classpath through the Compose and activity libraries (not declared directly)
- Room 2.8.5 (`room-runtime`, `room-ktx`, `room-compiler` via KSP 2.3.14). Schemas are exported to `app/schemas` and committed. The backup import writes in one `withTransaction` block, which Room 2.8.5 ships in `room-runtime` (its `room-runtime-android` artifact); `room-ktx` is still declared but in 2.8.5 contains no classes.
- Kotlin coroutines `Flow` (transitive through AndroidX, not declared directly)
- Bouncy Castle `bcprov-jdk18on` 1.87, used only for scrypt (`SCrypt.generate`) in the backup format; the Bouncy Castle JCA provider is not registered. R8 keeps only what scrypt reaches (19 Bouncy Castle classes in the release mapping, with `SCrypt` itself inlined into the app's code) and needs no keep rules for it
- Tests: JUnit 4 and `kotlinx-coroutines-test` (unit); AndroidX Test, Espresso, Compose UI test, `room-testing` (instrumented). The database is at version 2 with one migration (1→2, in `DatabaseMigrations`, no destructive fallback); `DatabaseMigrationsTest` checks its SQL against the committed schemas on the JVM, and `MigrationTest` migrates a version 1 file and validates it with Room on a device (written; not yet run on a device for version 2; the version 1 harness passed on one Android 17 phone).
- minSdk 26, targetSdk / compileSdk 37, Java 11

Planned (not added yet):

- Hilt, navigation library
- DataStore
- CameraX + ML Kit barcode scanning
- `androidx.biometric`

## Security

Tumbler is **beta** and **not audited**: do not keep accounts in it whose loss would be
critical. Secrets are encrypted with a non-exportable Android Keystore key; the app has no
`INTERNET` permission and no telemetry. **System backups are disabled by design**
(`allowBackup="false"` plus data extraction rules that exclude cloud backup and device
transfer), so accounts move to a new phone only through the app's encrypted export: one
file, protected by a password you choose (at least 12 characters; scrypt and AES-256-GCM),
that is only as strong as that password. The password is not stored and cannot be
recovered, and the file stays wherever you save it, so keeping it safe is up to you. See
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
- [docs/storage-format.md](docs/storage-format.md) – byte-level specification of how secrets are stored in the database (both formats, data keys, key states) and what is active today
- [docs/backup-format.md](docs/backup-format.md) – byte-level specification of the encrypted backup file, the export/import service and screens, and what is still to do
- [CLAUDE.md](CLAUDE.md) – guidance for AI coding assistants working in this repo

## License

Tumbler is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version (GPL-3.0-or-later). It is distributed without any warranty. See [LICENSE](LICENSE) for the full text.

Copyright (C) 2026 phucho0237
