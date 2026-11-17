package com.phucho0237.lucaauth.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The app's Room database.
 *
 * There is deliberately no destructive migration fallback: if a migration is missing,
 * opening the database fails loudly instead of silently deleting every account. Each
 * schema version is exported to `app/schemas` and committed.
 */
@Database(entities = [AccountEntity::class], version = 1, exportSchema = true)
abstract class LucaAuthDatabase : RoomDatabase() {

    abstract fun accountDao(): AccountDao

    companion object {
        private const val DATABASE_NAME = "lucaauth.db"

        @Volatile
        private var instance: LucaAuthDatabase? = null

        /** Returns the process-wide database, opening it on first use. */
        fun getInstance(context: Context): LucaAuthDatabase =
            instance ?: synchronized(this) {
                instance ?: open(context, DATABASE_NAME).also { instance = it }
            }

        /** Opens the database file [name]; tests use their own name. */
        internal fun open(context: Context, name: String): LucaAuthDatabase =
            Room.databaseBuilder(context.applicationContext, LucaAuthDatabase::class.java, name)
                .build()
    }
}
