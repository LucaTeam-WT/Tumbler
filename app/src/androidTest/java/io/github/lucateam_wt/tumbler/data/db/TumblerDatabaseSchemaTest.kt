package io.github.lucateam_wt.tumbler.data.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TumblerDatabaseSchemaTest {

    @Test
    fun exportedSchemaVersion1_isPackaged() {
        // app/schemas is added to the androidTest assets in app/build.gradle.kts.
        val json = InstrumentationRegistry.getInstrumentation().context.assets
            .open("io.github.lucateam_wt.tumbler.data.db.TumblerDatabase/1.json")
            .bufferedReader()
            .use { it.readText() }

        assertTrue(json.contains("\"version\": 1,"))
        assertTrue(json.contains("\"tableName\": \"accounts\""))
        assertTrue(json.contains("`secretBlob` BLOB NOT NULL"))
    }
}
