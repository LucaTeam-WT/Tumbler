# Security Policy

## Reporting a vulnerability

Please **do not** open a public issue for security problems.
Use GitHub's private vulnerability reporting (Security → Report a vulnerability) or email the maintainer directly.

## Threat model (summary)

LucaAuth protects TOTP/HOTP secrets against:

- Other apps and casual access to app storage (secrets encrypted with Android Keystore keys)
- Shoulder surfing and screen capture (`FLAG_SECURE`, codes hidden by default)
- Clipboard snooping (auto-clear after copy)
- Unauthorized use of an unlocked phone (biometric / device credential app lock)

Out of scope: a rooted device with an active attacker, malware with accessibility/screen-reading privileges, physical extraction of an unlocked device.

## Design principles

- Offline by default; no network permission, no telemetry.
- Secrets never leave the device unencrypted. Backups are encrypted client-side with a user-supplied password.
- Secrets, codes and passwords are never logged.

## Known limitations

- Only the OTP secret is encrypted (AES-GCM with an Android Keystore key, bound to the account id). Issuer and account name, along with OTP type, algorithm, digits, period, counter and creation time, are stored in plaintext in the app-private Room database. Other apps cannot read it, and it is excluded from cloud backup and device-to-device transfer, but anyone who can read the app's private storage (for example on a rooted device) can see which accounts exist.
