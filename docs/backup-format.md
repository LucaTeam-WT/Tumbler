# Tumbler backup file format

**Status:** format version 1. Implemented as library code in
`app/src/main/java/io/github/lucateam_wt/tumbler/backup/` and covered by JVM unit tests.
**Not yet wired into the app:** there is no export or import screen, and nothing in the
app calls this code yet.

This document is normative: it should be precise enough to write an independent reader.
The unit tests include one (`SpecCodec` in
`app/src/test/java/io/github/lucateam_wt/tumbler/backup/BackupFixtures.kt`) that shares no code
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
   unpaired UTF-16 surrogate has no UTF-8 encoding and is rejected: on export it is
   `InvalidCharacters`, and on import `WrongPasswordOrDamaged` before scrypt runs.

NFC makes a password typed with precomposed characters (for example `ệ` U+1EC7) and one
typed with combining marks (`e` U+0323 U+0302), as different Vietnamese keyboards do,
produce the same key.

**Length cap.** A password has at most **1024 characters**, counted as Unicode code
points after NFC (`MAX_PASSWORD_CHARACTERS`). A longer password is `TooLong` on export,
and `WrongPasswordOrDamaged` on import before scrypt runs, since no backup can have been
written with it. An input longer than **8,192 UTF-16 units** is refused before it is
copied or normalized: NFC composes at most 4 code points into one (the longest canonical
decomposition, for example U+1F82 = U+03B1 U+0313 U+0300 U+0345, checked against every
code point by `BackupPasswordTest`), and a code point takes at most 2 UTF-16 units, so such
an input always has more than 1024 code points after NFC. The cap bounds every buffer
derived from a password; its UTF-8 form is at most 4 KiB. The UTF-8 encoder treats every
result other than complete success as a failure, including a full output buffer, so a
password is never truncated.

**Policy for new passwords** (`validatePassword`, `validatePasswordConfirmation`): at
least **12** and at most **1024 characters**, counted as Unicode code points after NFC
(so `ệ` counts once however it was typed, and an emoji outside the BMP counts once), and
no unpaired surrogate. It must be entered twice; the two entries match if their NFC UTF-8
bytes are equal (compared in constant time). There is no other rule. Readers do not apply
the minimum: any password up to the cap is tried.

## Key derivation

