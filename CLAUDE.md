# CLAUDE.md

Guidance for AI coding assistants working in this repository.

## Project

LucaAuth – offline TOTP/HOTP authenticator for Android.
Package: `com.phucho0237.lucaauth`. Kotlin + Jetpack Compose + Material 3, MVVM, Hilt, Room, DataStore, CameraX + ML Kit, `androidx.biometric`. minSdk 26.

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
- User-facing strings go in `res/values/strings.xml` (with Vietnamese in `values-vi/`).
- Prefer the Gradle version catalog (`gradle/libs.versions.toml`) for dependencies.
- Do not commit generated files, `local.properties`, keystores, or IDE settings.
- Keep README.md in sync with the real state of the code. Any PR that adds, changes or removes a feature, dependency, command or folder updates README.md in the same PR: the Features table (✅ implemented and covered by tests, 🚧 partly done with what is missing, ⬜ not started), tech stack, build commands and doc links when they change. Verify by opening the source files, never from memory or from the plan; never mark something ✅ that is not in the code, and remove outdated claims. Update the "target layout" below when the package structure changes.

## Architecture (target layout)

```
com.phucho0237.lucaauth
├── data/      # Room, Keystore crypto, backup import/export, repositories
│   ├── crypto/    # AES-GCM SecretCipher impl, Android Keystore key provider
│   ├── db/        # Room database, AccountEntity, AccountDao, summary projection
│   └── repository/ # RoomAccountRepository (encrypts secrets), entity/domain mappers
├── domain/    # OTP generators (TOTP/HOTP), models, use cases
│   ├── crypto/    # SecretCipher interface + exceptions
│   ├── model/     # OtpAccount, OtpType, AccountSummary
│   ├── otp/       # Base32, Hotp, Totp, OtpAuthUri parser
│   └── repository/ # AccountRepository interface
├── ui/        # Compose screens, components, theme
│   ├── accounts/  # accounts list + add screens, AccountListViewModel, UI state, clipboard copy
│   ├── theme/     # Material 3 theme (dynamic color, dark mode)
│   └── LucaAuthApp.kt  # root composable: switches list <-> add screen (no navigation library)
├── di/        # AppContainer (manual DI, lazy; Hilt is not used yet)
└── LucaAuthApplication.kt  # owns the AppContainer
```
