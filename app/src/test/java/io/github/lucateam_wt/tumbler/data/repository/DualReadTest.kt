package io.github.lucateam_wt.tumbler.data.repository

import io.github.lucateam_wt.tumbler.data.crypto.AesGcmSecretCipher
import io.github.lucateam_wt.tumbler.data.crypto.CountingWrappers
import io.github.lucateam_wt.tumbler.data.crypto.DataKeyCipher
import io.github.lucateam_wt.tumbler.data.crypto.DataKeyHolder
import io.github.lucateam_wt.tumbler.data.crypto.FakeDataKeyStore
import io.github.lucateam_wt.tumbler.data.crypto.FakeKeystore
import io.github.lucateam_wt.tumbler.data.crypto.KeyProvider
import io.github.lucateam_wt.tumbler.data.db.AccountEntity
import io.github.lucateam_wt.tumbler.domain.crypto.KeyUnavailableException
import io.github.lucateam_wt.tumbler.domain.crypto.SecretCipherException
import io.github.lucateam_wt.tumbler.domain.model.OtpAccount
import io.github.lucateam_wt.tumbler.domain.model.OtpType
import io.github.lucateam_wt.tumbler.domain.otp.Base32
import io.github.lucateam_wt.tumbler.domain.otp.OtpAlgorithm
import io.github.lucateam_wt.tumbler.domain.repository.NextHotpCodeResult
import io.github.lucateam_wt.tumbler.domain.repository.UnreadableAccountException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import java.util.Collections
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** A holder over an in-memory key table, for repository tests. Every holder in a test uses the same alias base. */
internal fun testDataKeyHolder(
    store: FakeDataKeyStore = FakeDataKeyStore(),
    wrappers: CountingWrappers = CountingWrappers(FakeKeystore()),
) = DataKeyHolder(store, wrappers::wrapperFor, aliasBase = TEST_WRAP_ALIAS)

internal const val TEST_WRAP_ALIAS = "test_wrap_key"

/**
 * Both repositories read rows in either format (`docs/storage-format.md`) while writing only the
 * legacy one. Rows sealed with a data key are made here by an "earlier process" (another
 * [DataKeyHolder] over the same key table and Keystore), because the app does not write them yet.
 */
class DualReadTest {

    /** The legacy Keystore key as a plain JVM key; [lost] simulates its entry being deleted. */
    private class LegacyKeys : KeyProvider {
        private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        var lost = false

        override fun getOrCreateKey(): SecretKey = key
        override fun getExistingKey(): SecretKey? = if (lost) null else key
    }

    /** Marks the coroutines that run inside a test transaction. */
    private class InTransaction : AbstractCoroutineContextElement(InTransaction) {
        companion object Key : CoroutineContext.Key<InTransaction>
    }

    private object MarkingRunner : TransactionRunner {
        override suspend fun <R> inTransaction(block: suspend () -> R): R = withContext(InTransaction()) { block() }
    }

    private val keystore = FakeKeystore()
    private val wrappers = CountingWrappers(keystore)
    private val keyTable = FakeDataKeyStore()
    private val legacyKeys = LegacyKeys()
    private val legacyCipher = AesGcmSecretCipher(legacyKeys)
    private val dao = FakeAccountDao()

    /** This process's holder: nothing unwrapped yet. */
    private val dataKeys = testDataKeyHolder(keyTable, wrappers)
    private val repository = RoomAccountRepository(dao, legacyCipher, dataKeys, MarkingRunner)
    private val store = RoomAccountBackupStore(dao, legacyCipher, dataKeys, MarkingRunner)
    private val random = SecureRandom()

    // RFC 4226 appendix D: secret "12345678901234567890", counter 0 -> 755224, 1 -> 287082.
    private val hotp = OtpAccount("Bank", "bob", OtpType.HOTP, "12345678901234567890".toByteArray(Charsets.US_ASCII))
    private val totp = OtpAccount(
        "Example", "alice@example.com", OtpType.TOTP, Base32.decode("JBSWY3DPEHPK3PXP"), OtpAlgorithm.SHA256, 8, 60,
    )

    @Test
    fun aLegacyRowIsReadAsBeforeAndNeverTouchesTheDataKeys() = runBlocking<Unit> {
        putLegacy("l", totp, 1)
        resetCounters()

        assertEquals(totp, repository.getAccount("l"))
        assertEquals(listOf(totp), store.readAll().map { it.account })
        assertEquals(listOf(totp), store.writeTransaction { readAll() }.map { it.account })
        assertEquals(0, keyTable.calls.get())
        assertEquals(0, wrappers.unwraps.get())
        assertEquals(0, wrappers.wraps.get())
        assertEquals(0, keystore.generations.get())
    }

