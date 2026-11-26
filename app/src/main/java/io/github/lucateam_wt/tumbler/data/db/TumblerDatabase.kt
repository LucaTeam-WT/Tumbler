package io.github.lucateam_wt.tumbler.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The app's Room database.
 *
 * There is deliberately no destructive migration fallback: if a migration is missing,
 * opening the database fails loudly instead of silently deleting every account. Each
 * schema version is exported to `app/schemas` and committed, and every migration is in
 * [DatabaseMigrations].
 */
@Database(entities = [AccountEntity::class, DataKeyEntity::class], version = 2, exportSchema = true)
abstract class TumblerDatabase : RoomDatabase() {

    abstract fun accountDao(): AccountDao

    abstract fun dataKeyDao(): DataKeyDao

    companion object {
        private const val DATABASE_NAME = "lucaauth.db"

        @Volatile
        private var instance: TumblerDatabase? = null

        /** Returns the process-wide database, opening it on first use. */
        fun getInstance(context: Context): TumblerDatabase =
            instance ?: synchronized(this) {
                instance ?: open(context, DATABASE_NAME).also { instance = it }
            }

        /** Opens the database file [name]; tests use their own name. */
        internal fun open(context: Context, name: String): TumblerDatabase =
            Room.databaseBuilder(context.applicationContext, TumblerDatabase::class.java, name)
                .addMigrations(*DatabaseMigrations.ALL)
                .build()
    }
}
