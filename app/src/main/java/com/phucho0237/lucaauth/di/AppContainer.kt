package com.phucho0237.lucaauth.di

import android.content.Context
import com.phucho0237.lucaauth.data.crypto.AesGcmSecretCipher
import com.phucho0237.lucaauth.data.crypto.AndroidKeystoreKeyProvider
import com.phucho0237.lucaauth.data.db.LucaAuthDatabase
import com.phucho0237.lucaauth.data.repository.RoomAccountRepository
import com.phucho0237.lucaauth.domain.crypto.SecretCipher
import com.phucho0237.lucaauth.domain.repository.AccountRepository

/** Manual dependency graph of the app; every object is created on first use. */
class AppContainer(private val context: Context) {

    private val database: LucaAuthDatabase by lazy { LucaAuthDatabase.getInstance(context) }

    private val secretCipher: SecretCipher by lazy { AesGcmSecretCipher(AndroidKeystoreKeyProvider()) }

    val accountRepository: AccountRepository by lazy {
        RoomAccountRepository(database.accountDao(), secretCipher)
    }
}
