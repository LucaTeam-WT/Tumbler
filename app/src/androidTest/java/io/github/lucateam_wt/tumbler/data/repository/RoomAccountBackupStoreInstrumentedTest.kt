package io.github.lucateam_wt.tumbler.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lucateam_wt.tumbler.data.crypto.AesGcmSecretCipher
import io.github.lucateam_wt.tumbler.data.crypto.AndroidKeystoreKeyProvider
import io.github.lucateam_wt.tumbler.data.db.TumblerDatabase
import io.github.lucateam_wt.tumbler.domain.crypto.KeyUnavailableException
import io.github.lucateam_wt.tumbler.domain.model.OtpAccount
import io.github.lucateam_wt.tumbler.domain.model.OtpType
import io.github.lucateam_wt.tumbler.domain.otp.Base32
import io.github.lucateam_wt.tumbler.domain.repository.UnreadableAccountException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore

/** Real (in-memory) Room transactions and the real Android Keystore, with a test-only key alias. */
@RunWith(AndroidJUnit4::class)
class RoomAccountBackupStoreInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val alias = "lucaauth_test_key_backup_store"
    private lateinit var database: TumblerDatabase
    private lateinit var repository: RoomAccountRepository
    private lateinit var store: RoomAccountBackupStore

    private val totp = OtpAccount("Example", "alice@example.com", OtpType.TOTP, Base32.decode("JBSWY3DPEHPK3PXP"))
    private val hotp = OtpAccount("Bank", "bob", OtpType.HOTP, Base32.decode("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"), counter = 3)

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Before
    fun setUp() {
        keyStore().deleteEntry(alias)
        database = Room.inMemoryDatabaseBuilder(context, TumblerDatabase::class.java).build()
        val cipher = AesGcmSecretCipher(AndroidKeystoreKeyProvider(alias))
        repository = RoomAccountRepository(database.accountDao(), cipher)
        store = RoomAccountBackupStore(database, cipher)
    }

    @After
    fun tearDown() {
        database.close()
        keyStore().deleteEntry(alias)
    }

    @Test
    fun readAll_readsWhatTheRepositoryWrote() = runBlocking {
        val totpId = repository.add(totp)
        val hotpId = repository.add(hotp)

        val all = store.readAll()

        assertEquals(setOf(totpId, hotpId), all.map { it.id }.toSet())
        assertEquals(totp, all.single { it.id == totpId }.account)
        assertEquals(hotp, all.single { it.id == hotpId }.account)
    }

    @Test
    fun writeTransaction_commitsInsertsAndReplacesKeepingIdAndCreationTime() = runBlocking {
        val hotpId = repository.add(hotp)
        val createdAt = store.readAll().single().createdAtMillis

        val insertedId = store.writeTransaction {
            replace(hotpId, hotp.copy(issuer = "Bank (new)", counter = 10))
            insert(totp, createdAtMillis = 5)
        }

        val all = store.readAll().associateBy { it.id }
        assertEquals(hotp.copy(issuer = "Bank (new)", counter = 10), repository.getAccount(hotpId))
        assertEquals(createdAt, all.getValue(hotpId).createdAtMillis)
        assertEquals(totp, repository.getAccount(insertedId))
        assertEquals(5L, all.getValue(insertedId).createdAtMillis)
    }

    @Test
    fun writeTransaction_rollsBackEveryWriteWhenTheBlockThrows() = runBlocking {
        val hotpId = repository.add(hotp)

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                store.writeTransaction {
                    insert(totp, createdAtMillis = 5)
                    replace(hotpId, hotp.copy(counter = 99))
                    throw IllegalStateException("abort")
                }
            }
        }

        assertEquals(listOf(hotpId), store.readAll().map { it.id })
        assertEquals(hotp, repository.getAccount(hotpId))
    }

    @Test
    fun readAll_keystoreKeyDeleted_isUnreadableWithoutCreatingAKey() = runBlocking {
        val id = repository.add(totp)
        keyStore().deleteEntry(alias)

        val error = assertThrows(UnreadableAccountException::class.java) { runBlocking { store.readAll() } }

        assertEquals(id, error.accountId)
        assertTrue(error.cause is KeyUnavailableException)
        assertTrue(!keyStore().containsAlias(alias))
    }
}
