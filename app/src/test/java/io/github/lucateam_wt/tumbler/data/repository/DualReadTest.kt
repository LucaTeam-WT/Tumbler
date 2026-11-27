package io.github.lucateam_wt.tumbler.data.repository

import android.security.keystore.KeyPermanentlyInvalidatedException
import io.github.lucateam_wt.tumbler.data.crypto.AesGcmSecretCipher
import io.github.lucateam_wt.tumbler.data.crypto.CountingWrappers
import io.github.lucateam_wt.tumbler.data.crypto.DataKeyCipher
import io.github.lucateam_wt.tumbler.data.crypto.DataKeyHolder
import io.github.lucateam_wt.tumbler.data.crypto.FakeDataKeyStore
import io.github.lucateam_wt.tumbler.data.crypto.FakeKeystore
import io.github.lucateam_wt.tumbler.data.crypto.KeyProvider
import io.github.lucateam_wt.tumbler.data.db.AccountEntity
import io.github.lucateam_wt.tumbler.domain.crypto.KeyTemporarilyUnavailableException
import io.github.lucateam_wt.tumbler.domain.crypto.KeyUnavailableException
import io.github.lucateam_wt.tumbler.domain.crypto.NewStorageKeyRequiredException
import io.github.lucateam_wt.tumbler.domain.crypto.SecretCipher
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
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.security.ProviderException
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.AEADBadTagException
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

/** A holder over a key table that sees the highest key version of [accounts], as the Room store does. */
internal fun linkedDataKeyHolder(
    accounts: FakeAccountDao,
    keys: FakeDataKeyDao = FakeDataKeyDao(),
    wrappers: CountingWrappers = CountingWrappers(FakeKeystore()),
) = DataKeyHolder(RoomDataKeyStore(keys, accounts), wrappers::wrapperFor, aliasBase = TEST_WRAP_ALIAS)

/**
 * Both repositories read rows in either format (`docs/storage-format.md`), write only the data-key
 * one, fall back to the legacy copy of a row whose data key is lost, and never unwrap or call the
 * Keystore inside a transaction. Rows sealed with a data key by an "earlier process" are made with
 * another [DataKeyHolder] over the same key table and Keystore.
 */
class DualReadTest {

    /** A lock regression in the data-key code would hang instead of failing. */
    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

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

    /** The legacy cipher, counting decrypts and keeping every plaintext it returned. */
    private class CountingLegacyCipher(private val delegate: SecretCipher) : SecretCipher {
        val decrypts = AtomicInteger()
        val encrypts = AtomicInteger()
        val returned: MutableList<ByteArray> = Collections.synchronizedList(mutableListOf())

        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
            encrypts.incrementAndGet()
            return delegate.encrypt(plaintext, associatedData)
        }

