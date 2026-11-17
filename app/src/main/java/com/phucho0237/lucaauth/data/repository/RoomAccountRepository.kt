package com.phucho0237.lucaauth.data.repository

import com.phucho0237.lucaauth.data.db.AccountDao
import com.phucho0237.lucaauth.domain.crypto.SecretCipher
import com.phucho0237.lucaauth.domain.model.AccountSummary
import com.phucho0237.lucaauth.domain.model.OtpAccount
import com.phucho0237.lucaauth.domain.repository.AccountRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * [AccountRepository] backed by Room, with each secret encrypted by [cipher] and bound
 * to its account id as associated data, so a secret copied into another row fails to
 * decrypt.
 *
 * Cipher calls run on [dispatcher] because Keystore operations can be slow. Cipher
 * exceptions propagate unchanged; secrets are never logged or put in messages.
 */
class RoomAccountRepository(
    private val dao: AccountDao,
    private val cipher: SecretCipher,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = { System.currentTimeMillis() },
) : AccountRepository {

    override fun observeAccounts(): Flow<List<AccountSummary>> =
        dao.observeAll().map { rows -> rows.map { it.toSummary() } }

    override suspend fun add(account: OtpAccount): String {
        val id = idGenerator()
        val secretBlob = withContext(dispatcher) {
            cipher.encrypt(account.secret, associatedData(id))
        }
        dao.insert(account.toEntity(id, secretBlob, clock()))
        return id
    }

    override suspend fun getAccount(id: String): OtpAccount? {
        val entity = dao.getById(id) ?: return null
        val secret = withContext(dispatcher) {
            cipher.decrypt(entity.secretBlob, associatedData(entity.id))
        }
        try {
            return entity.toOtpAccount(secret)
        } catch (e: IllegalArgumentException) {
            secret.fill(0)
            throw e
        }
    }

    override suspend fun updateCounter(id: String, counter: Long) {
        require(counter >= 0) { "Counter must not be negative" }
        dao.updateCounter(id, counter)
    }

    override suspend fun delete(id: String) {
        dao.delete(id)
    }

    private fun associatedData(id: String): ByteArray = id.toByteArray(Charsets.UTF_8)
}
