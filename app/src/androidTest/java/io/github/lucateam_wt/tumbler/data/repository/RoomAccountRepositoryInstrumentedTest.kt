package io.github.lucateam_wt.tumbler.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lucateam_wt.tumbler.data.crypto.AesGcmSecretCipher
import io.github.lucateam_wt.tumbler.data.crypto.AndroidKeystoreKeyProvider
import io.github.lucateam_wt.tumbler.data.db.TumblerDatabase
import io.github.lucateam_wt.tumbler.domain.crypto.KeyUnavailableException
import io.github.lucateam_wt.tumbler.domain.model.OtpAccount
import io.github.lucateam_wt.tumbler.domain.model.OtpType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore

/** Real Room database and real Android Keystore, with a test-only file name and key alias. */
@RunWith(AndroidJUnit4::class)
class RoomAccountRepositoryInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val alias = "lucaauth_test_key_storage"
    private val databaseName = "lucaauth-test.db"
    private val openDatabases = mutableListOf<TumblerDatabase>()

    private val totp = OtpAccount("Example", "alice@example.com", OtpType.TOTP, "JBSWY3DPEHPK3PXP".toByteArray())
    private val hotp = OtpAccount("Bank", "bob", OtpType.HOTP, "12345678901234567890".toByteArray(), counter = 3)

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun openRepository(): RoomAccountRepository {
        val database = TumblerDatabase.open(context, databaseName).also { openDatabases += it }
        return RoomAccountRepository(database.accountDao(), AesGcmSecretCipher(AndroidKeystoreKeyProvider(alias)))
    }

    private fun deleteTestData() {
        keyStore().deleteEntry(alias)
        context.deleteDatabase(databaseName)
    }

    @Before
    fun setUp() = deleteTestData()

    @After
    fun tearDown() {
        openDatabases.forEach { it.close() }
        deleteTestData()
    }

    @Test
    fun addGetDelete_roundTrip() = runBlocking {
        val repository = openRepository()
        val id = repository.add(totp)

        assertEquals(totp, repository.getAccount(id))
        repository.delete(id)
        assertNull(repository.getAccount(id))
    }

    @Test
    fun dataSurvivesClosingAndReopeningTheDatabase() = runBlocking {
        val id = openRepository().add(hotp)
        openDatabases.single().close()

        assertEquals(hotp, openRepository().getAccount(id))
    }

    @Test
    fun keystoreAliasDeleted_getAccountThrowsKeyUnavailable() = runBlocking {
        val repository = openRepository()
        val id = repository.add(totp)
        keyStore().deleteEntry(alias)

        assertThrows(KeyUnavailableException::class.java) { runBlocking { repository.getAccount(id) } }
        assertFalse(keyStore().containsAlias(alias))
    }

    @Test
    fun updateCounter_changesOnlyHotpRows() = runBlocking {
        val repository = openRepository()
        val totpId = repository.add(totp)
        val hotpId = repository.add(hotp)

        // Real SQL reports 0 rows updated for a TOTP row or an unknown id.
        assertThrows(NoSuchElementException::class.java) { runBlocking { repository.updateCounter(totpId, 9) } }
        assertThrows(NoSuchElementException::class.java) { runBlocking { repository.updateCounter("missing", 9) } }
        repository.updateCounter(hotpId, 9)

        assertEquals(totp, repository.getAccount(totpId))
        assertEquals(hotp.copy(counter = 9), repository.getAccount(hotpId))
    }
}
