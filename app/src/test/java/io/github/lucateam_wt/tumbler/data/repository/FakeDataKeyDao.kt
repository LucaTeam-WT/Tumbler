package io.github.lucateam_wt.tumbler.data.repository

import io.github.lucateam_wt.tumbler.data.db.DataKeyDao
import io.github.lucateam_wt.tumbler.data.db.DataKeyEntity
import java.util.concurrent.ConcurrentSkipListMap

/** The `data_keys` table in memory. Mirrors `INSERT OR IGNORE`: -1 when the version exists, which is left unchanged. */
class FakeDataKeyDao : DataKeyDao {

    val rows = ConcurrentSkipListMap<Int, DataKeyEntity>()

    override suspend fun get(version: Int): DataKeyEntity? = rows[version]

    override suspend fun getAll(): List<DataKeyEntity> = rows.values.toList()

    override suspend fun insertIfAbsent(entity: DataKeyEntity): Long =
        if (rows.putIfAbsent(entity.version, entity) == null) entity.version.toLong() else -1
}