scrypt as defined in RFC 7914 (Bouncy Castle's `SCrypt.generate`), with N = 2^log2(N),
the header's r and p, the header's salt, and a 32-byte output used as the AES-256 key.

Defaults written by the app: N = 2^16, r = 8, p = 2 (128 · N · r = 64 MiB of memory).

Because the parameters come from the file, a reader **must** check these hard bounds
before running scrypt, so that a crafted file cannot exhaust memory or CPU:

- 14 ≤ log2(N) ≤ 16 (N a power of two, 2^14 ≤ N ≤ 2^16; the encoding makes N a power
  of two, and a log2(N) byte above 16 is rejected without computing 2^log2(N));
- 1 ≤ r ≤ 8;
- 1 ≤ p ≤ 4;
- estimated memory 128 · N · r ≤ 64 MiB (67,108,864 bytes). Within the N and r bounds
  this always holds; it is checked anyway, so loosening one of them cannot silently raise
  the memory cap;
- N < 2^(128 · r / 8), as RFC 7914 requires (within the bounds above this only excludes
  r = 1 with N = 2^16; Bouncy Castle throws for it);
- 16 ≤ S ≤ 64.

The writer's defaults sit at the N, r and memory bounds, so the reader never allocates
more than the writer did. The worst case a file can request is 64 MiB of memory and
4 times the work of one 64 MiB scrypt pass (twice the work of the defaults, because
p ≤ 4).

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
holds them (no Unicode normalization). UTF-8 is decoded strictly: overlong forms, encoded
surrogates, values above U+10FFFF and truncated sequences are invalid.

**Labels must already be trimmed.** Each label (issuer and account name) must be in the
form the app's `otpauth://` parser stores: no leading or trailing whitespace. Neither side
trims: the writer refuses such a label and the reader rejects it (below).

Whitespace is whatever Kotlin's `String.trim()` removes **on the platform running the
code**, the parser's own rule: a UTF-16 unit `c` for which `Char.isWhitespace()` is true,
that is, Java's `Character.isWhitespace(c)` or `Character.isSpaceChar(c)`. The set comes
from the platform's Unicode character tables, not from this document, so it can differ
between Android versions (and between Android and the desktop JVM). On the JVM the unit
tests run on, it is Unicode categories Zs, Zl and Zp (including U+00A0 and U+3000), U+0009
to U+000D and U+001C to U+001F (`labels_whitespaceIsExactlyTheSetTheSpecNames`).
Whitespace inside a label is kept. An empty label is allowed, and so are two empty labels
(the parser stores both empty for the label `:`).

The decoder **rejects** a label with surrounding whitespace (the whole payload is
`InvalidPayload`) instead of trimming it, and the writer refuses to write one
(`AccountRejectedException` with `LABEL_NOT_TRIMMED`; see [Writing](#writing)). A backup
never changes a label, so an imported ` GitHub` can neither silently become `GitHub` nor
be stored next to an existing `GitHub` that it fails to match. Because the set is the
platform's, a backup whose label starts or ends with a character that the writing device
did not treat as whitespace but the importing device does is rejected as a whole
(`InvalidPayload`), fail-closed; nothing is trimmed on import. The opposite case cannot
come from this writer, which refuses every label its own platform would trim.

The digits, period, counter and non-empty-secret rules are the ones the app's
`OtpAccount` enforces for every account, including those parsed from `otpauth://` links.
The length limits (1024 bytes per label and secret, 1000 accounts) are specific to the
backup: the add-account path does not bound lengths today, so `exportBackup` refuses an
account above a limit (`AccountRejectedException` naming the account and the reason; see
[Writing](#writing)) instead of writing a file it could not read back.

**Fail closed:** a reader must reject the whole payload if any record is malformed or
invalid (including a label that is not trimmed), if the count is out of range, or if
bytes are left over after the last record. There is no partial import.

## Writing

`exportBackup(accounts, password)` writes one file with the default parameters and a fresh
random salt and nonce. It runs scrypt, so call it off the main thread. The account checks
run before scrypt, so a rejected account costs no key derivation. It throws:

- `AccountRejectedException` (an `IllegalArgumentException`) if an account cannot be
  written. It names the **first** such account by `accountIndex`, its zero-based position
  in the list given, and gives a closed `reason` (`AccountRejection`):
  `TOO_MANY_ACCOUNTS` (more than 1000; the index is then 1000, the first account that does
  not fit), `LABEL_NOT_TRIMMED`, `LABEL_UNPAIRED_SURROGATE`, `LABEL_TOO_LONG` or
  `SECRET_TOO_LONG`. Accounts are checked in order and, within an account, the issuer,
  then the account name, then the secret. One such account blocks the whole export, by
  design: a backup holds every account or none. The message holds the index, the reason
  and the field name (`issuer`, `account name` or `secret`), never a label or a secret.
- `BackupNotEnoughMemoryException` if scrypt cannot allocate its memory. Only an
  `OutOfMemoryError` from the scrypt call is mapped (it is the exception's cause), as on
  the reading side (`NotEnoughMemory`); one anywhere else propagates unchanged. Nothing was
  written, and the export may succeed once more memory is free.
- `IllegalArgumentException` if the password fails `validatePassword`. The UI must check
  it with `validatePasswordConfirmation` first.

`sealBackup`, which takes the salt, nonce, parameters and KDF explicitly, is a test seam:
a fixed salt and nonce would reuse a GCM key and nonce. It is annotated
`@VisibleForTesting(otherwise = PRIVATE)`, so lint reports any caller outside the test
source sets (as a warning); `exportBackup` is its only production caller.

## Reading

There are two entry points. `readBackup(input: InputStream, password)` reads a file or
content URI and is the one to use. `readBackup(bytes: ByteArray, password)` reads a file
that is already in memory. Both run scrypt, so call them off the main thread.

**Bounded stream read.** The `InputStream` overload requests at most 4 MiB + 1 bytes
(4,194,305) from the stream in total. As soon as more than 4 MiB have arrived it returns
`TooLarge` without reading the rest, so a longer stream costs no more than a 4 MiB one.
Its read buffer starts at 64 KiB and doubles up to 4 MiB + 1 bytes; when the stream ends,
the bytes read are copied into an exact-size array, so peak memory for the file during
that final copy is about two buffers (about 8 MiB). It never closes the stream; the
caller owns it. If the stream throws
an `IOException`, or a read returns 0 bytes (which `InputStream` does not allow and which
would otherwise loop forever), the result is `ReadFailed`: the bytes read so far are
zeroed and never parsed. Every read buffer is zeroed before the call returns. The
`ByteArray` overload also returns `TooLarge` for more than 4 MiB without parsing, but by
then the caller has already read the whole file into memory.

Both overloads return exactly one of these closed results, checking in this order:

| Step | Condition | Result |
|---|---|---|
| 0 | (stream only) the stream throws `IOException` or a read returns 0 bytes before the end, while at most 4 MiB have arrived | `ReadFailed` |
| 1 | file larger than 4 MiB | `TooLarge` |
| 2 | shorter than 8 bytes, or bytes 0–7 are not the magic | `NotABackup` |
| 3 | format version is not 1 | `UnsupportedVersion` |
| 4 | KDF id is not 1 | `UnsupportedVersion` |
| 5 | scrypt parameters or salt length outside the bounds | `ParametersOutOfBounds` |
| 6 | file ends before the header plus a 16-byte tag (at any earlier step too, once the magic matched) | `WrongPasswordOrDamaged` |
| 7 | the password contains an unpaired UTF-16 surrogate (so it has no UTF-8 encoding), or is longer than 1024 code points after NFC (an input over 8,192 UTF-16 units is refused before it is copied); no backup can have been written with it | `WrongPasswordOrDamaged` |
| 8 | scrypt cannot allocate its memory (`OutOfMemoryError` from the KDF) | `NotEnoughMemory` |
| 9 | GCM authentication fails | `WrongPasswordOrDamaged` |
| 10 | payload version is not 1 | `UnsupportedVersion` |
| 11 | payload malformed or an account invalid | `InvalidPayload` |
| — | otherwise | `Success(accounts)` |

Steps 0–7 run before scrypt. A wrong password and a modified or truncated file give the
same `WrongPasswordOrDamaged`, deliberately: GCM cannot tell them apart, and a short file
is reported the same way. A version or KDF id byte is checked as soon as it is read, so a
newer file is reported as `UnsupportedVersion` even if its later layout differs.
`NotEnoughMemory` says nothing about the file or the password; nothing was decrypted, and
the same file may open on a device with more free memory.

**Never throws for any input.** Every file content, stream content and password (of any
length) gives one of the results above, including when scrypt runs out of memory. What
remains can be caused only by the platform or by the caller's stream implementation,
never by the bytes or the password: an `IllegalStateException` if AES-GCM is missing from
the platform, an `OutOfMemoryError` outside scrypt, and any exception other than
`IOException` that the caller's `InputStream` throws. Every allocation other than
scrypt's is bounded: buffers for the file and the payload by the 4 MiB limit (about two
file-sized buffers at the peak of a stream read), and buffers derived from the password
by the password cap, because an input longer than 8,192 UTF-16 units is refused before it
is copied (its NFC copy is at most 3 times that, and its UTF-8 form at most 4 KiB).

## Import planning

`planImport(existing, incoming)` decides nothing and writes nothing. An imported account
**conflicts** with an existing one if they have:

- the same type, issuer and account name (exact, case-sensitive string comparison), or
- identical secret bytes.

If several existing accounts match, the counterpart is the first (in the order given) of:

1. if the imported account is HOTP, a HOTP account with the same secret;
2. an account with the same secret;
3. an account with the same label.

Step 1 makes the HOTP rules below hold against every existing HOTP account with that
secret, wherever it sits in the list. Without it, an existing TOTP account with the same
secret listed first would be picked, Keep both would be offered, and the device could end
with two HOTP rows sharing a secret, one of them behind.

Duplicates inside the backup are not conflicts with each other. **Exception to the HOTP
rule below:** two HOTP accounts with the same secret *inside one backup* do not conflict
with each other, so both can be imported without a decision. The backup holds what the
device that wrote it had, so this reproduces that device's accounts rather than creating
a new pair. The rule still holds against this device: if it already has a HOTP account
with that secret, each of the two conflicts with it (Skip or Replace only, and only one of
them may replace it).

For each conflict the user must choose one of the decisions `allowedDecisions(conflict)`
returns, in this display order:

| Existing and imported account | Allowed decisions |
|---|---|
| both HOTP, identical secret bytes (any label) | **Skip**, **Replace** |
| any other conflict | **Skip**, **Keep both**, **Replace** |

Keep both is not offered for two HOTP accounts with the same secret. The server keeps one
counter for that secret, while two rows would each keep their own. Whichever row falls
behind would produce codes for counter values the server has already passed, and those
codes would be rejected.

`applyDecisions(plan, decisions)` requires exactly one decision per conflict, so nothing
is ever overwritten by default. Each decision must be allowed for its conflict. A missing
or extra decision, a decision that is not allowed, and two Replace decisions for the same
existing account are each rejected with an `IllegalArgumentException`, and no writes are
returned. Otherwise it returns inserts (non-conflicting accounts and "Keep both"
accounts, in backup order) and replacements (`Replacement`: the existing id, the
imported account and a counter policy).

**Replace** overwrites every field of the existing account with the imported one, except
the counter, which follows a **counter policy**; the stored row keeps its id and creation
time.

| Existing and imported account | Counter policy | Counter written |
|---|---|---|
| both HOTP, identical secret bytes (any label) | `KEEP_LARGER_OF_STORED_AND_IMPORTED` | the **larger** of the counter stored at write time and the imported one |
| both HOTP, different secrets (only the labels matched) | `USE_IMPORTED` | the **imported** counter |
| both TOTP, or HOTP on one side only | `USE_IMPORTED` | the imported account as it is |

**The counter rule is applied at write time, not when the plan is made.** A `Replacement`
carries the policy, not a final counter. The pure function
`resolveCounter(storedNow, replacement)` gives the value to write, where `storedNow` is
the counter read from the row inside the same repository transaction as the write. A
value fixed at plan time could be stale: with a plan made while the row held 100, three
codes generated before the user confirms (the row now holds 103) and an imported 5,
writing the plan-time 100 would move the counter backwards. `Replacement.plannedCounter`
is that plan-time value, kept **only as a display hint** (for example on a confirmation
screen); it must never be written, and neither may `imported.counter` be written directly.
Because the policy was chosen from the row's type and secret at plan time, the
transaction must also check that the row still exists with them, and otherwise abort and
plan again.

With the same secret, a replace can never move the counter backwards, which would
regenerate codes the server has already accepted. With a different secret, the account
was re-enrolled: the old counter says nothing about the new secret, and a larger old
counter could run past the server's look-ahead window (the codes would then be
rejected). A type change never matches by label, because the type is part of the label,
so the mixed case only happens when the secrets are identical.

## Sensitive data in memory

Passwords are `CharArray`, secrets and keys are `ByteArray`, and the code wipes
(fills with zeros) every intermediate array it owns in `finally` blocks: password copies
(bounded by the password cap) and their UTF-8 bytes, the UTF-8 encoder's buffer, the
derived key, the encoded payload before encryption, the file
bytes and read buffers of the stream overload, the decrypted payload after decoding, and
on a failed decode the secrets decoded so far.
This is **best-effort**:

- the JVM and ART may copy arrays (garbage collection, JIT) and never zero freed memory;
- `SecretKeySpec` keeps its own copy of the key, and `Cipher` keeps the AES key schedule
  and GCM state; neither can be wiped;
- Bouncy Castle's scrypt clears its large buffers (also when an allocation fails), but its
  PBKDF2/HMAC state, derived from the password, is left to the garbage collector;
- which passwords pass through an immutable `String` during NFC normalization:
  - a password whose UTF-16 units are all below U+0300 (ASCII, Latin-1, Latin
    Extended-A and -B, IPA, spacing modifier letters) is already NFC and is copied as it
    is. It never reaches `java.text.Normalizer` and never becomes a `String`, on any
    Android version;
  - any other password (for example one with a combining mark, a Vietnamese letter with
    two diacritics such as `ệ`, Greek, Cyrillic, CJK or an emoji) goes to
    `Normalizer.isNormalized`. On Android 8.0 to 11 (API 26 to 30) that copies it into a
    `String`. From Android 12 (API 31) the platform passes the `CharSequence` to ICU
    without that copy;
  - if such a password is not already NFC, `Normalizer.normalize` returns the normalized
    password as a `String` on every version;
- the decrypted labels are `String`s;
- the secrets returned by `readBackup` are owned by the caller, which must wipe them;
- a password typed into a Compose text field exists as `String`s anyway.

Nothing here logs, and no `toString()` or exception message contains a password, key,
secret, label or decrypted payload.

## Requirements for the integration (PR B)

Nothing in the app calls this code yet. The change that wires it into the UI and the
repository must:

1. **Read with the stream overload:** `readBackup(InputStream, password)` on the content
   URI's stream (it stops at 4 MiB + 1 bytes), not the `ByteArray` overload on a file read
   whole. The caller opens and closes the stream.
2. **Run export and import off the main thread, and handle running out of memory.** Show
   `NotEnoughMemory` (import) and `BackupNotEnoughMemoryException` (export) as their own
   "not enough memory" message and `ReadFailed` as a read error, never as "wrong password".
   Also handle an `OutOfMemoryError` from anywhere else in the operation, which is not
   mapped.
3. **Offer only the allowed decisions:** exactly `allowedDecisions(conflict)` per conflict
   (no Keep both for two HOTP accounts with the same secret), and prevent two Replace
   decisions for the same existing account. `applyDecisions` throws for either.
4. **Write Replace counters at write time.** Inside the repository transaction, read the
   row, check that it still exists with the type and secret it had when the plan was made,
   and write `replacement.imported` with the counter `resolveCounter(row.counter,
   replacement)`. Alternatively, plan again inside the transaction and abort if the row
   changed. Never write `plannedCounter` or `imported.counter` directly. Replace keeps the
   row's id and creation time; inserts use the imported creation time.
5. **Match the export limits on the add path, or handle the export failure.** The add path
   bounds no lengths today. Either apply the backup's limits (1024 UTF-8 bytes per label,
   1024 bytes per secret) and the parser's trimming to every way of creating or renaming an
   account, or handle `AccountRejectedException` (item 6).
6. **Show which account blocks an export:** use `AccountRejectedException.accountIndex` to
   name the account from the app's own list (not from the message) and `reason` to say
   what is wrong with it.
7. **Expect label whitespace to differ between platforms.** It is the platform's
   `String.trim()` (see [Payload](#payload-payload-version-1)), which can differ between
   Android versions. A backup whose label edges the importing platform treats as
   whitespace is rejected as a whole (`InvalidPayload`), fail-closed; the UI must not trim
   or repair it.
8. **Keep the password in a `CharArray` where possible**, wipe it after use, and never put
   it in saved instance state or a `SavedStateHandle`.
9. **Say that a wrong password and a damaged file are indistinguishable.** The message for
   `WrongPasswordOrDamaged` must say it could be either; the format cannot tell them apart.
10. **Wipe the imported secrets** returned in `Success` once they are stored.
11. **Run instrumented tests on a real device** (so far everything runs on the desktop JVM
    only):
    - Conscrypt AES-GCM: a modified file maps to `WrongPasswordOrDamaged` (Conscrypt's
      `AEADBadTagException` is a `BadPaddingException`), and decryption buffers the whole
      plaintext before returning it, so nothing unauthenticated is decoded;
    - Android's `java.text.Normalizer` (the golden password, NFD input, the U+0300 fast
      path, the longest canonical decomposition of 4) and its UTF-8 decoder strictness:
      run `BackupPayloadTest.decode_rejectsMalformedUtf8` as an instrumented test;
    - the `Character` whitespace tables across API levels (26 to current), for item 7;
    - real memory and timing: a 64 MiB scrypt plus the read buffers (about 8 MiB at the
      peak) on a low-RAM device, with p up to 4 (the most a file can ask for);
    - an export then import round trip on a release (R8-minified) build, since R8 has only
      been checked statically (no keep rules were added).

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
- No app version that writes backups has been released yet, so version 1 is not frozen:
  until then its rules may still be tightened in place, as long as `golden-v1.bin` keeps
  decrypting unchanged.

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

The tests check:

- that the committed file still has this size and SHA-256;
- that it decrypts to the expected accounts, also with the NFD form of the password;
- that re-encoding the same inputs reproduces it byte for byte;
- that the independent reader decodes it;
- that decryption without the header as associated data fails, while the same bytes with
  the same NFC password decrypt when the header is passed (a positive control, so the
  failure can only come from the missing associated data).

The test source is pure ASCII, with every non-ASCII character written as a `\u` escape,
so no editor can change the normalization form of the password or a label unnoticed.
