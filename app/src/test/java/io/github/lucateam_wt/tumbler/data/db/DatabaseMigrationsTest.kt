package io.github.lucateam_wt.tumbler.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy

/**
 * Checks on the JVM that each migration's SQL builds exactly the schema Room exported to
 * `app/schemas`. `MigrationTest` checks the migrated database with Room on a device.
 */
class DatabaseMigrationsTest {

    /** Gradle runs unit tests in the module directory; an IDE runner may use the project root. */
    private val schemaDir = listOf("schemas", "app/schemas")
        .map { File(it, "io.github.lucateam_wt.tumbler.data.db.TumblerDatabase") }
        .firstOrNull { it.isDirectory }
        ?: throw AssertionError("Exported Room schemas not found")

    @Test
    fun `migration 1 to 2 runs exactly its documented statements and nothing else`() {
        val statements = mutableListOf<String>()
        val db = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            when {
                method.name == "execSQL" && args?.size == 1 -> statements += args[0] as String
                method.name == "toString" -> "RecordingDatabase"
                else -> throw AssertionError("The migration called ${method.name}")
            }
            null
        } as SupportSQLiteDatabase

        DatabaseMigrations.MIGRATION_1_2.migrate(db)

        assertEquals(DatabaseMigrations.MIGRATION_1_2_SQL, statements)
        // No statement reads, changes or deletes a row.
        statements.forEach { assertTrue(it, it.startsWith("ALTER TABLE") || it.startsWith("CREATE TABLE")) }
    }

    @Test
    fun `migration 1 to 2 adds exactly the columns of the exported version 2 accounts table`() {
        val v1 = createSql(1, "accounts")
        val v2 = createSql(2, "accounts")
        val added = DatabaseMigrations.MIGRATION_1_2_SQL
            .filter { it.startsWith("ALTER TABLE `accounts` ADD COLUMN ") }
            .map { it.removePrefix("ALTER TABLE `accounts` ADD COLUMN ") }
        assertEquals(2, added.size)

        val primaryKey = ", PRIMARY KEY(`id`))"
        assertTrue(v1.endsWith(primaryKey))
        val expected = v1.removeSuffix(primaryKey) + added.joinToString("") { ", $it" } + primaryKey
        assertEquals(expected, v2)
    }

    @Test
    fun `migration 1 to 2 creates the exported version 2 data_keys table`() {
        val create = DatabaseMigrations.MIGRATION_1_2_SQL.single { it.startsWith("CREATE TABLE") }
        assertEquals(createSql(2, "data_keys").replace("\${TABLE_NAME}", "data_keys"), create)
    }

    @Test
    fun `every exported version is reachable by registered migrations`() {
        val versions = schemaDir.listFiles()!!.map { it.name.removeSuffix(".json").toInt() }.sorted()
        assertEquals((1..versions.last()).toList(), versions)
        val steps = DatabaseMigrations.ALL.map { it.startVersion to it.endVersion }
        assertEquals(versions.zipWithNext(), steps)
    }

    /** The `createSql` of [table] in the exported schema [version], with `${TABLE_NAME}` kept. */
    private fun createSql(version: Int, table: String): String {
        val json = File(schemaDir, "$version.json").readText()
        assertTrue(json.contains("\"version\": $version,"))
        val entity = Regex("\"tableName\": \"${Regex.escape(table)}\",\\s*\"createSql\": \"((?:[^\"\\\\]|\\\\.)*)\"")
            .find(json) ?: throw AssertionError("No table $table in $version.json")
        return entity.groupValues[1]
    }
}
