package io.github.lucateam_wt.tumbler.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {

    /** All accounts without their secret, ordered by [AccountEntity.createdAt] then id. */
    @Query(
        "SELECT id, issuer, accountName, type, algorithm, digits, period, counter " +
            "FROM accounts ORDER BY createdAt ASC, id ASC",
    )
    fun observeAll(): Flow<List<AccountSummaryRow>>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun getById(id: String): AccountEntity?

    /** Every row, in the order of [observeAll]. */
    @Query("SELECT * FROM accounts ORDER BY createdAt ASC, id ASC")
    suspend fun getAll(): List<AccountEntity>

    /** @throws android.database.sqlite.SQLiteConstraintException if [entity]'s id exists. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: AccountEntity)

    /**
     * Compare-and-set for "next code": adds 1 to the counter of HOTP row [id] only while it
     * still holds [expectedCounter] and [expectedSecretBlob] and the counter is below
     * `Long.MAX_VALUE`. `'HOTP'` is the stored name of
     * [io.github.lucateam_wt.tumbler.domain.model.OtpType.HOTP].
     *
     * @return the number of rows updated: 1, or 0 if the row is unknown, not HOTP, changed
     *   since it was read, or at the counter limit.
     */
    @Query(
        "UPDATE accounts SET counter = counter + 1 WHERE id = :id AND type = 'HOTP' " +
            "AND counter = :expectedCounter AND secretBlob = :expectedSecretBlob " +
            "AND counter < 9223372036854775807",
    )
    suspend fun advanceCounter(id: String, expectedCounter: Long, expectedSecretBlob: ByteArray): Int

    /**
     * Overwrites every column of row [id] except `id` and `createdAt`, which are kept, and
     * clears [AccountEntity.legacySecretBlob], so no copy of the replaced secret stays behind.
     *
     * @return the number of rows updated: 1, or 0 if [id] is unknown.
     */
    @Query(
        "UPDATE accounts SET issuer = :issuer, accountName = :accountName, type = :type, " +
            "algorithm = :algorithm, digits = :digits, period = :period, counter = :counter, " +
            "secretBlob = :secretBlob, keyVersion = :keyVersion, legacySecretBlob = NULL " +
            "WHERE id = :id",
    )
    suspend fun replace(
        id: String,
        issuer: String,
        accountName: String,
        type: String,
        algorithm: String,
        digits: Int,
        period: Int,
        counter: Long,
        secretBlob: ByteArray,
        keyVersion: Int,
    ): Int

    /** Deletes row [id], and with it its [AccountEntity.legacySecretBlob]. */
    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun delete(id: String)

    /** The highest [AccountEntity.keyVersion] of any row, or `null` if there is no row. */
    @Query("SELECT MAX(keyVersion) FROM accounts")
    suspend fun highestKeyVersion(): Int?
}
