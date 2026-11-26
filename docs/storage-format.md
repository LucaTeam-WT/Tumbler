# Storage format of account secrets

This document specifies how Tumbler stores OTP secrets in its database (`lucaauth.db`,
Room schema version 2): the two secret formats, the data keys and their wrapping, the
associated data, and the key states. The encrypted backup file has its own, independent
format ([backup-format.md](backup-format.md)); it holds plaintext secrets under a
password-derived key and does not depend on anything here.

## What is active today

Schema version 2 adds the second format (envelope encryption) next to the original one.
In this version of the app:

- **Every secret is written in the legacy format** (`keyVersion` 0): adding an account,
  importing a backup and replacing an account during an import all use the Keystore key
  directly, as before.
- **Both formats can be read.** The account list, "next code" and the backup export and
  import read a row in whichever format its `keyVersion` names.
- **No data key exists.** The `data_keys` table is empty, nothing in the app creates a
  data key or the wrapping Keystore key `lucaauth_wrap_key_v1`, and no existing row is
  converted. Rows with `keyVersion` >= 1 exist only in tests.

Converting existing rows (with progress UI), writing new rows in the new format and the
"accounts unreadable" banner are planned for a later version; see
[SECURITY.md](../SECURITY.md#known-limitations-and-planned-work).

Code: `data/crypto/` (`EnvelopeFormat`, `DataKeyCipher`, `SealedSecret`, `DataKey`,
`DataKeyHolder`, `DataKeyWrapper`, `DataKeyStore`), `domain/crypto/`
(`KeyTemporarilyUnavailableException`), `data/db/` (`AccountEntity`, `DataKeyEntity`,
`DataKeyDao`, `DatabaseMigrations`), `data/repository/` (`StoredSecretReader`,
`RoomDataKeyStore`).

## Tables

`accounts` (one row per account; only the secret-related columns are listed):

| Column | Type | Meaning |
|---|---|---|
| `id` | TEXT, primary key | Account id, a random UUID (36 ASCII characters). |
| `secretBlob` | BLOB NOT NULL | The encrypted secret, in the format `keyVersion` names. |
| `keyVersion` | INTEGER NOT NULL DEFAULT 0 | 0: legacy format. n >= 1: sealed with data key n. Negative: invalid, never opened. |
| `legacySecretBlob` | BLOB, nullable | Reserved for the conversion: the legacy payload of a row whose secret was resealed with a data key, kept for one release. Always `NULL` today. |

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

- Replacing an account (backup import, "Replace") sets `secretBlob` and `keyVersion` and
  sets `legacySecretBlob` to `NULL`, so the row keeps no copy of the replaced secret. The
  database file may: `PRAGMA secure_delete` is not set, so SQLite can leave the old
  ciphertext (still encrypted, under the key it was sealed with, today the legacy Keystore
  key) in free pages of `lucaauth.db` and in its journal (`-wal`, or `-journal`) until those
  pages are overwritten. The same holds for a deleted account.
- Deleting an account deletes its row and with it `legacySecretBlob`.
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

Associated data: the account id as UTF-8. This is the only format the app has ever
written.

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

`DataKeyHolder.prepareWriteKey` (not called by the app yet). It must run outside any
database transaction:

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

A new key is made only when there is no record, or when the newest record's wrapping key is
gone. If the newest key exists but cannot be used (temporarily or for good, see "Key
states"), `prepareWriteKey` throws and makes nothing.

Writing a row then calls `DataKeyHolder.writeKey(version)` inside the writing transaction:
it reads that version's record in the same transaction and refuses to seal if it is not
there, so no row is committed without the record of its key.

## Key states

The state of data key n in a running process (`DataKeyHolder`):

| State | Condition | Reading a row with `keyVersion` n | Writing (once enabled) |
|---|---|---|---|
| Absent | No record for n | `KeyUnavailableException`; not cached, so a record that appears later is found | Creates the next version |
| Not yet unwrapped | Record present, not used yet in this process | The first reader reads the record and starts one unwrap; concurrent readers share it | Unwraps, then uses it |
| Ready | Unwrapped | Uses the key in memory: no database or Keystore call | Uses it |
| Lost | Record present, its wrapping key is gone from the Keystore | `KeyUnavailableException`, cached | Creates a new version under a new alias; the old record and its rows stay (unreadable) |
| Unreadable | Record malformed, unwraps to a key that is not 32 bytes, or does not authenticate (`AEADBadTagException`) | `SecretCipherException`, cached | Refuses to write; creates nothing |
| Temporarily unavailable | The wrapping key exists, but the Keystore call failed otherwise (`KeyStoreException`, `ProviderException`, `IOException`, `InvalidKeyException` and the like, reported as "Decryption failed") | `KeyTemporarilyUnavailableException` to every reader of that unwrap; **not** cached, the next read unwraps again | `KeyTemporarilyUnavailableException` (retryable); creates nothing |
| Wiped | `DataKeyHolder.wipe()` ran | The next read unwraps again | Unwraps again |

Cached means: later reads of n get the same failure with no database or Keystore call,
until `DataKeyHolder.clearFailures()` or `wipe()`. The account list's reload calls
`clearFailures()` (through `AccountRepository.forgetCachedReadFailures`). A permanently
invalidated wrapping key (`KeyPermanentlyInvalidatedException`, an `InvalidKeyException`)
is treated as temporarily unavailable: it is never cached and never replaced by a new key
automatically.

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

A ready data key stays in memory for the life of the process. Copies made by the JCA
(`SecretKeySpec`, the cipher's key schedule) cannot be wiped. What this means for the
threat model is described in [SECURITY.md](../SECURITY.md).

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
  another writer's insert, version numbering above orphaned rows, the retry limits.
- `DualReadTest`: both repositories reading both formats side by side, "next code" on a
  data-key row (RFC 4226 vectors), lost keys, mislabelled rows, and that writes stay in
  the legacy format.
- `DatabaseMigrationsTest` (JVM) and `MigrationTest`, `SchemaVersion2InstrumentedTest`
  (device): the migration and the version 2 SQL.
