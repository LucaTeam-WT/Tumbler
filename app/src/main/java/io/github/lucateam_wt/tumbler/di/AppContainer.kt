package io.github.lucateam_wt.tumbler.di

import android.content.Context
import io.github.lucateam_wt.tumbler.backup.BackupService
import io.github.lucateam_wt.tumbler.data.crypto.AesGcmSecretCipher
import io.github.lucateam_wt.tumbler.data.crypto.AndroidKeystoreKeyProvider
import io.github.lucateam_wt.tumbler.data.db.TumblerDatabase
import io.github.lucateam_wt.tumbler.data.repository.RoomAccountBackupStore
import io.github.lucateam_wt.tumbler.data.repository.RoomAccountRepository
import io.github.lucateam_wt.tumbler.domain.crypto.SecretCipher
import io.github.lucateam_wt.tumbler.domain.repository.AccountRepository

/** Manual dependency graph of the app; every object is created on first use. */
class AppContainer(private val context: Context) {

    private val database: TumblerDatabase by lazy { TumblerDatabase.getInstance(context) }

    private val secretCipher: SecretCipher by lazy { AesGcmSecretCipher(AndroidKeystoreKeyProvider()) }

    val accountRepository: AccountRepository by lazy {
        RoomAccountRepository(database.accountDao(), secretCipher)
    }

    /**
     * Backup export and import. No screen uses it yet (that is the next step), so release
     * builds strip it and the backup code with it.
     */
    internal val backupService: BackupService by lazy {
        BackupService(RoomAccountBackupStore(database, secretCipher))
    }
}