    @Test
    fun rowsSealedWithADataKeyAreReadNextToLegacyRows() = runBlocking<Unit> {
        putSealed("s", totp, 1)
        putLegacy("l", hotp, 2)
        resetCounters()

        assertEquals(totp, repository.getAccount("s"))
        assertEquals(hotp, repository.getAccount("l"))
        assertEquals(listOf(totp, hotp), store.readAll().map { it.account })
        assertEquals(listOf("s", "l"), store.writeTransaction { readAll() }.map { it.id })
        assertEquals(1, wrappers.unwraps.get())
        assertEquals(0, wrappers.wraps.get())
    }

    @Test
    fun nextCodeOnADataKeyRowUsesItsSecretAndAdvancesTheCounter() = runBlocking<Unit> {
        val row = putSealed("h", hotp, 1)
        resetCounters()

        assertEquals(NextHotpCodeResult.Code("755224", 1), repository.nextHotpCode("h"))
        assertEquals(NextHotpCodeResult.Code("287082", 2), repository.nextHotpCode("h"))
        val stored = dao.stored("h")
        assertEquals(2L, stored.counter)
        assertEquals(row.keyVersion, stored.keyVersion)
        assertArrayEquals(row.secretBlob, stored.secretBlob)
        assertEquals(1, wrappers.unwraps.get())
    }

    @Test
    fun manyDataKeyRowsShareOneUnwrapAcrossBothReaders() = runBlocking<Unit> {
        repeat(20) { putSealed("id-$it", totp, it.toLong()) }
        resetCounters()

        assertEquals(20, store.readAll().size)
        assertEquals(1, wrappers.unwraps.get())
        assertEquals(1, keyTable.calls.get())

        keyTable.calls.set(0)
        repeat(20) { assertEquals(totp, repository.getAccount("id-$it")) }
        assertEquals(1, wrappers.unwraps.get())
        assertEquals(0, keyTable.calls.get())
    }

    @Test
    fun theDataKeyRecordIsReadInTheCallersTransaction() = runBlocking<Unit> {
        putSealed("h", hotp, 1)
        resetCounters()
        val inTransaction = Collections.synchronizedList(mutableListOf<Boolean>())
        keyTable.beforeCall = { inTransaction += currentCoroutineContext()[InTransaction] != null }

        assertTrue(repository.nextHotpCode("h") is NextHotpCodeResult.Code)
        assertEquals(listOf(true), inTransaction)

        // A new process reading inside a backup write transaction.
        val other = RoomAccountBackupStore(dao, legacyCipher, testDataKeyHolder(keyTable, wrappers), MarkingRunner)
        other.writeTransaction { readAll() }
        assertEquals(listOf(true, true), inTransaction)
    }

    @Test
    fun aLostWrappingKeyMakesItsRowsUnreadableAndKeepsThem() = runBlocking<Unit> {
        putSealed("s", hotp, 1)
        putLegacy("l", totp, 2)
        keystore.lose(TEST_WRAP_ALIAS)
        resetCounters()

        assertThrows(KeyUnavailableException::class.java) { runBlocking { repository.getAccount("s") } }
        assertSame(NextHotpCodeResult.Failed, repository.nextHotpCode("s"))
        assertEquals(0L, dao.stored("s").counter)
        val e = assertThrows(UnreadableAccountException::class.java) { runBlocking { store.readAll() } }
        assertEquals("s", e.accountId)
        // Legacy rows are unaffected, nothing was deleted and no key was made.
        assertEquals(totp, repository.getAccount("l"))
        assertEquals(2, dao.size)
        assertEquals(1, wrappers.unwraps.get())
        assertEquals(0, wrappers.wraps.get())
        assertEquals(0, keystore.generations.get())
    }

    @Test
    fun aLostLegacyKeyLeavesDataKeyRowsReadable() = runBlocking<Unit> {
        putSealed("s", totp, 1)
        putLegacy("l", hotp, 2)
        legacyKeys.lost = true

        assertEquals(totp, repository.getAccount("s"))
        assertThrows(KeyUnavailableException::class.java) { runBlocking { repository.getAccount("l") } }
    }

