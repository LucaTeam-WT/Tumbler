# Security Policy

> **Tumbler is beta software and has not been audited.** Do not store accounts whose
> loss or compromise would be critical until the planned items under
> [Known limitations](#known-limitations-and-planned-work) land. Keep recovery codes
> for every account elsewhere: the only way to move accounts is the app's encrypted
> export, which is only as safe as the password you give it.

Every statement below describes the code as it is today. Planned work is listed
separately and is not a promise of a date.

## What the app protects

- TOTP/HOTP secrets stored on the device.
- Account labels (issuer and account name) stored on the device; see the limitation
  below, they are **not** encrypted.

## How it protects them today

**Storage.** Accounts live in a Room (SQLite) database named `lucaauth.db` in the
app-private storage (`TumblerDatabase`). The app's own code writes no files in its storage
other than the Room database and the journal files SQLite keeps next to it: `-wal` and
`-shm` in write-ahead logging, which Room uses by default, or `-journal` in the rollback
journal (`TRUNCATE`) mode that Room uses instead on low-RAM devices. It uses no
`SharedPreferences` or external storage. The only other file it writes is an encrypted
export, to a document the user creates with the system file picker (see
[Encrypted export and import](#encrypted-export-and-import)). (Android and the libraries the app uses may keep their own files
in app-private storage, such as caches or the Compose/ART profile data; the app's code
does not put secrets there.)

The database is at schema version 2 ([docs/storage-format.md](docs/storage-format.md)).
What is stored where:

| What | Where | Protected by |
|---|---|---|
| Each account's secret | `accounts.secretBlob` | Sealed with a data key (`keyVersion` n >= 1); a row not converted yet: the legacy Keystore key (`keyVersion` 0) |
| The secret as it was before the conversion | `accounts.legacySecretBlob` (rows the startup migration converted; kept for one release) | The legacy Keystore key `lucaauth_secret_key_v1` |
| Each data key (32 bytes) | `data_keys.wrappedKey`, wrapped | Its own Keystore key `lucaauth_wrap_key_v1` (version 1) or `lucaauth_wrap_key_v1_k<n>` |
| Labels, type, algorithm, digits, period, counter, creation time | `accounts` | Nothing (plaintext) |
| Keystore keys | Android Keystore | Non-exportable; StrongBox requested, TEE fallback |

The schema migration from version 1 only adds these columns and the table. At the first
start of this version the app then converts every legacy row: it decrypts the secret with
the legacy key, seals it with the data key and writes the new payload in one
compare-and-set statement that also moves the old payload into `legacySecretBlob`; no
SQLite transaction is held while the Keystore is used. The old payload stays there (and
the legacy key in the Keystore) for one release: reads fall back to it when a data key is
lost, and a later version could undo the conversion from it (this version has no code that
does; it also removes neither). Replacing an account during an import clears its
`legacySecretBlob`; deleting an account deletes its row. Neither erases the old
ciphertext from the file: `PRAGMA secure_delete` is not set, so SQLite may leave a
replaced, deleted or converted secret (still encrypted, under the key it was sealed with)
in free pages of the database and in its journal files until they are overwritten.

The schema change is one-way: there is no migration back to version 1 and no destructive
fallback, so an older build installed over this one fails to open the database.
Downgrading is not supported; export a backup first.

**Encryption (envelope).** Only the OTP secret is encrypted, per account. Every secret the
app writes (adding an account, importing or replacing one) is sealed in software with the
newest data key: AES-256-GCM (`AES/GCM/NoPadding`, 128-bit tag) with a fresh random
12-byte nonce from `SecureRandom` per seal, and the purpose, the key version and the
account id as associated data, so a payload copied into another row or labelled with
another key version fails to open (`DataKeyCipher`). The data key is 32 bytes from
`SecureRandom`, stored only wrapped (AES-GCM) by its own non-exportable Keystore key; the
wrap is checked by unwrapping it before the key is stored, and a stored wrapped key is
never overwritten (`DataKeyHolder`).

The legacy format, which every earlier version wrote, is only read: AES-GCM in the
Keystore under `lucaauth_secret_key_v1` with the account id as associated data
(`AesGcmSecretCipher`). It is read for rows not converted yet and, when a row's data key
is lost, from the row's kept legacy copy.

No Keystore call and no unwrap runs inside a database transaction: keys are unwrapped,
and rows that need the legacy key are decrypted, before the transaction opens; inside it
only keys already in memory are used (checked by a JVM test that counts Keystore calls per
transaction).

**Key states.** Reading never creates a key. A Keystore error that may be temporary is not
remembered and never leads to a new key: the screen offers "Try again". A wrapping key
that is gone or permanently invalidated, or a stored wrapped key that does not
authenticate, makes that data key unusable: accounts that only it can open show as
unreadable. If that key is the newest one, or accounts name a key whose stored record is
gone, adding and importing are refused until the user confirms "Start a new
storage key" in a dialog. That makes a new data key with a new version number and Keystore
alias (no number is reused); the old wrapped key and the unreadable accounts are kept as
they are and can only come back from a backup. The app never deletes an unreadable account
on its own.

**Keys.** Non-exportable 256-bit AES keys in the Android Keystore
(`AndroidKeystoreKeyProvider`): `lucaauth_wrap_key_v1` wraps data key 1, and
`lucaauth_wrap_key_v1_k<n>` wraps data key n if the user ever starts a new one. Each is
created when its data key is made, requesting StrongBox on API 28+ and falling back to the
TEE (secure hardware) if StrongBox generation fails. The legacy key
`lucaauth_secret_key_v1` is no longer created (nothing encrypts with it); on an upgraded
device it stays for one release to read the kept legacy copies. Whether a given phone
actually backs a key with hardware is decided by the device; the app does not check or
report it. The keys do **not** require user authentication
(`setUserAuthenticationRequired(false)`) or an unlocked device (`setUnlockedDeviceRequired`
is not used), so any code running inside the app's process can use them. Decryption and
unwrapping never create a key; a missing key surfaces as an error, not as silent data
loss.

**Plaintext in memory.** The account list view model decrypts every account's secret
when the list loads. A TOTP account's secret is copied into its code generator, which
stays in memory for as long as the screen's view model lives, to compute codes each
second. A HOTP account keeps no secret: the list decrypts it only to check that the row is
readable. The view model wipes each decrypted array in one `finally` as soon as the row
was checked (and, for TOTP, the generator made), also when that fails or a reload cancels
the load. If the load is cancelled while the repository is still decrypting, or the row
holds invalid values, the repository wipes the array itself and never hands it over. Not
wiped: the copy each TOTP generator keeps, which lives until the generator is dropped
(when the list loads again, also by a load that a reload cancelled after it had made some
generators) and is then left to the garbage collector; and the key copy that the JCA
`SecretKeySpec` makes each time a code is generated (every second for every TOTP account
while the list is shown). Generated codes are held
in UI state only. Secrets are not placed in saved instance state. Plaintext is not
guaranteed to be wiped from memory (the JVM may copy it). "Next code" on a HOTP account
decrypts that account's secret again, in software with the data key inside its database
transaction (or, for a row that needs the legacy key, with the Keystore just before the
transaction, kept until the transaction ends), and wipes that array on every path; the
copies the HOTP generator and the JCA HMAC key make are left to the garbage collector. The
startup migration holds one account's plaintext at a time, from its legacy decrypt until
its new payload is written, and wipes it (and the copy decrypted again to check the new
payload) on every path.

**The data key in memory.** Once unwrapped, the data key stays in the app's memory for as
long as the process runs: the app never wipes it. Anyone who can read the running app's
memory gets that key and with it every secret sealed with it (see the threat model).

**Plaintext on disk.** Issuer, account name, OTP type, algorithm, digits, period,
counter and creation time are stored unencrypted in the database, and the pasted
`otpauth://` link is parsed in memory and not stored.

**System backup.** Cloud backup and device-to-device transfer are disabled by design:
`android:allowBackup="false"`, `res/xml/data_extraction_rules.xml` (API 31+) and
`res/xml/backup_rules.xml` (API 30 and lower) exclude all domains. A restored database
would be useless anyway, because the Keystore keys that wrap the data keys (and the legacy
key) never leave the device.

### Encrypted export and import

The accounts screen's menu exports every account into one file and imports such a file
(`BackupService`, `ui/backup/`; the format is specified in
[docs/backup-format.md](docs/backup-format.md)).

- **As strong as its password.** The file is encrypted with AES-256-GCM under a key that
  scrypt derives from the password (N = 2^16, r = 8, p = 2 by default; random salt and
  nonce per file). The password must have 12 to 1024 characters after NFC normalization
  and is entered twice. Anyone who has the file can try passwords offline, so a short or
  guessable password makes the backup weak. The header (format version, scrypt
  parameters, salt, nonce) is readable without the password, and the file size reveals
  roughly how many accounts it holds.
- **The password is not stored** anywhere and cannot be recovered; without it the file
  cannot be restored. The app keeps it in memory only for the export or import: the
  password fields are not in saved instance state or a `SavedStateHandle`, so they do not
  survive process death. The text reaches the backup code as a `CharArray` copy, which is
  wiped after use, on cancel and when the screen's view model is cleared. The password
  fields are not reported to autofill (`ContentDataType.None`): Compose does not tell the
  autofill framework when they gain focus or change. Each field is still in the view
  structure Compose gives the platform, with the autofill type None, the hint "password",
  the sensitive flag and its current text as its value (checked in the Compose 1.12.1
  sources). Whether the platform includes that value in a save request, and so whether a
  password manager stays quiet, is not verified on a device.
- **Wrong password and damaged file look the same**, by design: AES-GCM cannot tell them
  apart, and the app shows one message ("Wrong password or damaged file") for both.
- **The file is the user's responsibility.** It is written through the system file picker
  (Storage Access Framework, no storage permission) to wherever the user chooses, which
  may be a folder that another app or a cloud service syncs. The app does not keep track
  of it or delete it later. It opens the chosen file for writing only once the encrypted
  backup is complete in memory, and only if the export was not cancelled meanwhile (for
  example when the app is closed while scrypt runs), so an export that fails or is
  cancelled before then leaves the file as it was. A cancellation that lands while the
  file is being opened or written deletes it like a failed write. If the write then fails
  halfway, the app deletes the incomplete file (if the
  storage provider refuses, it empties it and asks the user to delete it). Android cannot
  replace a file atomically, so if the chosen file already held something, that content
  is lost in this case. A file that cannot be opened, or that arrives after the app was
  restarted, is deleted only if it is empty.
- **Import never writes without confirmation.** It shows how many new accounts there
  are and each conflict by label (never a secret), offers only the choices that are safe for it with
  "skip" preselected, and writes everything in one database transaction after a final
  confirmation. If the accounts changed in a way that changes the plan, it writes nothing
  and asks again.
- **Plaintext in memory.** An export decrypts every secret to build the backup and wipes
  those arrays as soon as the backup is encrypted, before it opens the file; an
  import holds the backup's decrypted secrets, and the device's secrets read for the
  plan, until it is written or cancelled. The code wipes these arrays and the passwords
  after use on every path, including cancellation and running out of memory
  (best-effort; see the limitations below).

**Network and telemetry.** The app declares no `INTERNET` permission (and no other
permission), sets `android:usesCleartextTraffic="false"`, contains no analytics or
crash-reporting SDK, and the app's own code contains no logging calls.

**Exported components.** Only the launcher activity (`MainActivity`) is exported. The
`androidx.profileinstaller` library adds one more exported component to the merged
release manifest, `ProfileInstallReceiver`, protected by the `android.permission.DUMP`
permission, which ordinary apps cannot hold (it is granted to the adb shell). It is not
under the app's control without a lint-failing manifest override, so it is left as is.
All other merged components (`InitializationProvider`, Room's
`MultiInstanceInvalidationService`) are not exported. The only permission in the merged
manifest is a signature-level one that AndroidX adds for its own receivers; there is no
`INTERNET` permission.

**Clipboard.** Copying a code marks the clip as sensitive on Android 13+, which hides
it in the system preview. It is not cleared automatically.

## Threat model

| In scope | Partially mitigated | Out of scope |
|---|---|---|
| Another app on the same phone reading our files: app-private storage, secrets encrypted with a key other apps cannot use | Malware without root reading app storage: it cannot read secrets, but can read labels, and can read the clipboard after a copy | A rooted or otherwise compromised device |
| A lost or stolen **locked** phone: secrets are sealed with a data key that is stored only wrapped by a hardware-backed-where-available Keystore key, so a copy of the database file alone does not reveal them (the app does not itself guarantee device storage encryption; that is up to the OS and device) | A stolen phone that is unlocked or whose screen lock is weak: there is no app lock yet, so the app opens straight to the codes | A malicious or modified OS |
| A user's backup leaking: no cloud backup or transfer contains app data | Shoulder surfing: codes are always visible (no hiding, no screen-capture protection yet) | Physical attacks on a running, unlocked phone (including memory extraction). **With the data key, a memory dump of the running app yields every stored secret**, not only those decrypted at that moment (see below) |
| A damaged or modified export file: AES-GCM authentication rejects it before anything is imported | An exported file that leaks: encrypted, but only as strong as its password, which can be guessed offline | Where the user keeps an exported file and who else can reach it |
| Secrets leaking through logs, network or telemetry: none exist | A code lingering in the clipboard: marked sensitive on Android 13+, not auto-cleared | Screen recording or accessibility tooling run by the user themselves |

## Known limitations and planned work

- **No app lock yet.** Anyone who can unlock the phone can open the app and see codes.
- **Backup wiping is best-effort.** Some copies cannot be wiped: the key copies that the
  HOTP generator, `SecretKeySpec` and `Cipher` make; Bouncy Castle's PBKDF2/HMAC state;
  the `String`s that Compose's text fields and NFC normalization create from a password;
  and the decrypted labels. The keyboard app sees every character typed. Details are in
  [docs/backup-format.md](docs/backup-format.md#sensitive-data-in-memory).
- **No password strength check** beyond the 12-character minimum.
- **No screen-capture protection yet.** `FLAG_SECURE` is not set; screenshots and
  recents thumbnails can show codes, the import review (account labels) and a password
  that the user chose to show.
- **Backup device tests ran on one phone only** (Android 17). A first run of all
  instrumented tests had three failures: two Compose UI test mistakes, and the
  1000-account export and import, which ran past its time limit. After the fixes and the
  following review fixes, the whole suite ran again there: 111 tests, no failures, none
  skipped. On that phone the Keystore key is in StrongBox and each Keystore operation
  takes about 0.33 s per account, so export and import take seconds per ten accounts
  (about 5.5 minutes each for 1000; figures in
  [docs/backup-format.md](docs/backup-format.md), item 11). The time to show the account
  list was not measured. With the schema version 2 changes the whole instrumented suite
  ran again (116 tests, no failures, none skipped); the review fixes made after that run
  are covered by JVM tests only so far. No round trip has been done on a release build.
- **Accessibility services receive the backup password.** The backup password fields are
  marked as password fields (`Password` semantics) and draw only mask characters, but
  their editable text holds the real password, and an enabled accessibility service
  receives the password as it is typed: Compose's text-changed events carry the old and
  the new text (checked in the Compose 1.12.1 sources; not verified on a device).
- **Overlapping scrypt runs.** Cancel does not stop a running scrypt (it blocks): after
  Cancel while a backup is being read, that scrypt keeps running to its end, and a new
  import or export can start meanwhile, so two can overlap (at the default parameters a
  peak of about 2 × 64 MiB plus buffers).
- **An empty file the user picked can be deleted.** When the chosen file cannot be opened
  for writing, or the picker's result arrives after the app was restarted, the app deletes
  the file if it is empty, because it cannot tell it from the empty file the picker just
  created. An existing empty file the user picked is deleted too, with the message "The
  incomplete file was deleted."
- **No clipboard auto-clear yet.**
- **Labels are not encrypted** (see above): someone who can read the app's private
  storage can see which accounts exist.
- **The Keystore keys need no authentication**, so they protect against other apps and
  offline extraction, not against code running as the app.
- **Not audited.** No independent review has taken place.
- If app data is cleared, the stored secrets cannot be recovered. If a data key's wrapping
  key is lost, the accounts sealed with it can be read only from their kept legacy copy
  (converted rows, while the legacy key exists), otherwise only from a backup.
- **Envelope encryption weakens the model for memory (accepted trade-off).** The data key
  is unwrapped once per process and then stays in the app's memory for as long as the
  process runs. Anyone who can read the running app's memory (a rooted or compromised
  device, a memory extraction on an unlocked phone) gets that key, and with it **every**
  secret sealed with it: all current secrets and every future one, HOTP secrets included,
  not only the secrets decrypted at that moment. The Keystore protects the data key at
  rest only. In return, showing the list, "next code", export and import no longer cost a
  Keystore operation per account.
- **Legacy copies stay for one release.** On an upgraded device every converted row keeps
  its old ciphertext in `legacySecretBlob`, and the legacy Keystore key stays. Reads fall
  back to that copy when a data key is lost; there is no code to undo the conversion, and
  this version has no cleanup. Replacing or deleting an account
  removes its copy.
- **The new code has not run on a device yet.** The migration, the data key on the write
  paths, the progress screen and the storage-key banner are covered by JVM tests; their
  instrumented tests (WAL and `TRUNCATE` journaling, a real Room transaction, kill and
  resume, the legacy-copy fallback, a lost wrapping key) are written and compile but have
  not been run. An upgrade over an existing install has not been tried, and the startup
  time with the migration has not been measured.
- **Permanently invalidated keys** are handled as lost (covered by JVM tests only): the
  app's keys need no user authentication, so a device cannot be made to invalidate them
  for a test.
- **A key broken for good but reported like a temporary error is a dead end.** Only a
  missing key, `KeyPermanentlyInvalidatedException` and a failed authentication count as
  definite; every other Keystore failure (for example `UnrecoverableKeyException`, or an
  invalid key blob after a secure-hardware reset, if a device reports it that way) is
  treated as temporary. If the newest wrapping key fails like that on every start, adding
  and importing stay refused with "Try again", converted accounts show "Code temporarily
  unavailable" (the legacy copy is not used for temporary errors), and "Start a new storage
  key" is never offered; clearing the app's data is then the only way out, and only a
  backup brings the accounts back. The same failure of the legacy key leaves its rows
  unconverted and retried on every start. Which exceptions real devices raise here has not
  been checked.

## Reporting a vulnerability

Please **do not** open a public issue. Use GitHub's private vulnerability reporting for
this repository (the **Security** tab, then **Report a vulnerability**) to open a
private security advisory. Include the affected version or commit and steps to
reproduce.

Until the repository is public, message the maintainer, Luca (phucho0237), directly and
privately.

## Keeping this file accurate

Any change to crypto, storage or manifest code must re-verify every claim above
against the code in the same PR (see `CLAUDE.md`).
