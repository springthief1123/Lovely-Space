package io.github.springthief1123.lovelyspace.data

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.springthief1123.lovelyspace.core.Gender
import io.github.springthief1123.lovelyspace.core.Room as SiteRoom
import io.github.springthief1123.lovelyspace.core.RoomAction
import io.github.springthief1123.lovelyspace.core.RoomSearchCriteria
import io.github.springthief1123.lovelyspace.core.RoomStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class RoomPreferenceRepositoryTest {
    private fun room(
        id: Long,
        genre: String,
        name: String?,
        gender: Gender = Gender.FEMALE,
        age: Int? = 25,
        message: String = "募集",
    ) = SiteRoom(
        id = id,
        genreKey = genre,
        status = RoomStatus.WAITING,
        action = RoomAction.ENTER,
        elapsed = 3.minutes,
        name = name,
        gender = gender,
        age = age,
        area = "大阪",
        message = message,
    )

    @Test fun persistsFavoritesAndHiddenSeparatelyByHostAndRejectsReusedIdentity() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "room-preferences-${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, PresetDatabase::class.java, name)
            .addMigrations(PresetDatabase.MIGRATION_1_2, PresetDatabase.MIGRATION_2_3, PresetDatabase.MIGRATION_3_4)
            .build()
        var db = open()
        try {
            var repo = RoomPreferenceRepository(db)
            val chatRoom = room(42, "zenkoku", "ミナ")
            val lrRoom = room(42, "talk", "ユウ", Gender.MALE, 30)
            repo.setFavorite(chatRoom, true)
            repo.setHidden(chatRoom, true)
            var combined = repo.preferences.first().single()
            assertTrue(combined.favorite)
            assertTrue(combined.hidden)
            repo.setHidden(chatRoom, false)
            combined = repo.preferences.first().single()
            assertTrue(combined.favorite)
            assertFalse(combined.hidden)

            repo.setHidden(lrRoom, true)
            var saved = repo.preferences.first()
            assertEquals(2, saved.size)
            assertTrue(saved.single { it.genreKey == "zenkoku" }.favorite)
            assertFalse(saved.single { it.genreKey == "zenkoku" }.hidden)
            assertTrue(saved.single { it.genreKey == "talk" }.hidden)
            assertNotEquals(
                saved.single { it.genreKey == "zenkoku" }.host,
                saved.single { it.genreKey == "talk" }.host,
            )

            db.close()
            db = open()
            repo = RoomPreferenceRepository(db)
            saved = repo.preferences.first()
            assertEquals(2, saved.size)

            repo.observe(listOf(chatRoom.copy(message = "更新後")))
            var favorite = repo.preferences.first().single { it.genreKey == "zenkoku" }
            assertEquals("更新後", favorite.snapshotMessage)
            assertFalse(favorite.stale)
            assertTrue(favorite.appliesTo(chatRoom.copy(message = "さらに変更")))

            val reused = chatRoom.copy(name = "別の人")
            repo.observe(listOf(reused))
            favorite = repo.preferences.first().single { it.genreKey == "zenkoku" }
            assertTrue(favorite.stale)
            assertFalse(favorite.appliesTo(reused))

            // Once an ID collision is proven, a later matching profile must not silently clear it.
            repo.observe(listOf(chatRoom))
            favorite = repo.preferences.first().single { it.genreKey == "zenkoku" }
            assertTrue(favorite.stale)
            assertFalse(favorite.appliesTo(chatRoom))

            repo.clearFavorite(favorite.host, favorite.roomId)
            val hidden = repo.preferences.first().single()
            repo.clearHidden(hidden.host, hidden.roomId)
            assertTrue(repo.preferences.first().isEmpty())
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun undoingAnUnfavoriteRestoresTheSameRecordAndKeepsALaterHide() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "room-preferences-${UUID.randomUUID()}.db"
        val db = Room.databaseBuilder(context, PresetDatabase::class.java, name).build()
        try {
            val repo = RoomPreferenceRepository(db)
            val chatRoom = room(7, "zenkoku", "ミナ")
            repo.setFavorite(chatRoom, true)
            repo.observe(listOf(chatRoom.copy(name = "別の人")))
            val before = repo.preferences.first().single()
            assertTrue(before.stale)

            repo.clearFavorite(before.host, before.roomId)
            assertTrue(repo.preferences.first().isEmpty())
            // ID 再利用の印やプロフィールも含め、解除前の記録をそのまま戻す。
            repo.restoreFavorite(before)
            assertEquals(before, repo.preferences.first().single())

            repo.clearFavorite(before.host, before.roomId)
            val other = room(8, "zenkoku", "ユウ")
            repo.setFavorite(other, true)
            val second = repo.preferences.first().single { it.roomId == 8L }
            repo.clearFavorite(second.host, second.roomId)
            repo.setHidden(other, true)
            repo.restoreFavorite(second)
            val restored = repo.preferences.first().single { it.roomId == 8L }
            assertTrue(restored.favorite)
            assertTrue(restored.hidden)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun migratesExportedV2WithoutChangingExistingData() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "room-migration-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        val schema = javaClass.getResourceAsStream("/io.github.springthief1123.lovelyspace.data.PresetDatabase/2.json")!!
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
            old.execSQL(
                "INSERT INTO search_presets VALUES (" +
                    "'search', '保持する検索', 'talk', '', '', '', 'ALL', NULL, NULL, NULL, 1, NULL, NULL, NULL, 'SITE')",
            )
            old.version = 2
        }

        val db = Room.databaseBuilder(context, PresetDatabase::class.java, name)
            .addMigrations(PresetDatabase.MIGRATION_2_3, PresetDatabase.MIGRATION_3_4)
            .build()
        try {
            assertEquals(ProfilePreset("profile", "保持する設定", "合成の名前", 2, null, 13, true), db.presets().defaultProfile())
            assertEquals(MessagePreset("message", "保持する募集文", "合成の本文", true), db.presets().defaultMessage())
            assertTrue(db.presets().imported())
            assertEquals(
                SearchPreset("search", "保持する検索", "talk", RoomSearchCriteria()),
                SearchPresetRepository(db).presets.first().single(),
            )
            val repo = RoomPreferenceRepository(db)
            assertTrue(repo.preferences.first().isEmpty())
            repo.setFavorite(room(7, "talk", "移行後"), true)
            assertEquals(1, repo.preferences.first().size)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