        override fun decrypt(payload: ByteArray, associatedData: ByteArray): ByteArray {
            decrypts.incrementAndGet()
            return delegate.decrypt(payload, associatedData).also { returned += it }
        }
    }

    /**
     * Marks the transaction's coroutines, and records every transaction during which a Keystore
     * operation ran (a wrap, an unwrap, a key generation or a legacy decrypt). The tests here run one
     * operation at a time, so any such operation belongs to the open transaction.
     */
    private inner class AuditingRunner : TransactionRunner {
        val transactions = AtomicInteger()
        val withKeystoreWork = AtomicInteger()

        override suspend fun <R> inTransaction(block: suspend () -> R): R = withContext(InTransaction()) {
            transactions.incrementAndGet()
            val before = keystoreOperations()
            try {
                block()
            } finally {
                if (keystoreOperations() != before) withKeystoreWork.incrementAndGet()
            }
        }
    }

    private val keystore = FakeKeystore()
    private val wrappers = CountingWrappers(keystore)
    private val keyTable = FakeDataKeyStore()
    private val legacyKeys = LegacyKeys()
    private val legacyCipher = CountingLegacyCipher(AesGcmSecretCipher(legacyKeys))
    private val dao = FakeAccountDao()
    private val runner = AuditingRunner()

    /** This process's holder: nothing unwrapped yet. */
    private val dataKeys = testDataKeyHolder(keyTable, wrappers)
    private val repository = RoomAccountRepository(dao, legacyCipher, dataKeys, runner)
    private val store = RoomAccountBackupStore(dao, legacyCipher, dataKeys, runner)
    private val random = SecureRandom()

    private fun keystoreOperations() =
        wrappers.wraps.get() + wrappers.unwraps.get() + keystore.generations.get() + legacyCipher.decrypts.get()

    /** PR A must-do (b): no test here may unwrap or call the Keystore inside a transaction. */
    @After
    fun noKeystoreWorkInsideATransaction() {
        assertEquals("transactions with Keystore work", 0, runner.withKeystoreWork.get())
    }

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
        assertEquals(0, keyTable.calls.get())
        assertEquals(0, wrappers.unwraps.get())
        assertEquals(0, wrappers.wraps.get())
        assertEquals(0, keystore.generations.get())

        // A write transaction resolves the write key first (on a fresh install it makes version 1),
        // outside the transaction, and decrypts the legacy row before it opens.
        legacyCipher.returned.clear()
        assertEquals(listOf(totp), store.writeTransaction { readAll() }.map { it.account })
        assertEquals(setOf(1), keyTable.records.keys)
        assertEquals(1, keystore.generations.get())
        assertTrue(legacyCipher.returned.all { secret -> secret.all { it == 0.toByte() } })
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
    fun aKeyFoundUnreadableIsRetriedOnlyAfterForgetCachedReadFailures() = runBlocking<Unit> {
        putSealed("s", totp, 1)
        resetCounters()
        wrappers.failUnwrapsWith = { SecretCipherException("Decryption failed", AEADBadTagException("Tag mismatch")) }

        assertThrows(SecretCipherException::class.java) { runBlocking { repository.getAccount("s") } }
        wrappers.failUnwrapsWith = null
        assertThrows(SecretCipherException::class.java) { runBlocking { repository.getAccount("s") } }
        assertEquals(1, wrappers.unwraps.get())

        repository.forgetCachedReadFailures()
        assertEquals(totp, repository.getAccount("s"))
        assertEquals(2, wrappers.unwraps.get())
    }

    @Test
    fun aKeystoreErrorOnADataKeyRowIsRetryableAndNotCached() = runBlocking<Unit> {
        putSealed("s", totp, 1)
        resetCounters()
        keystore.lookupFailure = { ProviderException("busy") }

        assertThrows(KeyTemporarilyUnavailableException::class.java) { runBlocking { repository.getAccount("s") } }
        keystore.lookupFailure = null
        assertEquals(totp, repository.getAccount("s"))
        assertEquals(2, wrappers.unwraps.get())
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
    fun keysAreUnwrappedBeforeATransactionAndTheWriteKeysRecordIsReadInIt() = runBlocking<Unit> {
        putSealed("h", hotp, 1)
        putLegacy("l", totp, 2)
        resetCounters()
        val inTransaction = Collections.synchronizedList(mutableListOf<Boolean>())
        keyTable.beforeCall = { inTransaction += currentCoroutineContext()[InTransaction] != null }

        // A new process: the first next code unwraps outside its transaction, which then only reads memory.
        assertTrue(repository.nextHotpCode("h") is NextHotpCodeResult.Code)
        assertEquals(listOf(false), inTransaction)
        assertEquals(1, wrappers.unwraps.get())

        // A write reads the record of its key inside the transaction that commits the row.
        inTransaction.clear()
        val added = repository.add(totp)
        assertEquals(listOf(false, true), inTransaction)

        // Another new process importing: both keys and the legacy row are resolved before its transaction.
        inTransaction.clear()
        val other = RoomAccountBackupStore(dao, legacyCipher, testDataKeyHolder(keyTable, wrappers), runner)
        val ids = other.writeTransaction {
            insert(hotp, 9)
            readAll().map { it.id }
        }
        assertEquals(listOf("h", "l", added).sorted(), ids.filter { it in setOf("h", "l", added) }.sorted())
        assertEquals(listOf(true), inTransaction.filter { it })
        assertEquals(2, wrappers.unwraps.get())
        assertTrue(runner.transactions.get() >= 3)
    }

    @Test
    fun aRowWhoseDataKeyIsLostFallsBackToItsLegacyCopy() = runBlocking<Unit> {
        val sealed = putSealed("s", hotp, 1)
        val copy = legacyCipher.encrypt(hotp.secret, accountAssociatedData("s"))
        dao.put(sealed.copyWith(legacySecretBlob = copy))
        keystore.lose(TEST_WRAP_ALIAS)
        resetCounters()

        assertEquals(hotp, repository.getAccount("s"))
        assertEquals(listOf(hotp), store.readAll().map { it.account })
        legacyCipher.returned.clear()
        assertEquals(NextHotpCodeResult.Code("755224", 1), repository.nextHotpCode("s"))
        assertEquals(listOf(hotp.copy(counter = 1)), store.writeTransaction { readAll() }.map { it.account })
        // Read only: the row keeps its sealed payload, key version and legacy copy.
        val row = dao.stored("s")
        assertArrayEquals(sealed.secretBlob, row.secretBlob)
        assertEquals(sealed.keyVersion, row.keyVersion)
        assertArrayEquals(copy, row.legacySecretBlob)
        assertEquals(0, wrappers.wraps.get())
        assertEquals(0, keystore.generations.get())
        assertTrue(legacyCipher.returned.all { secret -> secret.all { it == 0.toByte() } })
    }

    @Test
    fun aRecordThatDoesNotUnwrapFallsBackToo() = runBlocking<Unit> {
        val sealed = putSealed("s", totp, 1)
        dao.put(sealed.copyWith(legacySecretBlob = legacyCipher.encrypt(totp.secret, accountAssociatedData("s"))))
        resetCounters()
        wrappers.failUnwrapsWith = { SecretCipherException("Decryption failed", AEADBadTagException("Tag mismatch")) }

        assertEquals(totp, repository.getAccount("s"))
        assertEquals(1, legacyCipher.decrypts.get())
    }

    @Test
    fun aTransientKeystoreErrorNeverFallsBackAndIsNotAnUnreadableAccount() = runBlocking<Unit> {
        val sealed = putSealed("s", totp, 1)
        dao.put(sealed.copyWith(legacySecretBlob = legacyCipher.encrypt(totp.secret, accountAssociatedData("s"))))
        resetCounters()
        keystore.lookupFailure = { ProviderException("busy") }

        assertThrows(KeyTemporarilyUnavailableException::class.java) { runBlocking { repository.getAccount("s") } }
        assertThrows(KeyTemporarilyUnavailableException::class.java) { runBlocking { store.readAll() } }
        assertThrows(KeyTemporarilyUnavailableException::class.java) { runBlocking { store.writeTransaction { readAll() } } }
        assertSame(NextHotpCodeResult.NotHotp, repository.nextHotpCode("s"))
        assertEquals(0, legacyCipher.decrypts.get())
    }

    @Test
    fun aTransientLegacyErrorIsNotAnUnreadableAccount() = runBlocking<Unit> {
        putLegacy("l", totp, 1)
        val failing = RoomAccountBackupStore(dao, FailingCipher(ProviderException("busy")), dataKeys, runner)

        assertThrows(KeyTemporarilyUnavailableException::class.java) { runBlocking { failing.readAll() } }
        assertThrows(KeyTemporarilyUnavailableException::class.java) { runBlocking { failing.writeTransaction { readAll() } } }
    }

    @Test
    fun noFallbackWhenTheLegacyKeyIsGoneToo() = runBlocking<Unit> {
        val sealed = putSealed("s", totp, 1)
        dao.put(sealed.copyWith(legacySecretBlob = legacyCipher.encrypt(totp.secret, accountAssociatedData("s"))))
        keystore.lose(TEST_WRAP_ALIAS)
        legacyKeys.lost = true

        assertThrows(KeyUnavailableException::class.java) { runBlocking { repository.getAccount("s") } }
        assertEquals("s", assertThrows(UnreadableAccountException::class.java) { runBlocking { store.readAll() } }.accountId)
    }

    @Test
    fun aRowChangedBetweenPrepareAndTheTransactionIsRetryableNotUnreadable() = runBlocking<Unit> {
        putLegacy("l", totp, 1)
        assertThrows(KeyTemporarilyUnavailableException::class.java) {
            runBlocking {
                legacyCipher.returned.clear()
                store.writeTransaction {
                    // Another writer replaced the row after it was prepared (simulated in the transaction).
                    dao.put(dao.stored("l").copyWith(secretBlob = legacyCipher.encrypt(hotp.secret, accountAssociatedData("l"))))
                    readAll()
                }
            }
        }
        assertTrue(legacyCipher.returned.all { secret -> secret.all { it == 0.toByte() } })
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
    fun writesUseOneDataKeyBoundToTheIdAndNeverTheLegacyKey() = runBlocking<Unit> {
        val added = repository.add(totp)
        val inserted = store.writeTransaction { insert(hotp, 5) }

        val key = dataKeys.key(1)
        for ((id, account) in listOf(added to totp, inserted to hotp)) {
            val row = dao.stored(id)
            assertEquals(1, row.keyVersion)
            assertNull(row.legacySecretBlob)
            assertEquals(2, row.secretBlob[0].toInt())
            assertArrayEquals(account.secret, DataKeyCipher.open(key, id, row.secretBlob))
        }
        assertThrows(SecretCipherException::class.java) { DataKeyCipher.open(key, inserted, dao.stored(added).secretBlob) }
        // One key: created by the first write, before its transaction; a fresh nonce per secret.
        assertEquals(setOf(1), keyTable.records.keys)
        assertEquals(1, wrappers.wraps.get())
        assertEquals(1, keystore.generations.get())
        assertFalse(dao.stored(added).secretBlob.copyOfRange(2, 14).contentEquals(dao.stored(inserted).secretBlob.copyOfRange(2, 14)))
        assertEquals(0, legacyCipher.encrypts.get())
        assertEquals(0, legacyCipher.decrypts.get())
    }

    @Test
    fun replaceSealsWithTheDataKeyAndDropsTheLegacyCopy() = runBlocking<Unit> {
        val sealed = putSealed("s", totp, 1)
        dao.put(sealed.copyWith(legacySecretBlob = legacyCipher.encrypt(totp.secret, accountAssociatedData("s"))))

        store.writeTransaction { replace("s", hotp) }

        val replaced = dao.stored("s")
        assertEquals(1, replaced.keyVersion)
        assertNull(replaced.legacySecretBlob)
        assertEquals(2, replaced.secretBlob[0].toInt())
        assertFalse(replaced.secretBlob.contentEquals(sealed.secretBlob))
        assertEquals(hotp, repository.getAccount("s"))
    }

    @Test
    fun anInvalidatedWrappingKeyCountsAsLostNotAsTransient() = runBlocking<Unit> {
        putSealed("s", totp, 1)
        resetCounters()
        wrappers.failUnwrapsWith = {
            SecretCipherException("Decryption failed", KeyPermanentlyInvalidatedException())
        }

        assertThrows(KeyUnavailableException::class.java) { runBlocking { repository.getAccount("s") } }
        assertThrows(NewStorageKeyRequiredException::class.java) { runBlocking { dataKeys.prepareWriteKey() } }
        // Cached like a lost key: no second unwrap until the failures are forgotten.
        assertThrows(KeyUnavailableException::class.java) { runBlocking { repository.getAccount("s") } }
        assertEquals(1, wrappers.unwraps.get())
    }

    @Test
    fun anInvalidatedLegacyKeyCountsAsLostNotAsTransient() = runBlocking<Unit> {
        putLegacy("l", totp, 1)
        val invalidated = FailingCipher(KeyPermanentlyInvalidatedException())

        assertThrows(KeyUnavailableException::class.java) {
            runBlocking { RoomAccountRepository(dao, invalidated, dataKeys, runner).getAccount("l") }
        }
    }

    /** A legacy cipher whose decrypt fails the way AesGcmSecretCipher wraps a Keystore error. */
    private class FailingCipher(private val cause: Exception) : SecretCipher {
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray = error("not used")
        override fun decrypt(payload: ByteArray, associatedData: ByteArray): ByteArray =
            throw SecretCipherException("Decryption failed", cause)
    }

    /** Stores [account] as row [id], sealed with a data key that an earlier process made and stored. */
    private fun putSealed(id: String, account: OtpAccount, createdAt: Long): AccountEntity {
        val earlier = testDataKeyHolder(keyTable, wrappers)
        val row = runBlocking {
            val version = earlier.prepareWriteKey()
            val sealed = DataKeyCipher.seal(earlier.writeKey(version), id, account.secret, random)
            assertEquals(version, sealed.keyVersion)
            account.toEntity(id, sealed, createdAt)
        }
        assertEquals(2, row.secretBlob[0].toInt())
        dao.put(row)
        return row
    }

    private fun putLegacy(id: String, account: OtpAccount, createdAt: Long): AccountEntity =
        account.toEntity(id, legacyCipher.encrypt(account.secret, accountAssociatedData(id)), createdAt)
            .also { dao.put(it) }

    private fun resetCounters() {
        wrappers.wraps.set(0)
        wrappers.unwraps.set(0)
        keyTable.calls.set(0)
        keystore.generations.set(0)
    }
}
