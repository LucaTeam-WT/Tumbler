package io.github.lucateam_wt.tumbler.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Every schema migration, registered in [TumblerDatabase.open]. There is no destructive fallback.
 * The SQL of each migration must produce exactly the schema exported to `app/schemas`:
 * `DatabaseMigrationsTest` checks the text on the JVM and `MigrationTest` checks the result with
 * Room on a device.
 */
internal object DatabaseMigrations {

    /**
     * 1 -> 2, envelope encryption (`docs/storage-format.md`): each existing row keeps its legacy
     * secret unchanged and gets `keyVersion` 0 and no `legacySecretBlob`; `data_keys` starts empty.
     * No secret is read, decrypted or rewritten, and no Keystore key is touched.
     */
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            MIGRATION_1_2_SQL.forEach(db::execSQL)
        }
    }

    internal val MIGRATION_1_2_SQL = listOf(
        "ALTER TABLE `accounts` ADD COLUMN `keyVersion` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `accounts` ADD COLUMN `legacySecretBlob` BLOB",
        "CREATE TABLE IF NOT EXISTS `data_keys` (`version` INTEGER NOT NULL, `kekAlias` TEXT NOT NULL, " +
            "`wrappedKey` BLOB NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`version`))",
    )

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}
