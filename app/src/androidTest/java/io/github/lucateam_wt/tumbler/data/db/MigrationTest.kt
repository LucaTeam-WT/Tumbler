package io.github.lucateam_wt.tumbler.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migration tests. Schema JSON files in `app/schemas` are packaged as androidTest assets (see
 * `app/build.gradle.kts`), which is what [MigrationTestHelper] reads. `DatabaseMigrationsTest`
 * checks the migration SQL against the same files on the JVM.
 *
 * HOW TO ADD A TEST FOR EACH FUTURE MIGRATION (e.g. 2 -> 3):
 * 1. Bump `version` in `TumblerDatabase`, add `MIGRATION_2_3` to [DatabaseMigrations] and to
 *    `DatabaseMigrations.ALL`. Never add a destructive fallback.
 * 2. Build once so `app/schemas/.../3.json` is exported, and commit it.
 * 3. Add a test here: `helper.createDatabase(DB_NAME, 2)`, insert rows with plain SQL
 *    (use made-up data, never real secrets), then
 *    `helper.runMigrationsAndValidate(DB_NAME, 3, true, MIGRATION_2_3)` and assert the
 *    rows survived with the expected values.
 * 4. Also open the migrated file through Room with all migrations registered.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        TumblerDatabase::class.java,
    )

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    @After
    fun deleteDatabase() {
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun currentSchemaVersion_createsAndOpensWithRoom() {
        helper.createDatabase(DB_NAME, CURRENT_VERSION).close()

        val db = TumblerDatabase.open(context, DB_NAME)
        try {
            assertEquals(CURRENT_VERSION, db.openHelper.readableDatabase.version)
        } finally {
            db.close()
        }
    }

    @Test
    fun migrate1To2_keepsEveryRowAsALegacyRowAndAddsAnEmptyKeyTable() {
        // Made-up payloads in the legacy layout (01 0c, 12-byte IV, ciphertext and tag).
        val totpBlob = ByteArray(46) { (it + 1).toByte() }.also { it[0] = 1; it[1] = 12 }
        val hotpBlob = ByteArray(50) { (100 - it).toByte() }.also { it[0] = 1; it[1] = 12 }
        helper.createDatabase(DB_NAME, 1).use { db ->
            db.execSQL(INSERT_V1, arrayOf<Any>("id-1", "Example", "alice", "TOTP", "SHA1", 6, 30, 0L, totpBlob, 1_000L))
            db.execSQL(INSERT_V1, arrayOf<Any>("id-2", "Bank", "bob", "HOTP", "SHA256", 8, 30, 7L, hotpBlob, 2_000L))
        }

        helper.runMigrationsAndValidate(DB_NAME, 2, true, DatabaseMigrations.MIGRATION_1_2).use { db ->
            db.query(
                "SELECT id, issuer, accountName, type, algorithm, digits, period, counter, secretBlob, createdAt, " +
                    "keyVersion, legacySecretBlob FROM accounts ORDER BY id",
            ).use { c ->
                assertEquals(2, c.count)
                assertTrue(c.moveToFirst())
                assertEquals("id-1", c.getString(0))
                assertEquals("Example", c.getString(1))
                assertEquals("alice", c.getString(2))
                assertEquals("TOTP", c.getString(3))
                assertEquals("SHA1", c.getString(4))
                assertEquals(6, c.getInt(5))
                assertEquals(30, c.getInt(6))
                assertEquals(0L, c.getLong(7))
                assertArrayEquals(totpBlob, c.getBlob(8))
                assertEquals(1_000L, c.getLong(9))
                assertEquals(0, c.getInt(10))
                assertTrue(c.isNull(11))

                assertTrue(c.moveToNext())
                assertEquals("id-2", c.getString(0))
                assertEquals("HOTP", c.getString(3))
                assertEquals("SHA256", c.getString(4))
                assertEquals(8, c.getInt(5))
                assertEquals(7L, c.getLong(7))
                assertArrayEquals(hotpBlob, c.getBlob(8))
                assertEquals(2_000L, c.getLong(9))
                assertEquals(0, c.getInt(10))
                assertTrue(c.isNull(11))
            }
            db.query("SELECT COUNT(*) FROM data_keys").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(0, c.getInt(0))
            }
        }

        // Room opens the migrated file with every migration registered and validates it.
        val room = TumblerDatabase.open(context, DB_NAME)
        try {
            runBlocking {
                val rows = room.accountDao().getAll()
                assertEquals(listOf("id-1", "id-2"), rows.map { it.id })
                assertTrue(rows.all { it.keyVersion == 0 && it.legacySecretBlob == null })
                assertArrayEquals(hotpBlob, rows[1].secretBlob)
                assertEquals(0, room.accountDao().highestKeyVersion())
                assertTrue(room.dataKeyDao().getAll().isEmpty())
                // The unchanged compare-and-set still matches a migrated HOTP row.
                assertEquals(1, room.accountDao().advanceCounter("id-2", 7L, hotpBlob))
                assertEquals(8L, room.accountDao().getById("id-2")?.counter)
            }
        } finally {
            room.close()
        }
    }

    private companion object {
        const val DB_NAME = "migration-test.db"
        const val CURRENT_VERSION = 2
        const val INSERT_V1 =
            "INSERT INTO accounts (id, issuer, accountName, type, algorithm, digits, period, counter, " +
                "secretBlob, createdAt) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
    }
}
