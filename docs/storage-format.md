# Storage format of account secrets

This document specifies how Tumbler stores OTP secrets in its database (`lucaauth.db`,
Room schema version 2): the two secret formats, the data keys and their wrapping, the
associated data, and the key states. The encrypted backup file has its own, independent
format ([backup-format.md](backup-format.md)); it holds plaintext secrets under a
password-derived key and does not depend on anything here.

## What is active today

Schema version 2 adds the second format (envelope encryption) next to the original one.
In this version of the app:

- **Every new secret is sealed with the newest data key** (`keyVersion` n >= 1): adding an
  account, and inserting or replacing an account during a backup import.
- **Existing rows are converted at startup.** Before the account list or the backup reads
  anything, every legacy row (`keyVersion` 0) is resealed with the data key, one row at a
  time, while the screen shows "N of M"
  ([Migration of existing rows](#migration-of-existing-rows)). The legacy payload is kept
  in `legacySecretBlob` for one release.
- **Both formats can be read.** The account list, "next code" and the backup export and
  import read a row in whichever format its `keyVersion` names, and fall back to the kept
  legacy payload when a row's data key is lost
  ([Reading with the legacy copy](#reading-with-the-legacy-copy)).
- **The legacy Keystore key only decrypts.** Nothing encrypts with `lucaauth_secret_key_v1`
  any more, so a fresh install never creates it; on an upgraded device it stays, unused
  once every row is converted, for one release (no cleanup in this version).
- **Data keys are made in two cases only**: version 1, by the first conversion on an
  upgraded device or by the first add or import on a fresh install; and a later version,
  only when the user confirms "Start a new storage key" after the current key became
  unusable ([Key states](#key-states)).

Code: `data/crypto/` (`EnvelopeFormat`, `DataKeyCipher`, `SealedSecret`, `DataKey`,
`DataKeyHolder`, `WriteKeyStatus`, `KeystoreErrors`, `DataKeyWrapper`, `DataKeyStore`),
`domain/crypto/` (`KeyTemporarilyUnavailableException`, `NewStorageKeyRequiredException`),
`domain/repository/` (`StorageGate`), `data/db/` (`AccountEntity`, `DataKeyEntity`,
`DataKeyDao`, `DatabaseMigrations`), `data/repository/` (`SecretMigrator`,
`MigratingStorageGate`, `StoredSecretReader`, `LegacySecretReader`, `RoomDataKeyStore`),
`ui/accounts/` (`StorageViews`).

## Tables

`accounts` (one row per account; only the secret-related columns are listed):

| Column | Type | Meaning |
|---|---|---|
| `id` | TEXT, primary key | Account id, a random UUID (36 ASCII characters). |
| `secretBlob` | BLOB NOT NULL | The encrypted secret, in the format `keyVersion` names. |
| `keyVersion` | INTEGER NOT NULL DEFAULT 0 | 0: legacy format. n >= 1: sealed with data key n. Negative: invalid, never opened. |
| `legacySecretBlob` | BLOB, nullable | The legacy payload of a row that the startup migration resealed with a data key, byte for byte, kept for one release as a fallback. `NULL` for legacy rows and for rows written with a data key (added or imported). |

Issuer, account name, type, algorithm, digits, period, counter and creation time are
stored unencrypted, as before.

`data_keys` (one row per data key; rows are only inserted, never updated or deleted):

| Column | Type | Meaning |
|---|---|---|
| `version` | INTEGER, primary key | Data key version, >= 1. |
| `kekAlias` | TEXT NOT NULL | Android Keystore alias of the key that wrapped it. Must equal the alias derived from `version` (below). |
| `wrappedKey` | BLOB NOT NULL | The wrapped data key, exactly 62 bytes. |
| `createdAt` | INTEGER NOT NULL | Epoch milliseconds. Informational. |

Row rules:

- Replacing an account (backup import, "Replace") sets `secretBlob` (sealed with the
  newest data key) and `keyVersion` and sets `legacySecretBlob` to `NULL`, so the row keeps
  no copy of the replaced secret. The database file may: `PRAGMA secure_delete` is not set,
  so SQLite can leave the old ciphertext (still encrypted, under the key it was sealed
  with: the legacy Keystore key or a data key) in free pages of `lucaauth.db` and in its
  journal (`-wal`, or `-journal`) until those pages are overwritten. The same holds for a
  deleted account, and for the legacy payload a conversion rewrote.
- Deleting an account deletes its row and with it `legacySecretBlob`.
- The conversion of a legacy row is one `UPDATE` that applies only while the row is still
  `keyVersion` 0 and still holds exactly the payload that was converted, and only if the
  record of the new key version is stored
  ([Migration of existing rows](#migration-of-existing-rows)).
- Inserting a data key uses `INSERT OR IGNORE`: an existing version is never
  overwritten.
- "Next code" keeps its compare-and-set unchanged: it advances the counter only while the
  row still holds the counter and the exact `secretBlob` it read.

### Migration 1 -> 2

```sql
ALTER TABLE `accounts` ADD COLUMN `keyVersion` INTEGER NOT NULL DEFAULT 0;
ALTER TABLE `accounts` ADD COLUMN `legacySecretBlob` BLOB;
CREATE TABLE IF NOT EXISTS `data_keys` (`version` INTEGER NOT NULL, `kekAlias` TEXT NOT NULL,
  `wrappedKey` BLOB NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`version`));
```

Every existing row keeps its secret byte for byte and becomes `keyVersion` 0. No secret is
read, decrypted or rewritten and no Keystore key is touched. There is no destructive
fallback: a missing migration makes opening the database fail. `DatabaseMigrationsTest`
(JVM) checks that this SQL builds exactly the exported `app/schemas/.../2.json`;
`MigrationTest` (device) migrates a version 1 file and validates it with Room.

The migration is one-way. There is no migration from 2 to 1 and no destructive fallback,
so a build with schema version 1, installed over this one (a downgrade), fails to open the
database. Downgrading is not supported; export a backup first.

## Format 0: legacy

`keyVersion` 0. AES-256-GCM in the Android Keystore with the key
`lucaauth_secret_key_v1` (`AesGcmSecretCipher`, `AndroidKeystoreKeyProvider`):

```
offset  size  field
0       1     version = 0x01
1       1     IV length = 0x0c
2       12    IV (generated by the Keystore for each encryption)
14      n     ciphertext (n = secret length, at most 65536)
14+n    16    GCM tag (128 bits)
```

Associated data: the account id as UTF-8. Every version of the app before the data key
wrote this format; this version only reads it (legacy rows not converted yet, and the
`legacySecretBlob` copies).

## Format n >= 1: sealed with a data key

`keyVersion` n. AES-256-GCM in software (`javax.crypto`, `AES/GCM/NoPadding`) with data
key n (`DataKeyCipher`):

```
offset  size  field
0       1     version = 0x02
1       1     nonce length = 0x0c
2       12    nonce (12 random bytes from SecureRandom for each seal)
14      n     ciphertext (1 <= n <= 65536)
14+n    16    GCM tag (128 bits)
```

Length rule: the secret is 1 to 65536 bytes, so a payload is 31 to 65566 bytes. Sealing
refuses an empty or longer secret. Before decrypting, the reader refuses a payload outside
31 to 65566 bytes ("Payload too short" or "Payload too large"; a 30-byte payload, which
would hold an empty secret, is too short even with a valid tag), a version byte other than
`0x02`, or a nonce length other than `0x0c`.

Associated data (`EnvelopeFormat.secretAssociatedData`):

```
"TumblerSecret" (13 bytes, ASCII) || 0x00 || n (4 bytes, big-endian) || account id (UTF-8)
```

The id is encoded strictly: an id with an unpaired surrogate, or longer than 255 bytes in
UTF-8, is refused instead of being replaced or truncated. Example, n = 1, id `acc-1`:

```
54756d626c6572536563726574 00 00000001 6163632d31
```

The associated data binds the purpose, the key version and the account: a payload opens
only for the row and version it was sealed for, and a legacy payload labelled with a data
key version (or the reverse) is refused by its version byte.

Nonces are random, so a data key must not seal more than 2^32 secrets
(NIST SP 800-38D, 8.3). Each account change seals once; the app stays many orders of
magnitude below that.

## Data keys

A data key is 32 bytes from `SecureRandom`. It is stored only wrapped, in `data_keys`,
under its own Keystore key:

| Data key version | Wrapping Keystore alias |
|---|---|
| 1 | `lucaauth_wrap_key_v1` |
| n >= 2 | `lucaauth_wrap_key_v1_k<n>` (for example `lucaauth_wrap_key_v1_k2`) |

The legacy key `lucaauth_secret_key_v1` never wraps a data key. Each wrapping key is an
AES-256 Android Keystore key with the same properties as the legacy key: non-exportable,
GCM, no padding, randomized encryption, StrongBox requested with TEE fallback, no user
authentication.

Wrapped data key (an `AesGcmSecretCipher` payload, `DataKeyWrapper`), exactly 62 bytes:

```
offset  size  field
0       1     version = 0x01
1       1     IV length = 0x0c
2       12    IV (generated by the Keystore)
14      32    encrypted data key
46      16    GCM tag
```

Associated data (`EnvelopeFormat.wrapAssociatedData`):

```
"TumblerDataKey" (14 bytes, ASCII) || 0x00 || n (4 bytes, big-endian) || alias (UTF-8)
```

Example, n = 1, alias `lucaauth_wrap_key_v1`:

```
54756d626c6572446174614b6579 00 00000001 6c756361617574685f777261705f6b65795f7631
```

Before any Keystore call, a record is refused if its version is below 1, its alias is not
the one derived from its version, or `wrappedKey` is not exactly 62 bytes starting with
`01 0c`. An unwrapped key that is not 32 bytes is wiped and refused.

### Creating a data key

`DataKeyHolder.prepareWriteKey` (called by adding an account, by every backup write
transaction and by the migration) and `DataKeyHolder.startNewKey` (called only by the
confirmed "Start a new storage key"). Both run outside any database transaction:

1. Generate 32 random bytes; wrap them under the alias of the new version (the Keystore
   key is created if that alias has none); unwrap the result and compare it with the
   original in constant time. Only a key that passes this check is stored.
2. Store the record with `INSERT OR IGNORE`. If another record already holds that
   version, the new key is wiped and the stored record is used. A stored record is never
   overwritten.
3. The new version is one above the highest version among both `data_keys` and the
   `keyVersion` of every row, so it never reuses the number (or alias) of a key that rows
   still name.
4. At most one key is made per version in a process: concurrent writers share one
   creation, and a writer that finds the version already held in memory (because another
   writer stored it after this one read the table) uses the stored record instead of
   generating another key.

When a key is made:

| Situation | `prepareWriteKey` (add, import, migration) | `startNewKey` (confirmed by the user) |
|---|---|---|
| No record, no row names a data key (fresh install, or an upgrade before the first conversion) | Makes version 1 | Makes version 1 |
| The newest record unwraps | Returns its version | Returns its version; makes nothing |
| The newest record cannot be used for good (wrapping key gone or permanently invalidated, record malformed or not authentic), or rows name a version that has no record | Throws `NewStorageKeyRequiredException`; makes nothing | Makes the next version (rule 3 above) |
| The newest record failed in a way that may be transient | Throws `KeyTemporarilyUnavailableException`; makes nothing | Throws it too; makes nothing |

So after a key is lost nothing is made on its own: writes are refused until the user
starts a new key, and a failure that may be transient never leads to a new key.

Writing a row then calls `DataKeyHolder.writeKey(version)` inside the writing transaction:
it reads that version's record in the same transaction and returns the key only if it was
already unwrapped from that same record (by `prepareWriteKey`). It never unwraps and never
calls the Keystore. It refuses to seal if the record is not there, so no row is committed
without the record of its key.

Version rules, in short: versions start at 1 and only grow; a new version is above every
record and every row's `keyVersion`, so no number or alias is reused, also when rows name a
key whose record is gone; records are never updated or deleted; new secrets are sealed with
the newest record, whose key `prepareWriteKey` checked before the transaction.

## Key states

The state of data key n in a running process (`DataKeyHolder`):

| State | Condition | Reading a row with `keyVersion` n | Writing, when n is the newest record |
|---|---|---|---|
| Absent | No record for n | `KeyUnavailableException`; not cached, so a record that appears later is found. A row with a kept legacy copy is read from that copy | No row names a data key: creates version 1. Rows name one: refused (`NewStorageKeyRequiredException`) until a new key is started |
| Not yet unwrapped | Record present, not used yet in this process | The first reader reads the record and starts one unwrap; concurrent readers share it | `prepareWriteKey` unwraps it, outside the transaction |
| Ready | Unwrapped | Uses the key in memory: no database or Keystore call | Uses it |
| Lost | Record present, its wrapping key is gone from the Keystore, or permanently invalidated (`KeyPermanentlyInvalidatedException`) | `KeyUnavailableException`, cached; a row with a kept legacy copy is read from that copy | Refused (`NewStorageKeyRequiredException`); creates nothing until the user confirms "Start a new storage key", which makes the next version under a new alias. The old record and its rows stay |
| Unreadable | Record malformed, unwraps to a key that is not 32 bytes, or does not authenticate (`AEADBadTagException`) | `SecretCipherException`, cached; a row with a kept legacy copy is read from that copy | As lost |
| Temporarily unavailable | The wrapping key exists, but the Keystore call failed otherwise (`KeyStoreException`, `ProviderException`, `IOException`, `InvalidKeyException` and the like, reported as "Decryption failed") | `KeyTemporarilyUnavailableException` to every reader of that unwrap; **not** cached, the next read unwraps again; the legacy copy is **not** used | `KeyTemporarilyUnavailableException` (retryable); creates nothing, also when the user confirms a new key |
| Wiped | `DataKeyHolder.wipe()` ran (the app never calls it) | The next read unwraps again | Unwraps again |

Cached means: later reads of n get the same failure with no database or Keystore call,
until `DataKeyHolder.clearFailures()` or `wipe()`. The account list's reload and every run
of the startup gate after the first ("Try again", "Start a new storage key") call
`clearFailures()`. The app's keys do not require user authentication, so Android does not
invalidate them when the screen lock changes; `KeyPermanentlyInvalidatedException` is
handled as lost in case a device raises it anyway (covered by JVM tests only).

Rules that hold in every state:

- Reading never creates a data key or a Keystore key.
- While a data key is ready, it is not unwrapped again. It is unwrapped once per process,
  and again only after `wipe()`, after a temporary failure, after `clearFailures()`
  forgot a cached failure, or when its record changed. At any time there is at most one
  unwrap of a version in progress, shared by every reader and writer that waits for it. A
  reader that is cancelled while waiting does not cancel the shared unwrap; the key is
  kept for the next reader.
- Only the definite failures above (lost, unreadable) are cached. Temporary failures and
  failures that are not cipher errors (for example running out of memory) are not cached.
- `wipe()` forgets cached failures too. A read that already holds a key when `wipe()` runs
  fails once ("Data key unavailable"); the next read unwraps again.
- Unreadable rows are never deleted or rewritten automatically.
- `wipe()` zeroes each key only once no operation is using it, and every key and every
  new key still being made, even if one of them cannot be zeroed. Called from inside a
  key's own use, that key is kept (for a later `wipe()`), all others are zeroed, and the
  call then throws.
- The holder's own coroutines do Keystore work only. Records are read in the caller's
  coroutine (inside a transaction, in that transaction), and no lock is held across a
  database call, so waiting for an unwrap cannot deadlock against an open transaction.

- No unwrap and no Keystore call runs inside a database transaction. Keys are resolved
  before it opens (`prepareWriteKey`, `key`); inside it only `writeKey` and `readyKey` are
  used, which use keys already in memory. Rows that cannot be opened with a key in memory
  (legacy rows, legacy copies) are decrypted before the transaction and checked again
  inside it: if the row changed meanwhile, the write fails and nothing is committed.

A ready data key stays in memory for the life of the process: the app never calls
`wipe()`. Copies made by the JCA (`SecretKeySpec`, the cipher's key schedule) cannot be
wiped. What this means for the threat model is described in [SECURITY.md](../SECURITY.md).

## Migration of existing rows

`SecretMigrator`, run by `MigratingStorageGate` once per process at startup, in a scope
that lives as long as the process (leaving the screen does not stop it):

1. Read the ids and payloads of every row with `keyVersion` 0 (oldest first). Their number
   M is the total of the progress; if it is 0, nothing else happens (no key is made).
2. `prepareWriteKey()`, outside any transaction. On an upgraded device this makes version
   1 (wrap, check unwrap, `INSERT OR IGNORE`), so the record is committed before any row
   names it. If a new key is required or the Keystore fails, the run stops here and every
   row stays as it is.
3. For each row, with no transaction open:
   1. decrypt the legacy payload with the legacy Keystore key (one Keystore call);
   2. seal the plaintext with the data key in software (fresh nonce; associated data:
      purpose, key version, account id);
   3. open the new payload again and compare it with the plaintext in constant time;
   4. one compare-and-set `UPDATE`: set `secretBlob` to the new payload, `keyVersion` to
      the data key version and `legacySecretBlob` to the old payload, only where the row is
      still `keyVersion` 0, still holds exactly the old payload, and a record of that
      version exists;
   5. wipe the plaintext and the reopened copy, whatever happened.

   The progress "N of M" advances after each row.

Outcomes per row: converted; changed meanwhile (the `UPDATE` matched nothing because the
row was deleted, replaced by an import or converted by another run: it is left as it is
and, if still a legacy row, tried again on the next start; the `UPDATE` changes only the
secret columns, so a HOTP counter that moved meanwhile is kept);
unreadable (the legacy payload does not open: left as it is, shown as unreadable); retry
later (a Keystore error that may be transient). The run stops early, leaving the remaining
rows for the next start, if the legacy key is gone (every remaining row is counted as
unreadable), if the check in step 3.3 fails, or on a database error.

Crash safety. A process killed at any point leaves each row either untouched or fully
converted, because each conversion is one statement. A key stored before the kill is found
by the next run and used; no second key is made. Concurrent writers (add, import, delete,
"next code") are safe: the `UPDATE` refuses a row that changed after it was read, and the
HOTP compare-and-set refuses a counter advance on a row whose payload changed. Two runs at
once convert each row once and share one key.

Progress. Only rows still at `keyVersion` 0 are counted, so a run after a kill shows the
rows left. While M > 0 the accounts screen shows a progress screen instead of the list,
hides the add button and the backup menu, and TalkBack reads "N of M accounts updated".
The backup store waits for the run before every read and write transaction, and an add
started from an add screen restored after process death waits too.

After the migration, a cold start costs a query for legacy rows, a read of the key records
and one unwrap of the newest key (to tell the UI whether writes work). While that key is
ready, showing the list, "next code", export and import make no further Keystore call.
Rows that can only be read through their legacy copy (below) cost one Keystore call each
time they are read.

## Reading with the legacy copy

A row converted by the migration keeps its legacy payload in `legacySecretBlob`. If its
data key is absent, lost or unreadable (a definite failure), the row is opened from that
copy with the legacy Keystore key instead. A failure that may be transient is reported as
such and never falls back. If the legacy key is gone too, the row is unreadable. The
fallback never rewrites the row.

## Storage states in the UI

`MigratingStorageGate` ends each run with a write status from `writeKeyStatus()` (never
creates a key):

| Status | When | What the accounts screen shows |
|---|---|---|
| Available | Newest key unwraps, or no key yet and no row names one | Nothing; if some accounts cannot be read for good, "N accounts cannot be read" and that only a backup brings them back |
| New key required | Newest key lost or unreadable, or rows name a key without a record | "A new storage key is needed" (with the number of unreadable accounts, if any) and the "Start a new storage key" action, which asks for confirmation. Adding and importing are refused with their own message; deleting works |
| Temporarily unavailable | The key could not be checked this time | "Try again", which runs the gate again |

A run that left rows for later because of a transient error also shows "Try again". An
account that failed to open only this time shows "Code temporarily unavailable" instead of
"Code unavailable". Unreadable accounts are never deleted by the app; the user can delete
them and import a backup that contains them.

## Tests

- `DataKeyCipherTest`: AES-256-GCM known answers (McGrew and Viega, GCM specification,
  test cases 14 to 16) through the same seal and open code, and test case 13 (an empty
  secret) refused in both directions; the key version a sealed secret carries; round trip; changing any
  byte; nonce, ciphertext and tag; account id, key version and purpose; truncated,
  oversized and misframed payloads; key and nonce lengths; 10 000 seals with distinct
  nonces; a wiped key.
- `EnvelopeFormatTest`: layouts, the examples above, strict UTF-8 and length limits,
  derived aliases, the 62-byte check.
- `DataKeyHolderTest`: every key state; one unwrap for concurrent first readers and none
  afterwards; temporary Keystore errors retried (also when shared by concurrent readers)
  and definite failures cached; cancellation; wipe during use, during an unwrap and during
  a creation, and from inside a key's use; creation, sharing, cancellation (also after the
  insert), rollback of a record, a conflicting record, a writer that read the table before
  another writer's insert, version numbering above orphaned rows, the retry limits; no key
  made for a lost, unreadable or orphaned key until `startNewKey`, never one for a
  transient error, `writeKeyStatus` never creating anything, `writeKey` never unwrapping.
- `SecretMigratorTest` (JVM, real AES-GCM legacy cipher, counting fake Keystore,
  compare-and-set fake DAO): conversion and counts, the record committed before the first
  row, a kill between rows and after the key was stored (resumed with the same key), a
  cancellation after a decrypt (plaintext wiped), rows changed meanwhile, a stale "next
  code", two migrators at once, unreadable, transient and lost-legacy-key rows, a failed
  check, a database error, orphaned rows, a lost wrapping key.
- `MigratingStorageGateTest` (JVM): every storage state, "N of M" while rows convert, one
  run per process, waiting callers, the cost of a cold start (one unwrap, nothing made),
  the confirmed new key (old record and rows untouched, no number reused), transient
  errors (no key made, retry recovers).
- `DualReadTest`: both repositories reading both formats side by side, "next code" on a
  data-key row (RFC 4226 vectors), lost keys, mislabelled rows, the legacy-copy fallback
  (and none on a transient error), new writes sealed with one data key, and no Keystore
  call inside any transaction (counted by an auditing transaction runner).
- `AccountListStorageTest` (JVM): the list waits for the gate, the progress, the banners,
  temporary rows, the new-key and retry actions, adds that wait or are refused.
- `EnvelopeMigrationInstrumentedTest` (device; written and compiled, not run on a device
  yet): the migration under WAL and `TRUNCATE` journaling, a kill and resume, a data key
  resolved inside a real Room `withTransaction` (both journal modes), the legacy-copy
  fallback, and a lost wrapping key followed by a confirmed new key.
- `DatabaseMigrationsTest` (JVM) and `MigrationTest`, `SchemaVersion2InstrumentedTest`
  (device): the migration and the version 2 SQL.
