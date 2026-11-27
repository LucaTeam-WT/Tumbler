package io.github.lucateam_wt.tumbler.data.repository

import io.github.lucateam_wt.tumbler.data.crypto.AesGcmSecretCipher
import io.github.lucateam_wt.tumbler.data.crypto.KeyProvider
import io.github.lucateam_wt.tumbler.data.db.AccountEntity
import io.github.lucateam_wt.tumbler.domain.crypto.KeyUnavailableException
import io.github.lucateam_wt.tumbler.domain.crypto.NewStorageKeyRequiredException
import io.github.lucateam_wt.tumbler.domain.crypto.SecretCipher
import io.github.lucateam_wt.tumbler.domain.crypto.SecretCipherException
import io.github.lucateam_wt.tumbler.domain.model.OtpAccount
import io.github.lucateam_wt.tumbler.domain.model.OtpType
import io.github.lucateam_wt.tumbler.domain.model.StoredAccount
import io.github.lucateam_wt.tumbler.domain.otp.Base32
import io.github.lucateam_wt.tumbler.domain.otp.OtpAlgorithm
import io.github.lucateam_wt.tumbler.domain.repository.UnreadableAccountException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** [RoomAccountBackupStore] on [FakeAccountDao]; real Room transactions are covered by the instrumented test. */
class RoomAccountBackupStoreTest {

    /** A lock regression in the data-key code would hang instead of failing. */
    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

    /** Plain JVM AES key; [lost] simulates the Keystore entry being deleted. */
    private class TestKeyProvider : KeyProvider {
        private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        var lost = false

        override fun getOrCreateKey(): SecretKey = key
        override fun getExistingKey(): SecretKey? = if (lost) null else key
    }

    /** Remembers every plaintext it hands out, so tests can check that it was wiped. */
    private class RecordingCipher(private val delegate: SecretCipher) : SecretCipher {
        val decrypted = mutableListOf<ByteArray>()

        /** Encryptions made through the store or repository (none: they seal with the data key). */
        var encrypts = 0
            private set

        /** Runs after each decryption, before the plaintext is returned. */
        var afterDecrypt: (() -> Unit)? = null

        /** Seeds a legacy row, as the app did before the data key. */
        fun seal(plaintext: ByteArray, associatedData: ByteArray): ByteArray = delegate.encrypt(plaintext, associatedData)

        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
            encrypts++
            return delegate.encrypt(plaintext, associatedData)
        }

