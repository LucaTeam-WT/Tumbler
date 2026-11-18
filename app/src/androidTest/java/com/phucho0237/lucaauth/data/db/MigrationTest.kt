package com.phucho0237.lucaauth.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migration test harness. Schema JSON files in `app/schemas` are packaged as androidTest
 * assets (see `app/build.gradle.kts`), which is what [MigrationTestHelper] reads.
 *
 * Today there is only version 1 and no migration, so this only proves the harness works:
 * the exported v1 schema can be created and opened by Room.
 *
 * HOW TO ADD A TEST FOR EACH FUTURE MIGRATION (e.g. 1 -> 2):
 * 1. Bump `version` in `LucaAuthDatabase`, write `Migration(1, 2)` and register it with
 *    `.addMigrations(...)` in `LucaAuthDatabase.open`. Never add a destructive fallback.
 * 2. Build once so `app/schemas/.../2.json` is exported, and commit it.
 * 3. Add a test here: `helper.createDatabase(DB_NAME, 1)`, insert rows with plain SQL
 *    (use made-up data, never real secrets), then
 *    `helper.runMigrationsAndValidate(DB_NAME, 2, true, MIGRATION_1_2)` and assert the
 *    rows survived with the expected values.
 * 4. Also open the migrated file through Room (as below) with all migrations registered.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        LucaAuthDatabase::class.java,
    )

    @Test
    fun currentSchemaVersion_createsAndOpensWithRoom() {
        helper.createDatabase(DB_NAME, CURRENT_VERSION).close()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = LucaAuthDatabase.open(context, DB_NAME)
        try {
            assertEquals(CURRENT_VERSION, db.openHelper.readableDatabase.version)
        } finally {
            db.close()
            context.deleteDatabase(DB_NAME)
        }
    }

    private companion object {
        const val DB_NAME = "migration-test.db"
        const val CURRENT_VERSION = 1
    }
}