    @Test
    fun aRowWhoseKeyVersionDoesNotMatchItsSecretIsRefused() = runBlocking<Unit> {
        val sealed = putSealed("s", totp, 1)
        val legacy = putLegacy("l", hotp, 2)

        // A data-key payload labelled legacy, and a legacy payload labelled with a data key.
        dao.put(sealed.copyWith(keyVersion = 0))
        dao.put(legacy.copyWith(keyVersion = sealed.keyVersion))
        for (id in listOf("s", "l")) {
            assertThrows(SecretCipherException::class.java) { runBlocking { repository.getAccount(id) } }
        }
        assertSame(NextHotpCodeResult.Failed, repository.nextHotpCode("l"))
        assertEquals("s", assertThrows(UnreadableAccountException::class.java) { runBlocking { store.readAll() } }.accountId)
    }

    @Test
    fun aDataKeyPayloadMovedToAnotherRowIsRefused() = runBlocking<Unit> {
        val sealed = putSealed("s", totp, 1)
        dao.put(AccountEntity("t", "X", "y", "TOTP", "SHA1", 6, 30, 0, sealed.secretBlob, 2, sealed.keyVersion, null))

        assertThrows(SecretCipherException::class.java) { runBlocking { repository.getAccount("t") } }
    }

    @Test
    fun negativeAndUnknownKeyVersionsAreRefusedWithoutMakingAKey() = runBlocking<Unit> {
        val legacy = putLegacy("l", hotp, 1)
        dao.put(legacy.copyWith(keyVersion = -1))
        assertThrows(SecretCipherException::class.java) { runBlocking { repository.getAccount("l") } }
        assertSame(NextHotpCodeResult.Failed, repository.nextHotpCode("l"))
        assertThrows(UnreadableAccountException::class.java) { runBlocking { store.readAll() } }

        dao.put(legacy.copyWith(keyVersion = 7))
        assertThrows(KeyUnavailableException::class.java) { runBlocking { repository.getAccount("l") } }
        assertSame(NextHotpCodeResult.Failed, repository.nextHotpCode("l"))
        assertEquals(0L, dao.stored("l").counter)
        assertEquals(0, wrappers.wraps.get())
        assertEquals(0, keystore.generations.get())
        assertTrue(keyTable.records.isEmpty())
    }

    @Test
    fun writesStayLegacyAndNeverCreateADataKey() = runBlocking<Unit> {
        val added = repository.add(totp)
        val inserted = store.writeTransaction { insert(hotp, 5) }

        for (id in listOf(added, inserted)) {
            val row = dao.stored(id)
            assertEquals(0, row.keyVersion)
            assertNull(row.legacySecretBlob)
            assertEquals(1, row.secretBlob[0].toInt())
        }
        assertEquals(0, keyTable.calls.get())
        assertEquals(0, wrappers.wraps.get())
        assertEquals(0, keystore.generations.get())
    }

    @Test
    fun replaceMakesADataKeyRowLegacyAndDropsItsLegacyCopy() = runBlocking<Unit> {
        val sealed = putSealed("s", totp, 1)
        dao.put(sealed.copyWith(legacySecretBlob = legacyCipher.encrypt(totp.secret, accountAssociatedData("s"))))

        store.writeTransaction { replace("s", hotp) }

        val replaced = dao.stored("s")
        assertEquals(0, replaced.keyVersion)
        assertNull(replaced.legacySecretBlob)
        assertEquals(1, replaced.secretBlob[0].toInt())
        assertFalse(replaced.secretBlob.contentEquals(sealed.secretBlob))
        assertEquals(hotp, repository.getAccount("s"))
    }

    /** Stores [account] as row [id], sealed with a data key that an earlier process made and stored. */
    private fun putSealed(id: String, account: OtpAccount, createdAt: Long): AccountEntity {
        val earlier = testDataKeyHolder(keyTable, wrappers)
        val row = runBlocking {
            val version = earlier.prepareWriteKey()
            val blob = DataKeyCipher.seal(earlier.writeKey(version), id, account.secret, random)
            account.toEntity(id, blob, version, createdAt)
        }
        assertEquals(2, row.secretBlob[0].toInt())
        dao.put(row)
        return row
    }

    private fun putLegacy(id: String, account: OtpAccount, createdAt: Long): AccountEntity =
        account.toEntity(id, legacyCipher.encrypt(account.secret, accountAssociatedData(id)), 0, createdAt)
            .also { dao.put(it) }

    private fun resetCounters() {
        wrappers.wraps.set(0)
        wrappers.unwraps.set(0)
        keyTable.calls.set(0)
        keystore.generations.set(0)
    }
}