        override fun decrypt(payload: ByteArray, associatedData: ByteArray): ByteArray =
            delegate.decrypt(payload, associatedData).also {
                decrypted += it
                afterDecrypt?.invoke()
            }
    }

    /** Runs the block as one transaction would: every row is restored if it throws. */
    private class RollbackRunner(private val dao: FakeAccountDao) : TransactionRunner {
        var transactions = 0
            private set

        override suspend fun <R> inTransaction(block: suspend () -> R): R {
            transactions++
            val before = dao.snapshot()
            try {
                return block()
            } catch (t: Throwable) {
                dao.restore(before)
                throw t
            }
        }
    }

    private val keyProvider = TestKeyProvider()
    private val dao = FakeAccountDao()
    private val cipher = RecordingCipher(AesGcmSecretCipher(keyProvider))
    private val runner = RollbackRunner(dao)
    private val dataKeys = linkedDataKeyHolder(dao)
    private var nextId = 0
    private var now = 1_000L
    private val repository = RoomAccountRepository(dao, cipher, dataKeys, runner, idGenerator = { "id-${nextId++}" }, clock = { now++ })
    private val store = RoomAccountBackupStore(dao, cipher, dataKeys, runner, idGenerator = { "id-${nextId++}" })

    private val totpSecret = Base32.decode("JBSWY3DPEHPK3PXP")
    private val hotpSecret = Base32.decode("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")
    private val totp = OtpAccount("Example", "alice@example.com", OtpType.TOTP, totpSecret, OtpAlgorithm.SHA256, 8, 60)
    private val hotp = OtpAccount("Bank", "bob", OtpType.HOTP, hotpSecret, counter = 5)

    private fun ByteArray.containsSequence(needle: ByteArray): Boolean =
        (0..size - needle.size).any { start -> needle.indices.all { this[start + it] == needle[it] } }

    private fun AccountEntity.with(type: String = this.type, digits: Int = this.digits, createdAt: Long = this.createdAt) =
        AccountEntity(id, issuer, accountName, type, algorithm, digits, period, counter, secretBlob, createdAt, keyVersion, legacySecretBlob)

    /** Stores [account] in the legacy format, as the app did before the data key (a row not migrated yet). */
    private fun addLegacy(account: OtpAccount): String {
        val id = "id-${nextId++}"
        dao.put(account.toEntity(id, cipher.seal(account.secret, accountAssociatedData(id)), now++))
        return id
    }

    @Test
    fun insertAndReplace_sealWithTheDataKeyAndReplaceDropsTheLegacyCopy() = runBlocking {
        val legacyId = addLegacy(totp)
        dao.put(dao.stored(legacyId).copyWith(legacySecretBlob = dao.stored(legacyId).secretBlob))

        val inserted = store.writeTransaction {
            replace(legacyId, hotp)
            insert(totp, createdAtMillis = 3)
        }

        for (id in listOf(legacyId, inserted)) {
            val row = dao.stored(id)
            assertEquals(1, row.keyVersion)
            assertEquals(2, row.secretBlob[0].toInt())
            assertNull(row.legacySecretBlob)
        }
        assertEquals(hotp, repository.getAccount(legacyId))
        assertEquals(totp, repository.getAccount(inserted))
        assertEquals(0, cipher.encrypts)
    }

    @Test
    fun beforeAccess_runsBeforeEveryReadAndTransaction() = runBlocking {
        addLegacy(totp)
        val gate = CompletableDeferred<Unit>()
        var waits = 0
        val gated = RoomAccountBackupStore(
            dao, cipher, dataKeys, runner,
            beforeAccess = {
                waits++
                gate.await()
            },
        )
        val transactions = runner.transactions
        val read = async { gated.readAll() }
        val written = async { gated.writeTransaction { insert(hotp, 1) } }
        yield()
        assertFalse(read.isCompleted || written.isCompleted)
        assertEquals(transactions, runner.transactions)
        assertEquals(1, dao.size)

        gate.complete(Unit)
        assertEquals(1, read.await().size)
        written.await()
        assertEquals(2, waits)
        assertEquals(2, dao.size)
    }

    @Test
    fun readAll_returnsEveryAccountOldestFirstWithItsCreationTime() = runBlocking {
        val hotpId = repository.add(hotp)
        val totpId = repository.add(totp)

        val all = store.readAll()

        assertEquals(listOf(hotpId, totpId), all.map { it.id })
        assertEquals(listOf(hotp, totp), all.map { it.account })
        assertEquals(listOf(1_000L, 1_001L), all.map { it.createdAtMillis })
    }

    @Test
    fun readAll_returnsNewArraysThatTheCallerMayWipe() = runBlocking {
        repository.add(totp)
        store.readAll().single().account.secret.fill(0)
        assertArrayEquals(totpSecret, store.readAll().single().account.secret)
    }

    @Test
    fun readAll_corruptedSecret_namesTheAccountAndWipesWhatWasDecrypted() = runBlocking {
        addLegacy(totp)
        val badId = addLegacy(hotp)
        val blob = dao.stored(badId).secretBlob.copyOf()
        blob[blob.lastIndex] = (blob[blob.lastIndex].toInt() xor 1).toByte()
        dao.put(dao.stored(badId).copyWith(secretBlob = blob))
        cipher.decrypted.clear()

        val error = assertThrows(UnreadableAccountException::class.java) { runBlocking { store.readAll() } }

        assertEquals(badId, error.accountId)
        assertTrue(error.cause is SecretCipherException)
        val message = error.message.orEmpty()
        assertFalse(message, message.contains(badId) || message.contains("Bank") || message.contains("bob"))
        // The account decrypted before the unreadable one was wiped.
        assertTrue(cipher.decrypted.single().all { it == 0.toByte() })
    }

    @Test
    fun readAll_callerCancelledWhileASecretIsDecrypted_wipesThatSecretAndTheOnesBefore() = runBlocking {
        addLegacy(totp)
        addLegacy(hotp)
        cipher.decrypted.clear()
        lateinit var reader: Job
        // The cipher runs on the store's dispatcher (IO); cancelling the caller there makes
        // withContext drop the plaintext it is about to return.
        cipher.afterDecrypt = { if (cipher.decrypted.size == 2) reader.cancel() }

        reader = launch { store.readAll() }
        reader.join()

        assertTrue(reader.isCancelled)
        assertEquals(2, cipher.decrypted.size)
        assertTrue("earlier secret", cipher.decrypted[0].all { it == 0.toByte() })
        assertTrue("secret dropped by the cancellation", cipher.decrypted[1].all { it == 0.toByte() })
    }

    @Test
    fun readAll_callerCancelledWhileTheOnlySecretIsDecrypted_wipesIt() = runBlocking {
        addLegacy(hotp)
        cipher.decrypted.clear()
        lateinit var reader: Job
        cipher.afterDecrypt = { reader.cancel() }

        reader = launch { store.writeTransaction { readAll() } }
        reader.join()

        assertTrue(reader.isCancelled)
        assertTrue(cipher.decrypted.single().all { it == 0.toByte() })
    }

    @Test
    fun readAll_keyLost_isUnreadableWithTheKeyErrorAsCause() = runBlocking {
        val id = addLegacy(totp)
        keyProvider.lost = true

        val error = assertThrows(UnreadableAccountException::class.java) { runBlocking { store.readAll() } }

        assertEquals(id, error.accountId)
        assertTrue(error.cause is KeyUnavailableException)
    }

    @Test
    fun readAll_rowTheAppRejects_isUnreadableAndItsSecretIsWiped() = runBlocking {
        val changes = listOf<(AccountEntity) -> AccountEntity>(
            { it.with(type = "UNKNOWN") },
            { it.with(digits = 3) },
            { it.with(createdAt = -1) },
        )
        for ((index, change) in changes.withIndex()) {
            val id = addLegacy(totp)
            dao.put(change(dao.stored(id)))
            cipher.decrypted.clear()

            val error = assertThrows("change $index", UnreadableAccountException::class.java) { runBlocking { store.readAll() } }

            assertEquals("change $index", id, error.accountId)
            assertTrue("change $index", error.cause is IllegalArgumentException)
            assertTrue("change $index", cipher.decrypted.single().all { it == 0.toByte() })
            repository.delete(id)
        }
    }

    @Test
    fun insert_storesTheAccountEncryptedUnderItsNewIdWithTheGivenCreationTime() = runBlocking {
        val id = store.writeTransaction { insert(hotp, createdAtMillis = 42) }

        assertEquals(hotp, repository.getAccount(id))
        assertEquals(42L, dao.stored(id).createdAt)
        assertFalse(dao.stored(id).secretBlob.containsSequence(hotpSecret))
        assertEquals(1, runner.transactions)
    }

    @Test
    fun insert_rejectsANegativeCreationTime() = runBlocking {
        assertThrows(IllegalArgumentException::class.java) { runBlocking { store.writeTransaction { insert(totp, -1) } } }
        assertEquals(0, dao.size)
    }

    @Test
    fun replace_overwritesEveryFieldAndTheCounterAsGivenButKeepsIdAndCreationTime() = runBlocking {
        val id = repository.add(hotp)
        val newTotp = OtpAccount("Example", "carol", OtpType.TOTP, totpSecret, OtpAlgorithm.SHA512, 7, 45)
        store.writeTransaction { replace(id, newTotp) }
        assertEquals(newTotp, repository.getAccount(id))
        assertEquals(1_000L, dao.stored(id).createdAt)

        // The store writes the counter it is given; the counter policy is the caller's job.
        store.writeTransaction { replace(id, hotp.copy(counter = 2)) }
        assertEquals(hotp.copy(counter = 2), repository.getAccount(id))
        assertEquals(listOf(id), store.readAll().map { it.id })
    }

    @Test
    fun replace_unknownId_throwsWithoutTheIdAndChangesNothing() = runBlocking {
        val id = repository.add(totp)

        val error = assertThrows(NoSuchElementException::class.java) {
            runBlocking { store.writeTransaction { replace("missing-id", hotp) } }
        }

        assertFalse(error.message.orEmpty().contains("missing-id"))
        assertEquals(listOf(totp), store.readAll().map { it.account })
        assertEquals(listOf(id), store.readAll().map { it.id })
    }

    @Test
    fun writeTransaction_runsTheWholeBlockInOneTransactionAndPropagatesItsFailure() = runBlocking {
        val id = repository.add(hotp)
        val transactions = runner.transactions
        val failure = IllegalStateException("abort")

        val thrown = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                store.writeTransaction {
                    insert(totp, 7)
                    replace(id, hotp.copy(counter = 99))
                    throw failure
                }
            }
        }

        assertSame(failure, thrown)
        assertEquals(transactions + 1, runner.transactions)
        assertEquals(listOf(id), store.readAll().map { it.id })
        assertEquals(hotp, repository.getAccount(id))
    }

    @Test
    fun writeTransaction_noUsableWriteKey_writesNothingButStillRunsABlockThatOnlyReads() = runBlocking {
        val id = addLegacy(hotp)
        // A row names data key 4, whose record is gone: a new key must be started first.
        dao.put(dao.stored(addLegacy(totp)).copyWith(keyVersion = 4))
        val transactions = runner.transactions

        assertThrows(NewStorageKeyRequiredException::class.java) { runBlocking { store.writeTransaction { insert(totp, 1) } } }
        assertThrows(NewStorageKeyRequiredException::class.java) { runBlocking { store.writeTransaction { replace(id, totp) } } }
        assertEquals(42, store.writeTransaction { 42 })

        assertEquals(transactions + 3, runner.transactions)
        assertEquals(2, dao.size)
        assertEquals(hotp, repository.getAccount(id))
        assertEquals(0, dao.stored(id).keyVersion)
    }

    @Test
    fun writer_readAllSeesTheTransactionsOwnWrites() = runBlocking {
        val first = repository.add(totp)
        val ids = store.writeTransaction {
            val added = insert(hotp, createdAtMillis = 5)
            listOf(added) + readAll().map { it.id }
        }
        assertEquals(listOf(ids[0], ids[0], first), ids)
    }

    @Test
    fun writer_cannotBeUsedAfterItsTransaction() = runBlocking {
        val id = repository.add(totp)
        val leaked = store.writeTransaction { this }

        assertThrows(IllegalStateException::class.java) { runBlocking { leaked.insert(hotp, 1) } }
        assertThrows(IllegalStateException::class.java) { runBlocking { leaked.replace(id, hotp) } }
        assertThrows(IllegalStateException::class.java) { runBlocking { leaked.readAll() } }
        assertEquals(listOf(totp), store.readAll().map { it.account })
    }

    @Test
    fun storedAccount_hidesItsIdLabelsAndSecretAndRejectsANegativeCreationTime() {
        val text = StoredAccount("row-id-123", totp, 1).toString()
        for (hidden in listOf("row-id-123", "Example", "alice", "JBSWY3DPEHPK3PXP", totpSecret.contentToString())) {
            assertFalse(text, text.contains(hidden))
        }
        assertThrows(IllegalArgumentException::class.java) { StoredAccount("id", totp, -1) }
    }
}
