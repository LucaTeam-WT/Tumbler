# LucaAuth backup file format

**Status:** format version 1. Implemented as library code in
`app/src/main/java/com/phucho0237/lucaauth/backup/` and covered by JVM unit tests.
**Not yet wired into the app:** there is no export or import screen, and nothing in the
app calls this code yet.

This document is normative: it should be precise enough to write an independent reader.
The unit tests include one (`SpecCodec` in
`app/src/test/java/com/phucho0237/lucaauth/backup/BackupFixtures.kt`) that shares no code
with the production codec and reads the committed golden file.

## Overview

A backup file holds every account (secret, labels, OTP parameters, HOTP counter,
creation time) encrypted with a key derived from a password the user chooses:

```
file = header || AES-256-GCM(key, nonce, aad = header, payload) || tag
key  = scrypt(UTF-8(NFC(password)), salt, N, r, p, 32 bytes)
```

Every export uses a fresh random salt and a fresh random nonce (`java.security.SecureRandom`),
so a key is never used with the same nonce twice.

## Conventions

- All integers are big-endian (network order). `u8`/`u16` are unsigned, `i32`/`i64` are
  two's-complement signed.
- Offsets and sizes are in bytes.
- `S` is the salt length read from the header.

## File layout (format version 1)

| Offset | Size | Field | Value |
|---|---|---|---|
| 0 | 8 | magic | ASCII `LUCABKUP` = `4c 55 43 41 42 4b 55 50` |
| 8 | 1 | format version | `01` |
| 9 | 1 | KDF id | `01` = scrypt (the only one defined) |
| 10 | 1 | log2(N) | `u8`; the writer uses 16 |
| 11 | 1 | r | `u8`; the writer uses 8 |
| 12 | 1 | p | `u8`; the writer uses 2 |
| 13 | 1 | salt length `S` | `u8`, 16 to 64; the writer uses 32 |
| 14 | `S` | salt | random |
| 14 + S | 12 | nonce | random 96-bit AES-GCM nonce |
| 26 + S | rest | ciphertext then tag | AES-256-GCM ciphertext of the payload, followed by the 16-byte (128-bit) tag |

The **header** is bytes `0` to `26 + S - 1` (magic through nonce). There is no length
field for the ciphertext: it runs to the end of the file, so trailing bytes change the
ciphertext and fail authentication.

A file is at most **4 MiB** (4,194,304 bytes). The largest payload the limits allow
(below) is 3,101,005 bytes, so the largest valid file is 90 + 3,101,005 + 16 bytes.

## Password

1. Normalize the password to Unicode **NFC**. Nothing else is applied: no trimming, no
   case folding, no other mapping.
2. Encode it as **UTF-8**, without a byte order mark or terminator. A password with an
   unpaired UTF-16 surrogate has no UTF-8 encoding and is rejected.

NFC makes a password typed with precomposed characters (for example `ệ` U+1EC7) and one
typed with combining marks (`e` U+0323 U+0302), as different Vietnamese keyboards do,
produce the same key.

**Policy for new passwords** (`validatePassword`, `validatePasswordConfirmation`): at
least **12 characters**, counted as Unicode code points after NFC (so `ệ` counts once
however it was typed, and an emoji outside the BMP counts once). It must be entered
twice; the two entries match if their NFC UTF-8 bytes are equal (compared in constant
time). There is no other rule. Readers do not apply the policy: any password is tried.

## Key derivation

