# Tumbler backup file format

**Status:** format version 1. Implemented in
`app/src/main/java/io/github/lucateam_wt/tumbler/backup/` and covered by JVM unit tests and
instrumented tests. `BackupService` connects it to the stored accounts (see
[Export and import service](#export-and-import-service)), and the accounts screen's menu
runs it ([Export and import screens](#export-and-import-screens)).

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
The add-account path applies the same label and secret limits (`AccountLimits`, enforced
by the `otpauth://` parser), so every account added since then can be exported. An
account stored before that, or above the 1000-account limit, can still exceed them, so
`exportBackup` refuses an account above a limit (`AccountRejectedException` naming the
account and the reason; see [Writing](#writing)) instead of writing a file it could not
read back.

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
`@VisibleForTesting(otherwise = PRIVATE)`, and `app/lint.xml` raises lint's
`VisibleForTests` check to an error, so `lintDebug` fails for any caller outside the test
source sets; `exportBackup` is its only production caller.

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
file-sized buffers at the end of a stream read, and about three during decryption; see
item 11 under [Requirements for the integration](#requirements-for-the-integration-pr-b)),
and buffers derived from the password
by the password cap, because an input longer than 8,192 UTF-16 units is refused before it
is copied (its NFC copy is at most 3 times that, and its UTF-8 form at most 4 KiB).

## Import planning

`planImport(existing, incoming)` decides nothing and writes nothing. An imported account
**conflicts** with an existing one if they have:

- the same type, issuer and account name (exact, case-sensitive string comparison), or
- identical secret bytes.

If several existing accounts match, the counterpart is chosen by rank, best first:

1. an account with the same label and the same secret (for an imported HOTP account this
   is a HOTP account, since the type is part of the label);
2. if the imported account is HOTP, another HOTP account with the same secret;
3. an account with the same secret;
4. an account with the same label.

Ranks 1 and 2 make the HOTP rules below hold against every existing HOTP account with that
secret, wherever it sits in the list. Without them, an existing TOTP account with the same
secret listed first would be picked, Keep both would be offered, and the device could end
with two HOTP rows sharing a secret, one of them behind. Rank 1 before rank 2 pairs an
imported HOTP account with the row of the same label when the device holds several HOTP
accounts with its secret.

Ranks are assigned in passes, best rank first and, within a pass, in backup order. Within
one rank, an existing account that no other imported account is paired with yet is
preferred, then the order given. A worse rank is never taken just to avoid sharing. If two
imported accounts still end up with the same counterpart (the device has fewer matching
accounts than the backup), both conflicts report it (`ImportConflict.sharesCounterpart`),
and at most one of them may replace it.

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
accounts, in backup order) and replacements (`Replacement`: the existing id and the
imported account, which it hands out only through `accountToWrite`).

**Replace** overwrites every field of the existing account with the imported one, except
the counter, which follows a **counter policy**; the stored row keeps its id and creation
time.

| Row as read at write time, and imported account | Counter policy | Counter written |
|---|---|---|
| both HOTP, identical secret bytes (any label) | `KEEP_LARGER_OF_STORED_AND_IMPORTED` | the **larger** of the stored counter and the imported one |
| both HOTP, different secrets (only the labels matched) | `USE_IMPORTED` | the **imported** counter |
| both TOTP, or HOTP on one side only | `USE_IMPORTED` | the imported account as it is |

**The counter rule is applied at write time, from the row as it is then.** A
`Replacement` exposes neither the imported account nor a counter. The only way to get the
account to write is `accountToWrite(storedNow)`, where `storedNow` is row `existingId` as
read inside the same repository transaction as the write (another row is an
`IllegalArgumentException`). The policy is decided from that row's type and secret
(`counterPolicy(storedNow, imported)`), not from what the plan saw, and
`resolveCounter(storedNow, imported)` gives the counter. A counter or policy fixed at plan
time could be stale: with a plan made while the row held 100, three codes generated before
the user confirms (the row now holds 103) and an imported 5, writing the plan-time 100
would move the counter backwards. Called with the plan-time account, `resolveCounter` is
only a display hint (for example on a confirmation screen).

With the same secret, a replace can never move the counter backwards, which would
regenerate codes the server has already accepted. With a different secret, the account
was re-enrolled: the old counter says nothing about the new secret, and a larger old
counter could run past the server's look-ahead window (the codes would then be
rejected). A type change never matches by label, because the type is part of the label,
so the mixed case only happens when the secrets are identical.

**Planning again before writing.** The accounts on the device can change while the user
decides. The writer therefore plans again, inside the write transaction, against the rows
it reads there, and compares the result with the plan the user confirmed
(`ImportPlan.matches`). The plans match if they hold equal imported accounts in the same
order and each one is clean in both, or conflicting in both with the same existing id and
an existing account equal in every field except the counter (so the reasons, the shared
counterparts and the allowed decisions are the same too). If they do not match (for example the counterpart was deleted, renamed or given
another secret, or an account was added that a clean import now conflicts with), nothing
is written and the user must decide again. Two changes do not count: a different counter
(generating a HOTP code moves it, and `accountToWrite` resolves it against the row read at
write time), and an added or deleted account that is no import's counterpart.

## Export and import service

`BackupService` (`backup/BackupService.kt`) runs the format against the stored accounts.
`AppContainer.backupService` creates it, and `BackupViewModel` (`ui/backup/`) calls it
([Export and import screens](#export-and-import-screens)). It reads and writes the
accounts through `AccountBackupStore` (`RoomAccountBackupStore`: the same table, encryption
and associated data as `RoomAccountRepository`; it seals the secrets it writes with the
newest data key and reads both formats of [storage-format.md](storage-format.md), which
this backup format does not depend on). Before every read and every write transaction it
waits until the startup migration of stored secrets has ended. Every function:

- runs on an injectable dispatcher (by default `Dispatchers.Default`, since scrypt is
  CPU-bound), so it may be called from the main thread;
- takes ownership of the password array and fills it with zeros before it returns,
  whatever the outcome;
- never opens or closes the caller's stream;
- returns a closed result for every outcome the UI must handle, including an
  `OutOfMemoryError` anywhere in the operation and an `IOException` from the stream. What
  can still propagate is a programming or platform fault (such as AES-GCM missing from the
  platform), a non-`IOException` thrown by the caller's stream, and cancellation.

**`exportAccounts(password, output)`** checks the password with `validatePassword`
(`PasswordRejected`, nothing read). It then reads and decrypts every account in one query,
calls `exportBackup`, writes the bytes and flushes the stream. Other results:

- `AccountUnreadable(accountId)`: an account does not decrypt or holds invalid values;
- `AccountRejected(accountId, reason)`: the first account the backup cannot hold, mapped
  from `AccountRejectedException.accountIndex`;
- `NotEnoughMemory`, in scrypt or anywhere else;
- `WriteFailed`: the stream threw while writing or flushing, so part of the file may
  exist;
- `StorageFailed`: a database error.

The decrypted secrets are wiped in every case: as soon as `exportBackup` returns the
encrypted file, before the stream (and so the document provider) is touched, and on every
other exit before the call returns.

**`readBackup(input, password)`** uses the stream overload, then plans the backup against
the accounts as they are now and returns `Ready(PreparedImport)`. Other results:

- `FileRejected(reason)`: any `BackupReadResult.Failure` except `NotEnoughMemory`;
- `NotEnoughMemory`;
- `ExistingAccountUnreadable(accountId)`: an account on the device that cannot be read
  could share a secret with an imported one, so no plan is made;
- `StorageFailed`.

On every result except `Ready` the imported secrets are already wiped.

**`applyImport(prepared, decisions)`** writes in one transaction:

1. It checks the decisions against the confirmed plan with `applyDecisions`: one per
   conflict, each one of `allowedDecisions`, and at most one Replace per existing
   account. Otherwise it returns `DecisionsRejected` and starts no transaction.
2. In one write transaction (Room `withTransaction`) it reads every account again, plans
   the same imported accounts again and compares the new plan with the confirmed one
   (`ImportPlan.matches`). If they no longer match, it writes nothing and returns
   `PlanChanged(newPlan)`. The old plan is then closed, and the new one, planned against
   the rows just read, needs new decisions.
3. Otherwise it writes from the plan made inside the transaction. Each Replace writes
   `replacement.accountToWrite(row)` for the row read there, keeping the row's id and
   creation time. Clean imports and Keep both are inserted with their imported creation
   time. The result is `Success(inserted, replaced, skipped)`.

A failure while writing rolls the whole transaction back and leaves the plan open for a
retry. Those results are `StorageFailed` (a database or Keystore error, a storage key that
failed in a way that may be transient, or a storage key that must be replaced first), `NotEnoughMemory`
and `ExistingAccountUnreadable`. `PlanClosed` means the plan was already applied (or may
have been, see below), replaced or closed, or is being applied.

Once the apply has begun on the service's dispatcher, a cancellation does not stop the
transaction. Room's `withTransaction` runs the block on its own transaction thread and
would resume a cancelled caller at once while that thread may still commit, so the service
waits for the transaction under `withContext(NonCancellable)`. Room's wait for its
transaction thread and the write lock then cannot be cancelled either, so the import is
written and the plan closed (or the plan is found changed and nothing is written). A
cancellation that lands before that point, while entering or dispatching `withContext`,
throws `CancellationException` with nothing written and the plan still open. Once the
transaction block has returned its writes, the import may have been committed, so every
other exit from that point on (cancellation, running out of memory, a database error while
committing) closes the plan and wipes its secrets; it is never left open. A retry then
returns `PlanClosed`, so the accounts can never be imported twice. Cancellation still
propagates as `CancellationException`; after one, `isOpen` says whether a retry is
possible. A plan the service has made is closed and wiped if a cancellation stops it from
reaching the caller: a `Ready` or `PlanChanged` made for a caller that was cancelled before
it received it is closed. The service allocates `Ready` and `PlanChanged` before it hands
the plan over, so running out of memory on that allocation closes the plan (`read`), or
keeps the old plan open because nothing was written (`apply`). The store wipes a secret it
was decrypting when the caller is cancelled during that decrypt, and the secrets already
read when converting them runs out of memory (item 14).

For the UI: do not `join()` the import's job on the main thread, since it waits for the
transaction, and `withTimeout` cannot stop an import that has started. A `StorageFailed`
or `NotEnoughMemory` that comes with a closed plan (`isOpen` is false) means the import may
have been written: reload the accounts instead of offering a retry. The screens do both
([Export and import screens](#export-and-import-screens)).

`PreparedImport` holds decrypted secrets: the imported accounts, and the device's accounts
as read for the plan. A successful apply wipes them. Otherwise the caller must `close()`
it, which wipes them too. A close during an apply takes effect when that apply ends, so it
never wipes secrets that are being written. Results and `PreparedImport` print only
counts and reasons, never an id, a label or a secret.

**Stale list cache (fixed, see item 12 below):** the accounts screen keeps a cached list of
the accounts: for a TOTP account its code generator (holding a copy of the secret), for a
HOTP account only that its secret decrypts (no secret is kept; the decrypted array is
wiped at once). That cache is replaced only after an import has committed and the list has
decrypted every account again, so in between it still describes the account as it was
before the import. "Next code" no longer reads it: the code and the counter come from one
repository transaction on the stored row. After a Replace that changed the secret
and set the counter to 50, a tap in that window shows the new secret's code for counter 50
and stores 51. Before this fix it wrote the absolute counter "cached counter + 1" (3 + 1
over 50) and showed a code of the old secret; after a Replace with the same HOTP secret it
could also move the counter below the one the import kept.

## Export and import screens

`ui/backup/` (`BackupViewModel` with the sealed `BackupUiState`, the dialogs, the import
review screen and `BackupFile`) runs the service. Both flows start from the overflow menu
(three dots) of the accounts screen. The system file pickers need no permission.

**Export.** With no accounts, "Export backup" shows a short message instead of the
dialog. Otherwise:

1. A password dialog asks for the password twice. The fields are secure (password
   keyboard, so no suggestions or learning; no autocorrect; no copy or cut) with a
   show/hide button. Their input is capped at `MAX_PASSWORD_INPUT_UTF16_UNITS`. Feedback
   is live and uses the export rules (`validatePassword`, `validatePasswordConfirmation`):
   12 to 1024 characters after NFC, no unpaired surrogate, and both entries equal after
   NFC. A confirmation that is still a prefix of the password is not flagged yet.
2. The system "create document" picker opens (`ActivityResultContracts.CreateDocument`,
   MIME type `application/octet-stream`, suggested name
   `tumbler-backup-YYYY-MM-DD.lucabkup`).
3. `exportAccounts` encrypts the whole backup in memory, then writes it to the document's
   stream with one call. The document is opened for writing ("wt", which truncates it)
   only at that write (`OpenOnFirstWriteOutput`), so nothing before it changes the
   document. An indeterminate progress dialog runs meanwhile, and Back does nothing.
4. A message gives the outcome. On success it says that the file is only as safe as its
   password, and that the password is not stored anywhere and cannot be recovered. Every
   `ExportResult` has its own message (an exhaustive `when`). An account that blocks the
   export is named from the app's list by its id (cut to 80 characters); an unreadable
   account is never named.

**Partial export (item 13).** The document from the picker is opened for writing only when
the service writes the finished file, and only while the export's job is still active:
`OpenOnFirstWriteOutput` checks the job right before it opens the document and, if it was
cancelled, throws its `CancellationException` without opening anything. The document is
therefore left exactly as it was (not opened, truncated, read or deleted) in these cases:
every failure before that write (a refused password, an account the backup cannot hold, an
unreadable account, a database error, not enough memory, an unexpected exception), and a
cancellation (the view model cleared) that lands at any time before that check, including
while scrypt runs. scrypt blocks and does not stop on cancellation, so the service still
reaches the write after it; the check is what keeps the document untouched then. For a
failure the message says that the chosen file was not changed, and that if it was created
for this backup it is empty and can be deleted; a cancellation shows no message. A
cancellation that lands after the check, while the document is being opened or written,
is handled like a failed write (below): the document may already be truncated, and it is
deleted.

Once the document was opened, every outcome but `Success` deletes it under
`NonCancellable`, with `DocumentsContract.deleteDocument`. That covers `WriteFailed`, a
`close()` that throws (also `WriteFailed`, since a provider may commit the file on close),
an unexpected exception and a cancellation. If the provider refuses the delete, the
document is truncated to zero bytes and the message asks the user to delete it.

A document that cannot be opened (the provider throws `IOException`, `SecurityException`,
`IllegalArgumentException` or `UnsupportedOperationException`) gives "could not be opened
for writing". It is deleted only if it is empty: the view model reads at most one byte to
find out, and leaves it alone if it holds bytes or that cannot be told. The same rule
applies when the picker's result arrives after the app was restarted: the password is
gone, the document is deleted only if it is empty, and the user is told to start again.
*Known limit:* an empty document cannot be told apart from one the picker has just
created, so an existing empty file the user picked is deleted in these two cases too, and
the message says "The incomplete file was deleted."

**What a failed write loses.** The Storage Access Framework cannot replace a document
atomically: opening it with "wt" empties it at once. The app asks the picker to create a
new document and does not check whether a provider returns an existing one instead. If one
does, its earlier content is gone as soon as the write starts, and a write that then fails
halfway (storage full, a provider error, a failed close) leaves neither that content nor a
usable new backup; the broken document is deleted. Saving each backup under a new name
keeps the earlier one. Not verified on a device: no picker that returns an existing
document was tried.

**Import.**

1. The system "open document" picker opens (`OpenDocument`, any type). The view model
   moves to `ImportChoosingFile` before it opens, so a second tap on Import opens no
   second picker. A picker result that arrives while another file is already being
   imported is ignored; nothing is opened for it. A result that arrives after the app was
   restarted is taken.
2. A password dialog asks for the backup's password.
3. `readBackup` reads the document's stream (closed afterwards). A progress dialog with
   Cancel runs meanwhile. A plan made for a read that was cancelled is closed, by the
   service or by the view model. A read acts on its outcome only while it is the latest
   read and the dialog still waits for it: an exception that arrives after Cancel, or
   after a newer import started, is ignored instead of ending the flow that runs now with
   "Nothing was imported". *Known limit:* Cancel does not stop scrypt, which blocks: the
   cancelled read's scrypt keeps running to its end while the user may already start
   another import or an export, so two scrypt runs can overlap (at the default parameters
   a peak of about 2 × 64 MiB plus buffers).
4. `WrongPasswordOrDamaged` shows the same dialog again for the same file with "Wrong
   password or damaged file." Nothing tells the two apart. The failures that are found
   without the password, or after a successful decryption, keep their own messages: not
   a Tumbler backup, unsupported version or parameters, invalid content, too large, read
   error. So do not enough memory, an unreadable account on the device (never named) and
   a database error.
5. `Ready` opens the review screen.
   - It shows the counts of new, identical and conflicting accounts.
   - An identical conflict (`incoming.account == existing.account`) is skipped and only
     counted.
   - Every other conflict shows both labels (never a secret) and the reason. It says when
     `sharesCounterpart` is true.
   - Each conflict offers exactly `allowedDecisions` as radio buttons, with Skip selected;
     Replace is never preselected.
   - Each choice says what happens to a HOTP counter. Replace with the same secret keeps
     the larger counter, and both values are shown; Replace with a new secret takes the
     backup's counter; Keep both adds the account with its own counter.
   - A second Replace of the same device account is disabled, with a note.
6. Continue opens a confirmation with the totals (to add, to replace, to skip). When
   something is to be replaced, it also says that Replace overwrites the current contents
   of that many accounts with the ones from the backup, and that this cannot be undone.
   Import writes; Back returns to the review. Cancel (the button, the close icon or Back)
   works until Import is pressed.
7. `applyImport` runs in `viewModelScope` and is never joined, so leaving the screen does
   not abandon it. A progress dialog that Back cannot close runs meanwhile. Every result
   is mapped by `importStep` (exhaustive):
   - `Success` shows the counts, and the list reloads.
   - `PlanChanged` shows the new plan's review with a notice and every decision back to
     Skip. It needs a new confirmation and is never applied by itself.
   - `DecisionsRejected` shows the review again with the default decisions.
   - `StorageFailed` and `NotEnoughMemory` with an open plan show the review again with a
     notice and the same decisions, for a retry.
   - Each notice is pinned above the list, outside it, so it is on screen however far the
     list was scrolled.
   - The same results with a closed plan, `PlanClosed`, and an unexpected exception say
     that the import may have been saved and the user should check the list. The list
     reloads, and no retry is offered.
   - `ExistingAccountUnreadable` shows its message.
8. The `PreparedImport` is closed on every exit: cancel, Back, a result that ends the
   import, a plan replaced by a new one, and `ViewModel.onCleared`.

**The list after an import.** The accounts list observes Room (`observeAccounts`), so a
committed import reaches it by itself. After an import that wrote, or may have written,
the view model also asks the list to reload (`AccountListViewModel.reload`). That restarts
the observation and decrypts every account again, which also recovers a list whose
loading had failed.

**Passwords in the UI.** Each field's text is a `TextFieldState` owned by
`BackupViewModel` (`PasswordInput`), never `rememberSaveable`, `rememberTextFieldState` or a
`SavedStateHandle`. So it survives a rotation but not process death, after which the flow
starts again with empty fields. The text reaches the service only as a new `CharArray`,
made when the password is submitted. The field and its undo history are cleared at once.
The array is wiped when the service returns, on cancel, when the picker is cancelled or
missing, and in `onCleared`. The export password waits as that array only while the
picker is open. The show/hide state is not saved either. No `toString()` of an input,
state or message prints a password or a label. Compose itself keeps the typed text in
`String`s and buffers that cannot be wiped (see below).

The fields carry the `Password` semantics property, which marks them as password fields,
and draw only mask characters. The Compose test tree showed that their editable text
(`EditableText`) still holds the real text. An enabled accessibility service receives the
password as it is typed: for each change Compose sends a `TYPE_VIEW_TEXT_CHANGED` event
that carries the old text (`beforeText`) and the new text, marked as a password event but
not stripped (checked in the Compose UI 1.12.1 sources, not verified on a device).

The fields are not reported to autofill: `contentDataType = ContentDataType.None`, set
outside the text field so that it replaces the field's own `Text` data type. With it,
Compose (1.12.1) does not tell the autofill framework when the field gains focus or its
value changes, so nothing in the app starts an autofill session for it. The field is still
in the view structure that Compose gives the platform: with the autofill type None, the
hint "password" (from the secure text field's content type), the sensitive flag, and its
current text as its autofill value (checked in the Compose UI 1.12.1 sources). A device
test checks that the property reaches each field. Whether the platform puts that value in
a save request, and whether a password manager stays quiet, was not verified on a device;
a password manager that reads the screen through accessibility is not affected.

**Add path (item 5).** A link whose issuer or account name is over 1024 UTF-8 bytes, or
whose secret is over 1024 bytes, gets its own message with the limit ("too long to fit in
a backup", `AccountLimitException`). Every other bad link keeps "invalid link". The labels
are checked before the secret is Base32-decoded, and a decoded secret that is rejected for
its size is wiped.

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
- the secrets returned by `readBackup` are owned by the caller, which must wipe them
  (`BackupService` does, see above);
- a password typed into a Compose text field exists as `String`s anyway (the screens copy
  it into a `CharArray` only when it is submitted, see
  [Export and import screens](#export-and-import-screens)), and the keyboard app sees
  every character typed.

Nothing here logs, and no `toString()` or exception message contains a password, key,
secret, label or decrypted payload.

## Requirements for the integration (PR B)

PR B was split in parts. **B1** added the storage and service layer
([Export and import service](#export-and-import-service)), **B2a** made "next code"
atomic (item 12), and **B2** added the screens
([Export and import screens](#export-and-import-screens)). Each requirement says which part
does what, and what is left:

1. **Read with the stream overload.** *Done in B1:* `BackupService.readBackup` calls
   `readBackup(InputStream, password)`, which stops at 4 MiB + 1 bytes. *Done in B2:* the
   view model passes the content URI's stream (`ContentResolver.openInputStream`), never a
   file read whole, and closes it in a `finally`.
2. **Run export and import off the main thread, and handle running out of memory.**
   *Done in B1:* the service runs on its dispatcher and maps every `OutOfMemoryError`,
   in scrypt or not, to a `NotEnoughMemory` result. *Done in B2:* `NotEnoughMemory` has its
   own "not enough memory" message, and `FileRejected(ReadFailed)` a read error message,
   never "wrong password".
3. **Offer only the allowed decisions.** *Done in B1:* `applyImport` returns
   `DecisionsRejected` for a decision outside `allowedDecisions` (such as Keep both for two
   HOTP accounts with the same secret) and for a second Replace of the same existing
   account. *Done in B2:* the review offers exactly `allowedDecisions(conflict)` per
   conflict, says so when `sharesCounterpart` is true, and disables a second Replace of the
   same device account.
4. **Write Replace counters at write time, and plan again before writing.** *Done in B1:*
   see `applyImport` above. *Done in B2:* on `PlanChanged` the review shows the new plan
   with a notice and asks again; it is never applied by itself.
5. **Match the export limits on the add path.** Accounts are created in two ways: adding
   an `otpauth://` link, and importing a backup (Keep both and new accounts). Both use the
   same limits. An import can only bring in what `BackupPayload.decode` accepted: labels
   of at most 1024 UTF-8 bytes, valid UTF-8 (so no unpaired surrogate) and already
   trimmed, and secrets of 1 to 1024 bytes. *Done in B1:* `OtpAuthUri.parse` rejects an
   issuer or account name above 1024 UTF-8 bytes (counted after trimming) or with an
   unpaired surrogate, and a secret above 1024 bytes (`AccountLimits`, equal to the
   backup's limits; a unit test checks that). *Done in
   B2:* a label or secret over the limits throws `AccountLimitException`, and the add
   screen shows its own message for it in English and Vietnamese; every other bad link
   keeps "invalid link". The labels are checked before the secret is decoded, and a
   decoded secret rejected for its size is wiped. *Still to do:* apply `AccountLimits` and
   the parser's trimming to any future way of creating or renaming an account. Accounts
   stored before B1 are not checked; an export reports them (item 6).
6. **Show which account blocks an export.** *Done in B1:* `ExportResult.AccountRejected`
   carries the account's id (mapped from `AccountRejectedException.accountIndex`) and the
   `reason`. *Done in B2:* the message names the account from the app's own list by that
   id and says what is wrong with it.
7. **Expect label whitespace to differ between platforms.** It is the platform's
   `String.trim()` (see [Payload](#payload-payload-version-1)), which can differ between
   Android versions. A backup whose label edges the importing platform treats as
   whitespace is rejected as a whole (`InvalidPayload`), fail-closed; the UI does not trim
   or repair it.
8. **Keep the password in a `CharArray` where possible.** *Done in B1:* the service takes
   a `CharArray` and wipes it. *Done in B2:* a new array per call, made from the field when
   the password is submitted and wiped on every path; the password is never in saved
   instance state or a `SavedStateHandle`.
9. **Say that a wrong password and a damaged file are indistinguishable.** *Done in B2:*
   `FileRejected(WrongPasswordOrDamaged)` shows "Wrong password or damaged file." and
   offers another try with the same file.
10. **Wipe the imported secrets once they are stored.** *Done in B1:* the service wipes
    them after `Success` and on every failure. *Done in B2:* the view model closes the
    `PreparedImport` on cancel, Back, every result that ends the import, a replaced plan
    and `ViewModel.onCleared`.
11. **Run instrumented tests on a real device.** All the instrumented tests
    (`connectedDebugAndroidTest`, 107 at the time) ran once, on one Android 17 phone (model
    PKG110). All passed except three of B2's. Two were mistakes in
    `BackupScreensTest`, since fixed:
    - the review test tapped a choice that `performScrollToNode` had left behind the
      bottom bar, so the bar took the tap and no choice was recorded (the app never
      recorded a wrong one);
    - the password test expected the typed text in no node at all. The `Password`
      property only marks the field as a password field; the Compose test tree showed
      that the field's editable text (`EditableText`) holds the real, unmasked text.
      Compose sends that text to enabled accessibility services in its text-changed
      events (see [Export and import screens](#export-and-import-screens) and
      `SECURITY.md`; not verified on a device).
    The third was the 1000-account test: one of its phases ran past the 5-minute wait. It
    now runs within one 30-minute budget and logs each phase (below).
    After those fixes and the following review fixes, the whole suite ran again on the
    same phone: 111 tests, 0 failures, 0 skipped (the 1000-account measurement is below).
    Not run on a device yet: the tests added or changed with the latest review fixes
    (`BackupScreensTest.exportedMessageWarnsThatThePasswordCannotBeRecovered`, which now
    checks the warning as its own string in English and Vietnamese).
    - *B1:* `BackupServiceInstrumentedTest` (a round trip with the default parameters into
      an empty database and into one with conflicts, the abort path, a cancellation inside
      the import's last insert, and the Conscrypt damage matrix: a modified file maps to
      `WrongPasswordOrDamaged`, since Conscrypt's `AEADBadTagException` is a
      `BadPaddingException`, and decryption buffers the whole plaintext before returning
      it, so nothing unauthenticated is decoded) and
      `RoomAccountBackupStoreInstrumentedTest` (real transactions and rollback).
    - *B2:* `BackupFlowInstrumentedTest` drives `BackupViewModel` with Room, the Keystore
      and the default scrypt parameters: a round trip of a TOTP account and HOTP accounts
      at counter 0 and `Long.MAX_VALUE` into an empty database (the list's flow sees the
      import); a round trip with conflicts and each decision; wrong password and damaged
      file in the same state; Android's `java.text.Normalizer` on passwords (NFC and NFD
      Vietnamese, Hangul syllables and jamo, compatibility forms that must not match,
      surrounding spaces and tabs, characters outside the BMP); 1000 accounts exported,
      read and imported, compared field by field and, for a sample, secret by secret; and
      a Replace that lands while "next code" is being used, which never shows a code twice.
      `BackupScreensTest` covers the menu, the dialogs and the review at font scale 1 and
      1.3.
    - *The 1000-account measurement.*
      `BackupFlowInstrumentedTest.memory_thousandAccountsExportAndImportWithoutRunningOutOfMemory`
      checks correctness, not speed, within one 30-minute budget. As soon as each phase
      ends it logs, under the tag `TumblerBackupPerf`:
      - the setup (1000 accounts added; before the data key one Keystore encryption each,
        now one software seal each);
      - where the Keystore keeps the key (StrongBox, TEE or software);
      - the average time of a key lookup, a decrypt and an encrypt (and, since the data
        key, of a legacy decrypt and of opening a row with the data key);
      - one scrypt at the default parameters;
      - the export, the read and the import, with the file size and the scrypt parameters
        read from its header.
      The lines hold counts, sizes, parameters and times only. Run the test alone, then
      read its lines:

      ```
      ./gradlew connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=io.github.lucateam_wt.tumbler.ui.backup.BackupFlowInstrumentedTest#memory_thousandAccountsExportAndImportWithoutRunningOutOfMemory"
      adb logcat -d -s TumblerBackupPerf:I
      ```

      At the time of this measurement (before the data key, see below) each secret was
      encrypted on its own under the Keystore key. An export therefore ran one Keystore
      decryption per account. An import ran one Keystore encryption per new
      or replaced account, and decrypted the device's accounts twice: once for the plan and
      once inside the write transaction. scrypt runs once per file.

      Measured in the full run on the PKG110 (Android 17, API 37, heap limit 256 MiB,
      default scrypt N = 2^16, r = 8, p = 2), from the test's `TumblerBackupPerf` lines:

      | Measurement | Result |
      |---|---|
      | Where the Keystore keeps the key | StrongBox |
      | Key lookup, average of 20 | 1.5 ms |
      | Decrypt one account's secret, average of 20 | 324.1 ms |
      | Encrypt one account's secret, average of 20 | 331.2 ms |
      | One scrypt (export of an empty backup) | 4154 ms |
      | Setup: 1000 accounts added | 340072 ms |
      | Export of 1000 accounts | 328706 ms (file 451079 bytes) |
      | Read of that file, planned against an empty device | 4123 ms |
      | Import of 1000 accounts into that empty device | 338622 ms |
      | Whole test | 1037142 ms |
      | Heap in use at the end | 11 MiB |

      The cost is the Keystore operation per account (about 0.33 s with StrongBox on this
      phone), not scrypt or the file: export and import take seconds per ten accounts, and
      several minutes for 1000. The read took about one scrypt because the device it was
      planned against was empty; on a device with accounts, the read and the import each
      add one Keystore decryption per account already there. The time to show the account
      list (one Keystore decryption per account as well) was not measured.

      These figures were measured before the data key. Since then the secrets are sealed in
      software with one data key, which is unwrapped once per process: export, import and
      the account list make no Keystore call per account once it is ready (a row read
      through its legacy copy still costs one). An upgraded device converts each stored
      secret once at startup, with one Keystore decryption per account. None of this has
      been measured on a device yet; the test's setup and its sample lines now time the
      data key instead.
    - *Still to do:* `BackupPayloadTest.decode_rejectsMalformedUtf8` and the golden
      password's NFD and longest-decomposition cases as instrumented tests; the
      `Character` whitespace tables across API levels (26 to current), for item 7; the
      memory peak on a low-RAM device with p up to 4 (the most a file can ask for). The
      stream read peaks at about two file-sized buffers (about 8 MiB) during its final
      copy, but that peak ends before scrypt runs: while scrypt holds its 64 MiB, only the
      file bytes (at most 4 MiB) stay in memory besides it. The larger peak outside scrypt
      comes after it, during decryption: the file, the cipher's internal buffer and the
      decrypted plaintext, so about 12 MiB for a 4 MiB file. The 1000-account test logs
      timings and the heap in use at its end, but does not measure that peak. An export then import round trip on a
      release (R8-minified) build is a manual check: release builds now keep the backup
      code and Bouncy Castle without keep rules.
12. **Make "next code" atomic before import is reachable (mandatory).** *Done in B2a:*
    `AccountRepository.nextHotpCode` (`RoomAccountRepository`) runs in one Room write
    transaction (`withTransaction`, through the same `TransactionRunner` as
    `RoomAccountBackupStore`). It reads the row; returns `NotFound`, `NotHotp`, or
    `LimitReached` at `Long.MAX_VALUE` (before decrypting or writing anything); decrypts
    the secret with the row's associated data; generates the code for the counter it read;
    and writes counter + 1 with a compare-and-set (`AccountDao.advanceCounter`, which
    updates the row only while it still holds that counter and that encrypted secret). The
    decrypted secret is wiped in a `finally` on every path, cancellation included. The
    accounts screen no longer generates HOTP codes from its cached account or writes an
    absolute counter (`updateCounter` is removed); the cache only decides whether a row
    offers "next code", and the screen shows the code the repository returns. The cache is
    therefore not involved any more (see the stale list cache above). Both guards are used
    because they are cheap together: Room's write transaction keeps other writers out
    between the read and the write, and the compare-and-set turns a row changed in between
    into `Failed` (no code, nothing written) instead of an overwrite; a unit test shows it
    holding without any transaction isolation. `NextHotpCodeInstrumentedTest` (real Room
    and Keystore: a replace between the list load and the tap, 20 concurrent calls, the
    counter limit) passed on one Android 17 phone. *Known and accepted:* a "next code" that
    is cancelled (its view model is cleared, for example when the activity finishes) after
    the transaction has committed has stored the next counter, but its code is never
    shown. That is harmless for HOTP: a code that is generated and never used is the normal
    case the server's look-ahead window allows for (RFC 4226, section 7.4), and the skipped
    code is never shown or reused.
13. **Delete a partial export, and leave the document alone until the backup is ready.**
    *Done in B2:* the view model opens the document ("wt") only at the service's one write
    of the finished file (`OpenOnFirstWriteOutput`), and only if the export's job is still
    active at that moment. So a failure before that write, and a cancellation that lands
    before the open is attempted (also one during scrypt, which does not stop on
    cancellation), leave the document unchanged, whatever it held; a cancellation after
    that check is handled like a failed write. Once the document is open, every
    outcome but `Success` deletes it (`DocumentsContract.deleteDocument`, under
    `NonCancellable`), including `WriteFailed`, a `close()` that throws (treated as a
    failed write, since a provider may write its last bytes on close), an unexpected
    exception and a cancellation; if the provider refuses, the document is truncated to
    zero bytes and the message asks the user to delete it. A document that cannot be
    opened, or that arrives after the app was restarted, is deleted only if it is empty.
    A write that fails halfway still loses what the document held before (see "What a
    failed write loses" above). Unit tests use a fake document that records every open for
    writing, every look into it and every delete: failures before the write, and a
    cancellation while the codec blocks in its scrypt step, leave a document holding a
    backup untouched; a write that fails midway, a failed close and a
    cancellation during the write delete it; each open failure deletes an empty document
    and keeps one with content; a result after a restart deletes an empty document and
    never one with content.
14. **Close the remaining best-effort wiping gaps.** *Done in B2:*
    - *Cancellation during a decrypt.* `RoomAccountBackupStore.decrypt` keeps the
      decrypted array in a local set inside the `withContext` block and wipes it on any
      `Throwable` (a cancellation that drops the block's result included) before it
      rethrows. `decryptAll` wipes every secret already read if a later row fails.
      `BackupService` converts the rows (`toExistingAccounts`) inside a `try` that wipes
      them all if that runs out of memory. `RoomAccountRepository.getAccount`, which the
      accounts list uses, does the same with its one decrypted array, and the list wipes
      the array it receives in one `finally` (also when a reload cancels the load). Unit
      tests cancel the caller while a decrypt runs, and run out of memory during the
      conversion.
    - *Out of memory when a result is allocated.* `BackupService` allocates `Ready` and
      `PlanChanged` (through an internal `PlanResults` seam that tests can make fail)
      before it sets `handedOver` or `freshHandedOver`. In `read`, a failed allocation
      closes the plan and returns `NotEnoughMemory`. In `apply`, the new plan and its
      result are made before the old plan changes state, so a failed allocation leaves the
      old plan open (`isOpen` stays true: nothing was written) and wipes the rows just
      read.
    What stays best-effort is listed in [Sensitive data in memory](#sensitive-data-in-memory).
15. **Known limit: the 1000-account cap is checked only on export.**
    `BackupPayload.MAX_ACCOUNTS` (1000) is enforced when a backup is written
    (`ExportResult.AccountRejected` with `TOO_MANY_ACCOUNTS`) and when one is read (a file
    cannot hold more). Neither adding an account nor an import checks how many accounts the
    device will hold afterwards. An import into a device that already has accounts can
    therefore take it above 1000, and from then on every export fails with "A backup can
    hold at most 1000 accounts, and this device has more." No data is lost: the accounts
    stay on the device, and the export fails before it touches the chosen file. *Still to
    do (deferred):* the real fix (for example refusing an import that would go over the
    cap, or a format that can hold more) is not designed yet.

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
