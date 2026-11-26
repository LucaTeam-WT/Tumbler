package io.github.lucateam_wt.tumbler.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lucateam_wt.tumbler.data.crypto.AesGcmSecretCipher
import io.github.lucateam_wt.tumbler.data.crypto.AndroidKeystoreKeyProvider
import io.github.lucateam_wt.tumbler.data.db.TumblerDatabase
import io.github.lucateam_wt.tumbler.domain.model.OtpAccount
import io.github.lucateam_wt.tumbler.domain.model.OtpType
import io.github.lucateam_wt.tumbler.domain.otp.Base32
import io.github.lucateam_wt.tumbler.domain.otp.Hotp
import io.github.lucateam_wt.tumbler.domain.repository.NextHotpCodeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore

/**
 * [RoomAccountRepository.nextHotpCode] on a real Room database file (WAL, several
 * connections) and the real Android Keystore, with a test-only file name and key alias.
 */
@RunWith(AndroidJUnit4::class)
class NextHotpCodeInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val alias = "lucaauth_test_key_next_hotp"
    private val databaseName = "lucaauth-test-next-hotp.db"
    private lateinit var database: TumblerDatabase
    private lateinit var repository: RoomAccountRepository
    private lateinit var store: RoomAccountBackupStore

    private val oldSecret = Base32.decode("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")
    private val newSecret = Base32.decode("JBSWY3DPEHPK3PXP")
    private fun hotp(secret: ByteArray = oldSecret, counter: Long = 3) =
        OtpAccount("Bank", "bob", OtpType.HOTP, secret, counter = counter)

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun deleteTestData() {
        keyStore().deleteEntry(alias)
        context.deleteDatabase(databaseName)
    }

    @Before
    fun setUp() {
        deleteTestData()
        database = TumblerDatabase.open(context, databaseName)
        val cipher = AesGcmSecretCipher(AndroidKeystoreKeyProvider(alias))
        val dataKeys = testDataKeyHolder(database, "${alias}_wrap")
        repository = RoomAccountRepository(database, cipher, dataKeys)
        store = RoomAccountBackupStore(database, cipher, dataKeys)
    }

    @After
    fun tearDown() {
        database.close()
        deleteTestData()
    }

    @Test
    fun importReplacedTheRowAfterTheListWasLoaded_codeAndCounterComeFromTheNewRow() = runBlocking {
        val id = repository.add(hotp())
        // What the accounts list holds: decrypted before the import.
        val listed = checkNotNull(repository.getAccount(id))
        store.writeTransaction { replace(id, hotp(newSecret, counter = 50)) }

        val result = repository.nextHotpCode(id)

        assertEquals(NextHotpCodeResult.Code(Hotp(newSecret).generate(50), 51), result)
        assertNotEquals(Hotp(listed.secret).generate(listed.counter), (result as NextHotpCodeResult.Code).code)
        assertEquals(hotp(newSecret, counter = 51), repository.getAccount(id))
    }

    @Test
    fun concurrentCalls_eachCounterIsHandedOutExactlyOnce() = runBlocking {
        val base = 3L
        val calls = 20
        val id = repository.add(hotp(counter = base))

        val results = (1..calls).map { async(Dispatchers.IO) { repository.nextHotpCode(id) } }.awaitAll()

        val codes = results.map { it as NextHotpCodeResult.Code }
        assertEquals((base + 1..base + calls).toList(), codes.map { it.counter }.sorted())
        assertEquals(codes.map { Hotp(oldSecret).generate(it.counter - 1) }, codes.map { it.code })
        assertEquals(base + calls, checkNotNull(repository.getAccount(id)).counter)
    }

    @Test
    fun counterLimit_returnsLimitReachedAndLeavesTheCounter() = runBlocking {
        val id = repository.add(hotp(counter = Long.MAX_VALUE))

        assertSame(NextHotpCodeResult.LimitReached, repository.nextHotpCode(id))
        assertEquals(hotp(counter = Long.MAX_VALUE), repository.getAccount(id))
    }
}
