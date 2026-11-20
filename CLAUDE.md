# CLAUDE.md

Guidance for AI coding assistants working in this repository.

## Project

LucaAuth – offline TOTP/HOTP authenticator for Android.
Package: `io.github.lucateam_wt.tumbler`. Kotlin + Jetpack Compose + Material 3, MVVM, Hilt, Room, DataStore, CameraX + ML Kit, `androidx.biometric`. minSdk 26.

## Commands

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew lintDebug
```

Run all three before declaring a task done.

## Rules

- Follow [CONTRIBUTING.md](CONTRIBUTING.md): branch naming, Conventional Commits, small PRs.
- Security rules in CONTRIBUTING.md are hard constraints: never log secrets/OTP codes, no plaintext secret storage, no `INTERNET` permission, no telemetry.
- OTP logic must have unit tests using RFC 4226 / RFC 6238 test vectors.
- UI: Material 3 components and theme tokens only; no hard-coded colors; support dark mode and dynamic color.
- Icons: do not add `material-icons-core` or `-extended`; add a small vector drawable `res/drawable/ic_*.xml` (Material Icons, Apache-2.0, `fillColor` `@android:color/white`, tinted by `Icon`). Decorative icons use `contentDescription = null`; icon-only buttons need a localized one that names the account where there are several.
- User-facing strings go in `res/values/strings.xml` (with Vietnamese in `values-vi/`).
- Prefer the Gradle version catalog (`gradle/libs.versions.toml`) for dependencies.
- Do not commit generated files, `local.properties`, keystores, or IDE settings.
- SECURITY.md describes only what the code does today. Whenever crypto, storage, Room schema, release build or manifest code changes, re-verify every security-relevant claim in SECURITY.md against the source in the same PR, and move items between "implemented" and "planned" accordingly.
- Backup format (`backup/`, specified in [docs/backup-format.md](docs/backup-format.md)): any change to the bytes written or accepted (header, KDF or its defaults and bounds, cipher, payload encoding, limits) bumps the format version (container) or payload version (payload) and adds a new golden file under `app/src/test/resources/backup/`, in the same PR as the matching update to docs/backup-format.md. Never edit or regenerate an existing golden file; its test must keep passing as long as the reader supports that version. The backup code is crypto code, so the SECURITY.md rule above applies to it too.
- Backup format v1 is not frozen until the first release that can write backups; from that release on, any change to the format must bump the format version, keep the golden-file test passing and update docs/backup-format.md in the same PR.
- Keep README.md in sync with the real state of the code. Any PR that adds, changes or removes a feature, dependency, command or folder updates README.md in the same PR: the Features table (✅ implemented and covered by tests, 🚧 partly done with what is missing, ⬜ not started), tech stack, build commands and doc links when they change. Verify by opening the source files, never from memory or from the plan; never mark something ✅ that is not in the code, and remove outdated claims. Update the "target layout" below when the package structure changes.

## Architecture (target layout)

```
io.github.lucateam_wt.tumbler
├── backup/    # encrypted backup file (docs/backup-format.md): scrypt (Bouncy Castle) + AES-256-GCM container, payload codec with limits, export password policy, import duplicate planning; pure JVM, not wired into the app yet
├── data/      # Room, Keystore crypto, repositories
│   ├── crypto/    # AES-GCM SecretCipher impl, Android Keystore key provider
│   ├── db/        # Room database, AccountEntity, AccountDao, summary projection
│   └── repository/ # RoomAccountRepository (encrypts secrets), entity/domain mappers
├── domain/    # OTP generators (TOTP/HOTP), models, use cases
│   ├── crypto/    # SecretCipher interface + exceptions
│   ├── model/     # OtpAccount, OtpType, AccountSummary
│   ├── otp/       # Base32, Hotp, Totp, OtpAuthUri parser
│   └── repository/ # AccountRepository interface
├── ui/        # Compose screens, components, theme
│   ├── accounts/  # compact-row accounts list (long-press menu; the next TOTP code shows in the last 10 s and is never stored; list item motion, empty/list crossfade, smooth countdown ring with pure helpers in RingMotion.kt), add screen, AccountListViewModel, UI state, clipboard copy
│   ├── theme/     # Material 3 theme (dynamic color, dark mode)
│   └── LucaAuthApp.kt  # root composable: switches list <-> add screen with AnimatedContent (slide + fade, no navigation library)
├── di/        # AppContainer (manual DI, lazy; Hilt is not used yet)
└── LucaAuthApplication.kt  # owns the AppContainer
```
