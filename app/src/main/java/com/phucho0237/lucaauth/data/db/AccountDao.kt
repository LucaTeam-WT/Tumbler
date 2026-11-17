package com.phucho0237.lucaauth.data.db

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

    /** @throws android.database.sqlite.SQLiteConstraintException if [entity]'s id exists. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: AccountEntity)

    /**
     * Updates only the counter, and only of a HOTP row. `'HOTP'` is the stored name of
     * [com.phucho0237.lucaauth.domain.model.OtpType.HOTP].
     */
    @Query("UPDATE accounts SET counter = :counter WHERE id = :id AND type = 'HOTP'")
    suspend fun updateCounter(id: String, counter: Long)

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun delete(id: String)
}