scrypt as defined in RFC 7914 (Bouncy Castle's `SCrypt.generate`), with N = 2^log2(N),
the header's r and p, the header's salt, and a 32-byte output used as the AES-256 key.

Defaults written by the app: N = 2^16, r = 8, p = 2 (128 · N · r = 64 MiB of memory).

Because the parameters come from the file, a reader **must** check these hard bounds
before running scrypt, so that a crafted file cannot exhaust memory or CPU:

- 14 ≤ log2(N) ≤ 17 (N a power of two, 2^14 ≤ N ≤ 2^17; the encoding makes N a power
  of two);
- 1 ≤ r ≤ 16;
- 1 ≤ p ≤ 4;
- estimated memory 128 · N · r ≤ 128 MiB (134,217,728 bytes);
- N < 2^(128 · r / 8), as RFC 7914 requires (within the bounds above this only excludes
  r = 1 with N ≥ 2^16; Bouncy Castle throws for it);
- 16 ≤ S ≤ 64.

The worst case a file can request is therefore 128 MiB of memory and 4 times the work of
a 128 MiB scrypt pass.

## Encryption

AES-256-GCM (`AES/GCM/NoPadding`), 12-byte nonce from the header, 128-bit tag. The
**whole header** (bytes `0` to `26 + S - 1`) is the associated data, so changing the
version, KDF id, parameters, salt length, salt or nonce makes authentication fail even
where it would not change the key.

## Payload (payload version 1)

The plaintext is a deterministic, length-prefixed encoding:

| Size | Field |
|---|---|
| 1 | payload version `u8` = `01` |
| 4 | account count `i32`, 0 to 1000 |
| … | that many account records, then nothing |

Account record:

| Size | Field | Rule on read |
|---|---|---|
| 1 | type `u8` | `01` = TOTP, `02` = HOTP |
| 1 | algorithm `u8` | `01` = SHA-1, `02` = SHA-256, `03` = SHA-512 |
| 1 | digits `u8` | 6 to 8 |
| 4 | period `i32`, seconds | ≥ 1 (stored for HOTP too, normally 30) |
| 8 | counter `i64` | ≥ 0 (stored for TOTP too, normally 0) |
| 8 | created-at `i64`, epoch milliseconds | ≥ 0 |
| 2 + n | issuer: `u16` length n, then n bytes of UTF-8 | n ≤ 1024, may be 0 |
| 2 + n | account name: `u16` length n, then n bytes of UTF-8 | n ≤ 1024, may be 0 |
| 2 + n | secret: `u16` length n, then n raw bytes (not Base32) | 1 ≤ n ≤ 1024 |

Codes are explicit values, never enum ordinals. Labels are stored exactly as the app
holds them (no normalization). UTF-8 is decoded strictly: overlong forms, encoded
surrogates, values above U+10FFFF and truncated sequences are invalid.

The digits, period, counter and non-empty-secret rules are the ones the app's
`OtpAccount` enforces for every account, including those parsed from `otpauth://` links.
The length limits (1024 bytes per label and secret, 1000 accounts) are specific to the
backup: the add-account path does not bound lengths today, so `exportBackup` refuses an
account above a limit (with an `IllegalArgumentException` naming the account index and
field) instead of writing a file it could not read back.

**Fail closed:** a reader must reject the whole payload if any record is malformed or
invalid, if the count is out of range, or if bytes are left over after the last record.
There is no partial import.

## Reading

`readBackup` never throws for any input; it returns exactly one of these, checking in
this order:

| Step | Condition | Result |
|---|---|---|
| 1 | file larger than 4 MiB | `TooLarge` (nothing else is read) |
| 2 | shorter than 8 bytes, or bytes 0–7 are not the magic | `NotABackup` |
| 3 | format version is not 1 | `UnsupportedVersion` |
| 4 | KDF id is not 1 | `UnsupportedVersion` |
| 5 | scrypt parameters or salt length outside the bounds | `ParametersOutOfBounds` |
| 6 | file ends before the header plus a 16-byte tag (at any earlier step too, once the magic matched) | `WrongPasswordOrDamaged` |
| 7 | GCM authentication fails | `WrongPasswordOrDamaged` |
| 8 | payload version is not 1 | `UnsupportedVersion` |
| 9 | payload malformed or an account invalid | `InvalidPayload` |
| — | otherwise | `Success(accounts)` |

Steps 1–6 run before scrypt. A wrong password and a modified or truncated file give the
same `WrongPasswordOrDamaged`, deliberately: GCM cannot tell them apart, and a short file
is reported the same way. A version or KDF id byte is checked as soon as it is read, so a
newer file is reported as `UnsupportedVersion` even if its later layout differs.

If the device cannot allocate scrypt's memory (at most 128 MiB), the `OutOfMemoryError`
propagates; callers should run the read off the main thread and handle it.

## Import planning

`planImport(existing, incoming)` decides nothing and writes nothing. An imported account
**conflicts** with an existing one if they have:

- the same type, issuer and account name (exact, case-sensitive string comparison), or
- identical secret bytes.

If several existing accounts match, the counterpart is the first (in the order given)
with the same secret, otherwise the first with the same label. Duplicates inside the
backup are not conflicts with each other.

For each conflict the user must choose **Skip**, **Keep both** or **Replace**;
`applyDecisions(plan, decisions)` requires exactly one decision per conflict, so nothing
is ever overwritten by default. It returns inserts (non-conflicting accounts and "Keep
both" accounts, in backup order) and replacements (existing id plus the account to
write over it). Two Replace decisions for the same existing account are rejected.

**Replace** overwrites every field of the existing account with the imported one; the
stored row keeps its id and creation time. **HOTP counter rule:**

| Existing and imported account | Counter written |
|---|---|
| both HOTP, identical secret bytes (any label) | the **larger** of the two counters |
| both HOTP, different secrets (only the labels matched) | the **imported** counter |
| both TOTP, or HOTP on one side only | the imported account as it is |

With the same secret, a replace can never move the counter backwards, which would
regenerate codes the server has already accepted. With a different secret, the account
was re-enrolled: the old counter says nothing about the new secret, and a larger old
counter could run past the server's look-ahead window (the codes would then be
rejected). A type change never matches by label, because the type is part of the label,
so the mixed case only happens when the secrets are identical.

