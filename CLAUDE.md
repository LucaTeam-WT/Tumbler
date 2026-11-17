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

## Architecture (target layout)

```
com.phucho0237.lucaauth
├── data/      # Room, Keystore crypto, backup import/export, repositories
├── domain/    # OTP generators (TOTP/HOTP), models, use cases
├── ui/        # Compose screens, components, theme, navigation
└── di/        # Hilt modules
```
