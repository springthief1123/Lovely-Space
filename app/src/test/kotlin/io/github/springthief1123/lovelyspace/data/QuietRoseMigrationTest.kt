package io.github.springthief1123.lovelyspace.data

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class QuietRoseMigrationTest {
    @Test fun v3SavedSearchSurvivesNewCombinedSearchField() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "quiet-migration-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        val schema = javaClass.getResourceAsStream("/io.github.springthief1123.lovelyspace.data.PresetDatabase/3.json")!!
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            old.execSQL("INSERT INTO search_presets VALUES ('saved', '保持する条件', 'zenkoku', '合成', '本文', '', 'ALL', NULL, 20, 40, 1, NULL, 1, NULL, 'SITE')")
            old.execSQL("INSERT INTO local_state VALUES ('preset_import', 'done')")
            old.version = 3
        }
        val db = Room.databaseBuilder(context, PresetDatabase::class.java, name).addMigrations(PresetDatabase.MIGRATION_3_4).build()
        try {
            val preset = db.searchPresets().presets().first().single()
            assertEquals("保持する条件", preset.label)
            assertEquals("合成", preset.criteria.name)
            assertEquals("本文", preset.criteria.message)
            assertEquals(20, preset.criteria.minAge)
            assertEquals("", preset.criteria.text)
            assertTrue(db.presets().imported())
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
