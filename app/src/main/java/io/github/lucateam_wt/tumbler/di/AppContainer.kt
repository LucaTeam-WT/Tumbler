package io.github.lucateam_wt.tumbler.di

import android.content.Context
import io.github.lucateam_wt.tumbler.backup.BackupService
import io.github.lucateam_wt.tumbler.data.crypto.AesGcmSecretCipher
import io.github.lucateam_wt.tumbler.data.crypto.AndroidKeystoreKeyProvider
import io.github.lucateam_wt.tumbler.data.crypto.DataKeyHolder
import io.github.lucateam_wt.tumbler.data.crypto.keystoreDataKeyWrapper
import io.github.lucateam_wt.tumbler.data.db.TumblerDatabase
import io.github.lucateam_wt.tumbler.data.repository.RoomAccountBackupStore
import io.github.lucateam_wt.tumbler.data.repository.RoomAccountRepository
import io.github.lucateam_wt.tumbler.data.repository.RoomDataKeyStore
import io.github.lucateam_wt.tumbler.domain.crypto.SecretCipher
import io.github.lucateam_wt.tumbler.domain.repository.AccountRepository

/** Manual dependency graph of the app; every object is created on first use. */
class AppContainer(private val context: Context) {

    private val database: TumblerDatabase by lazy { TumblerDatabase.getInstance(context) }

    /** The legacy Keystore key; only used to decrypt rows (and legacy copies) written before the data key. */
    private val secretCipher: SecretCipher by lazy { AesGcmSecretCipher(AndroidKeystoreKeyProvider()) }

    /**
     * The process's data keys, shared by every reader and writer so each is unwrapped at most once
     * per process. New secrets are sealed with the newest one.
     */
    private val dataKeys: DataKeyHolder by lazy {
        DataKeyHolder(RoomDataKeyStore(database), ::keystoreDataKeyWrapper)
    }

    val accountRepository: AccountRepository by lazy {
        RoomAccountRepository(database, secretCipher, dataKeys)
    }

    /** Backup export and import, used by the backup screens through `BackupViewModel`. */
    internal val backupService: BackupService by lazy {
        BackupService(RoomAccountBackupStore(database, secretCipher, dataKeys, beforeAccess = {}))
    }
}
