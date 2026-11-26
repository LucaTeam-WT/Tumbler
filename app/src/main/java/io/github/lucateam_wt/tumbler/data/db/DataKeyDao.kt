package io.github.lucateam_wt.tumbler.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * The `data_keys` table. There is no update and no delete: a stored data key is never changed or
 * removed, because rows sealed with it would become unreadable.
 */
@Dao
interface DataKeyDao {

    @Query("SELECT * FROM data_keys WHERE version = :version")
    suspend fun get(version: Int): DataKeyEntity?

    @Query("SELECT * FROM data_keys ORDER BY version ASC")
    suspend fun getAll(): List<DataKeyEntity>

    /**
     * Inserts [entity] unless a key with its version exists, which is then left unchanged
     * (`INSERT OR IGNORE`, never `REPLACE`).
     *
     * @return the new row id (the version), or -1 if a key with this version exists.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entity: DataKeyEntity): Long
}
