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
     * Updates only the counter, and only of a HOTP row. `'HOTP'` is the stored name of
     * [io.github.lucateam_wt.tumbler.domain.model.OtpType.HOTP].
     *
     * @return the number of rows updated: 1, or 0 if [id] is unknown or not a HOTP row.
     */
    @Query("UPDATE accounts SET counter = :counter WHERE id = :id AND type = 'HOTP'")
    suspend fun updateCounter(id: String, counter: Long): Int

    /**
     * Overwrites every column of row [id] except `id` and `createdAt`, which are kept.
     *
     * @return the number of rows updated: 1, or 0 if [id] is unknown.
     */
    @Query(
        "UPDATE accounts SET issuer = :issuer, accountName = :accountName, type = :type, " +
            "algorithm = :algorithm, digits = :digits, period = :period, counter = :counter, " +
            "secretBlob = :secretBlob WHERE id = :id",
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
    ): Int

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun delete(id: String)
}
