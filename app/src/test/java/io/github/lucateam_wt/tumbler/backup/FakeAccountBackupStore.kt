package io.github.lucateam_wt.tumbler.backup

import io.github.lucateam_wt.tumbler.domain.model.OtpAccount
import io.github.lucateam_wt.tumbler.domain.model.StoredAccount
import io.github.lucateam_wt.tumbler.domain.repository.AccountBackupStore
import io.github.lucateam_wt.tumbler.domain.repository.AccountBackupWriter

/**
 * In-memory [AccountBackupStore] with the real store's semantics: reads return new secret
 * arrays, writes keep their own copy (the real store encrypts), and a transaction whose block
 * throws restores every row. Failures (also in and after a commit) and concurrent writes can be injected.
 */
internal class FakeAccountBackupStore : AccountBackupStore {

    private class Row(val account: OtpAccount, val createdAt: Long)

    private var rows = LinkedHashMap<String, Row>()
    private var nextId = 0
    private var writeCalls = 0

    var transactions = 0
        private set
    var commits = 0
        private set

    /** Inserts and replacements performed, including ones a rollback undid. */
    var writes = 0
        private set

    /** The thread of every read and transaction. */
    val threads = mutableListOf<String>()

    /** Every secret array a read handed out, so tests can check that the caller wiped it. */
    val handedOut = mutableListOf<ByteArray>()

    /** Thrown by [readAll] outside a transaction. */
    var readFailure: Throwable? = null

    /** Thrown by the writer's readAll inside a transaction. */
    var transactionReadFailure: Throwable? = null

    /** Thrown by the write after the first [writesBeforeFailure] writes. */
    var writeFailure: Throwable? = null
    var writesBeforeFailure = 0

    /** Runs when a transaction starts, before its block: another write that committed just before it. */
    var beforeTransaction: (suspend () -> Unit)? = null

    /**
     * Runs after a transaction committed, before writeTransaction returns, where Room resumes
     * its caller. What it throws reaches the caller; the commit stands.
     */
    var afterCommit: (suspend () -> Unit)? = null

    /** Thrown by the commit, after the block returned: the commit fails and every row is restored. */
    var commitFailure: Throwable? = null

    /**
     * If set, every read returns a list whose first iteration throws an [OutOfMemoryError] at
     * its second element, as allocating while converting the rows could. Later iterations
     * and indexed reads work, so the caller can still wipe what it was given.
     */
    var conversionRunsOutOfMemory = false

    fun add(account: OtpAccount, createdAtMillis: Long = 0): String {
        val id = "row-${nextId++}"
        rows[id] = Row(account.deepCopy(), createdAtMillis)
        return id
    }

    fun account(id: String): OtpAccount = rows.getValue(id).account.deepCopy()

    fun createdAt(id: String): Long = rows.getValue(id).createdAt

    fun update(id: String, change: (OtpAccount) -> OtpAccount) {
        val row = rows.getValue(id)
        rows[id] = Row(change(row.account.deepCopy()), row.createdAt)
    }

    fun setCounter(id: String, counter: Long) = update(id) { it.copy(counter = counter) }

    fun delete(id: String) {
        rows.remove(id)
    }

    val ids: List<String> get() = sorted().map { it.key }

    fun accounts(): List<OtpAccount> = sorted().map { it.value.account.deepCopy() }

    private fun sorted() = rows.entries.sortedWith(compareBy({ it.value.createdAt }, { it.key }))

    private fun read(): List<StoredAccount> {
        threads += currentThreadName()
        val accounts = sorted().map { (id, row) ->
            StoredAccount(id, row.account.deepCopy(), row.createdAt).also { handedOut += it.account.secret }
        }
        return if (conversionRunsOutOfMemory) FailsOnFirstIteration(accounts) else accounts
    }

    private class FailsOnFirstIteration<T>(private val items: List<T>) : AbstractList<T>() {
        private var iterations = 0

        override val size: Int get() = items.size

        override fun get(index: Int): T = items[index]

        override fun iterator(): Iterator<T> {
            val first = iterations++ == 0
            val delegate = items.iterator()
            var index = 0
            return object : Iterator<T> {
                override fun hasNext(): Boolean = delegate.hasNext()

                override fun next(): T {
                    if (first && index++ == 1) throw OutOfMemoryError("test: converting the rows")
                    return delegate.next()
                }
            }
        }
    }

    override suspend fun readAll(): List<StoredAccount> {
        readFailure?.let { throw it }
        return read()
    }

    override suspend fun <R> writeTransaction(block: suspend AccountBackupWriter.() -> R): R {
        threads += currentThreadName()
        beforeTransaction?.invoke()
        transactions++
        val before = LinkedHashMap(rows)
        val writer = Writer()
        val result = try {
            writer.block().also {
                commitFailure?.let { throw it }
                commits++
            }
        } catch (t: Throwable) {
            rows = before
            throw t
        } finally {
            writer.open = false
        }
        afterCommit?.invoke()
        return result
    }

    private inner class Writer : AccountBackupWriter {
        var open = true

        override suspend fun readAll(): List<StoredAccount> {
            check(open)
            transactionReadFailure?.let { throw it }
            return read()
        }

        override suspend fun insert(account: OtpAccount, createdAtMillis: Long): String {
            check(open)
            failIfDue()
            require(createdAtMillis >= 0)
            return add(account, createdAtMillis)
        }

        override suspend fun replace(id: String, account: OtpAccount) {
            check(open)
            failIfDue()
            val row = rows[id] ?: throw NoSuchElementException("No account to replace")
            rows[id] = Row(account.deepCopy(), row.createdAt)
        }

        private fun failIfDue() {
            writes++
            val failure = writeFailure ?: return
            if (writeCalls++ >= writesBeforeFailure) throw failure
        }
    }
}

internal fun OtpAccount.deepCopy(): OtpAccount = copy(secret = secret.copyOf())

internal fun ByteArray.isWiped(): Boolean = all { it == 0.toByte() }

/** The thread name without the " @coroutine#n" suffix that coroutine debug mode adds. */
internal fun currentThreadName(): String = Thread.currentThread().name.substringBefore(" @coroutine")
