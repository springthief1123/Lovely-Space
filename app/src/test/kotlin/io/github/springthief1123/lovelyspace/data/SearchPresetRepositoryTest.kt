package io.github.springthief1123.lovelyspace.data

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.springthief1123.lovelyspace.core.*
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
class SearchPresetRepositoryTest {
    @Test fun persistsEveryConditionEditsWithoutDuplicatesAndDeletesAcrossReopen() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "search-${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, PresetDatabase::class.java, name)
            .addMigrations(PresetDatabase.MIGRATION_1_2).build()
        var db = open()
        try {
            var repo = SearchPresetRepository(db)
            val value = SearchPreset("saved", "  よく使う条件  ", "talk", RoomSearchCriteria(
                name = "合成 名前", message = "募集 テスト", excluded = "除外語", keywordMode = KeywordMode.ANY,
                gender = Gender.FEMALE, minAge = 25, maxAge = 40, includeUnknownAge = false, area = Prefectures.names.first(),
                waitingOnly = true, publicOnly = false, sort = RoomSort.ELAPSED))
            repo.save(value)
            assertEquals(value.copy(label = value.label.trim()), repo.presets.first().single())
            val edited = value.copy(label = "編集後", genreKey = "kinki",
                criteria = RoomSearchCriteria(waitingOnly = false, publicOnly = true))
            repo.save(edited)
            assertEquals(edited, repo.presets.first().single())
            // nullは「すべて」、falseは「満室・非公開」。取り違えず保存する。
            repo.save(edited.copy(id = "all", label = "すべて", criteria = RoomSearchCriteria()))
            try {
                repo.save(value.copy(criteria = value.criteria.copy(minAge = 45, maxAge = 40)))
                fail("逆転した年齢範囲を保存できない")
            } catch (_: IllegalArgumentException) { }
            try {
                repo.save(value.copy(genreKey = "unknown"))
                fail("不明なジャンルを保存できない")
            } catch (_: IllegalArgumentException) { }
            assertEquals(edited, repo.presets.first().single { it.id == "saved" })
            db.close()
            db = open()
            repo = SearchPresetRepository(db)
            assertEquals(2, repo.presets.first().size)
            assertEquals(RoomSearchCriteria(), repo.presets.first().single { it.id == "all" }.criteria)
            repo.delete("saved")
            assertEquals(listOf("all"), repo.presets.first().map { it.id })
            db.close()
            db = open()
            assertEquals(listOf("all"), SearchPresetRepository(db).presets.first().map { it.id })
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun migratesExportedV1WithoutChangingProfilesMessagesOrImportMarker() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "migration-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        val schema = javaClass.getResourceAsStream("/io.github.springthief1123.lovelyspace.data.PresetDatabase/1.json")!!
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            old.execSQL("INSERT INTO profile_presets VALUES ('profile', '保持する設定', '合成の名前', 2, NULL, 13, 1)")
            old.execSQL("INSERT INTO message_presets VALUES ('message', '保持する募集文', '合成の本文', 1)")
            old.execSQL("INSERT INTO local_state VALUES ('preset_import', 'done')")
            old.version = 1
        }
        val db = Room.databaseBuilder(context, PresetDatabase::class.java, name)
            .addMigrations(PresetDatabase.MIGRATION_1_2).build()
        try {
            // Room自身による移行後スキーマ検証を通し、全フィールドと取り込み済み状態を確認。
            assertEquals(ProfilePreset("profile", "保持する設定", "合成の名前", 2, null, 13, true), db.presets().defaultProfile())
            assertEquals(MessagePreset("message", "保持する募集文", "合成の本文", true), db.presets().defaultMessage())
            assertTrue(db.presets().imported())
            val search = SearchPreset("search", "移行後", "zenkoku", RoomSearchCriteria())
            val repo = SearchPresetRepository(db)
            assertTrue(repo.presets.first().isEmpty())
            repo.save(search)
            assertEquals(search, repo.presets.first().single())
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
