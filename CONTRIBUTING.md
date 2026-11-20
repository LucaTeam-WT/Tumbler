# Contributing to Tumbler

## Workflow

1. `main` is always buildable and protected. **No direct pushes.**
2. Create a branch from `main`:
   - `feat/<short-name>` – new feature
   - `fix/<short-name>` – bug fix
   - `chore/<short-name>` – tooling, deps, CI
   - `docs/<short-name>` – documentation only
3. Open a Pull Request into `main`. Keep PRs small and focused (aim for < 400 changed lines).
4. CI must be green, and the PR checklist must be completed.
5. Merge with **Squash and merge**; the PR title becomes the commit message.

## Commit / PR title convention

[Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<scope>): <summary in imperative mood>
```

Types: `feat`, `fix`, `refactor`, `perf`, `test`, `docs`, `build`, `ci`, `chore`.
Scopes (examples): `totp`, `scanner`, `storage`, `lock`, `backup`, `ui`.

Examples:
- `feat(totp): add RFC 6238 generator`
- `fix(storage): migrate keystore alias on upgrade`

## Code rules

- Kotlin official style; no wildcard imports; no unused code.
- UI in Jetpack Compose + Material 3 only. State lives in ViewModels (`StateFlow`), composables stay stateless where possible.
- Every new piece of logic gets a unit test. TOTP/HOTP code must be tested against the official RFC test vectors.
- Run before pushing:
  ```bash
  ./gradlew lintDebug testDebugUnitTest assembleDebug
  ```

## Security rules (non-negotiable for an authenticator)

- Never log secrets, OTP codes, or backup passwords (no `Log.*`, no crash reports containing them).
- Never store secrets in plaintext, `SharedPreferences`, or app-visible files. Use Keystore-backed encryption.
- Do not add the `INTERNET` permission or any analytics/telemetry SDK without a separate, explicitly approved PR.
- No hard-coded keys, tokens, or keystore files in the repo (`*.jks`, `*.keystore`, `local.properties` are git-ignored).
- New dependencies must be justified in the PR description (why, size, maintenance status, license).

## Branch protection (repo settings)

Settings → Rules → Rulesets → New branch ruleset, target `main`:

- Require a pull request before merging
- Require status checks to pass: `build` (from `CI`)
- Require branches to be up to date before merging
- Block force pushes and deletions
- Require linear history