## Sensitive data in memory

Passwords are `CharArray`, secrets and keys are `ByteArray`, and the code wipes
(fills with zeros) every intermediate array it owns in `finally` blocks: password copies
and their UTF-8 bytes, the derived key, the encoded payload before encryption, the
decrypted payload after decoding, and on a failed decode the secrets decoded so far.
This is **best-effort**:

- the JVM and ART may copy arrays (garbage collection, JIT) and never zero freed memory;
- `SecretKeySpec` keeps its own copy of the key, and `Cipher` keeps the AES key schedule
  and GCM state; neither can be wiped;
- Bouncy Castle's scrypt clears its large buffers, but its PBKDF2/HMAC state, derived from
  the password, is left to the garbage collector;
- a password that is not already in NFC goes through an immutable `String` during
  normalization (ASCII passwords and NFC input never do);
- the decrypted labels are `String`s;
- the secrets returned by `readBackup` are owned by the caller, which must wipe them;
- a password typed into a Compose text field exists as `String`s anyway.

Nothing here logs, and no `toString()` or exception message contains a password, key,
secret, label or decrypted payload.

## What the format does not hide

The file size reveals the payload size, hence roughly the number of accounts and the
length of their labels. The header (format version, scrypt parameters, salt, nonce) is
readable without the password.

## Versioning

- Any change to the container (header fields, KDF, cipher, defaults that change the
  bytes written) bumps the **format version** (byte 8).
- Any change to the payload encoding or its limits bumps the **payload version**.
- Each version has a golden file under `app/src/test/resources/backup/`. Existing golden
  files are never edited or regenerated, and must keep decrypting while the reader
  supports their version.
- This document changes in the same PR. See `CLAUDE.md`.

## Test vectors

**scrypt:** RFC 7914 section 12, vectors 1–3 (`ScryptTest`). Vector 4 (N = 2^20, r = 8)
needs 1 GiB and is not run.

**Golden file** `app/src/test/resources/backup/golden-v1.bin` (334 bytes, SHA-256
`f180df0f108e69c6d2327bc87c05a8df6ce40860486b55b0394a52c33d3a5a98`), made with:

- password `Mật khẩu thử nghiệm` ("test password"), NFC UTF-8
  `4d e1 ba ad 74 20 6b 68 e1 ba a9 75 20 74 68 e1 bb ad 20 6e 67 68 69 e1 bb 87 6d`;
- salt `00 01 02 … 1f` (32 bytes), nonce `a0 a1 … ab` (12 bytes);
- N = 2^16, r = 8, p = 2;
- four accounts using only the public test secrets `JBSWY3DPEHPK3PXP` and
  `GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ` (TOTP SHA-1/6/30 s, TOTP SHA-256/8/60 s with a
  Vietnamese issuer, HOTP SHA-512/7 digits with counter 2^32 and an emoji in the
  account name, TOTP with an empty account name); see `BackupGoldenFileTest`.

Header (58 bytes):

```
4c 55 43 41 42 4b 55 50 01 01 10 08 02 20 00 01
02 03 04 05 06 07 08 09 0a 0b 0c 0d 0e 0f 10 11
12 13 14 15 16 17 18 19 1a 1b 1c 1d 1e 1f a0 a1
a2 a3 a4 a5 a6 a7 a8 a9 aa ab
```

The tests check that the file decrypts to the expected accounts, that re-encoding the
same inputs reproduces it byte for byte, that the independent reader decodes it, and
that decryption without the header as associated data fails.
