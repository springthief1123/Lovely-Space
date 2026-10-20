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
import kotlin.time.Duration.Companion.minutes

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class NgWordRepositoryTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val room = Room(1, "zenkoku", RoomStatus.WAITING, RoomAction.ENTER, 2.minutes, "合成", Gender.FEMALE, 25, null, "合成の募集")

    private fun open(name: String) = Room.databaseBuilder(context, PresetDatabase::class.java, name)
        .addMigrations(PresetDatabase.MIGRATION_1_2, PresetDatabase.MIGRATION_2_3, PresetDatabase.MIGRATION_3_4, PresetDatabase.MIGRATION_4_5).build()

    @Test fun seedsBuiltInSetsOnceAndKeepsEditsAcrossReopen() = runTest {
        val name = "ng-${UUID.randomUUID()}.db"
        var db = open(name)
        try {
            var repo = NgWordRepository(db, backgroundScope)
            assertEquals(NgWordSet.BUILT_IN, repo.sets.first())
            val money = NgWordSet.builtIn("money")!!
            repo.save(money.copy(words = listOf(" 合成語 ", "販売", "合成語", "")))
            repo.setEnabled("builtin-media", false)
            repo.save(NgWordSet("mine", "  自分のセット ", listOf("合成ワード"), target = NgTarget.NAME))
            // 最初からのセットは削除できない（オフにして使わない）。自分で足したセットは削除できる。
            assertTrue(runCatching { repo.delete("builtin-media") }.exceptionOrNull() is IllegalArgumentException)
            repo.save(NgWordSet("extra", "消すセット", listOf("合成")))
            repo.delete("extra")
            db.close()

            db = open(name)
            repo = NgWordRepository(db, backgroundScope)
            val sets = repo.sets.first()
            // 並びは足した順。オフにしたセットはオフのまま残る。
            assertEquals(listOf("builtin-money", "builtin-media", "builtin-newhalf", "mine"), sets.map { it.id })
            assertEquals(listOf("合成語", "販売"), sets[0].words)
            assertFalse(sets[1].enabled)
            assertEquals(NgWordSet("mine", "自分のセット", listOf("合成ワード"), target = NgTarget.NAME), sets[3])
            val filter = NgWordFilter.of(sets)
            assertTrue(filter.matches(room.copy(message = "合成語あり")))
            assertTrue(filter.matches(room.copy(name = "合成ワード")))
            assertFalse(filter.matches(room))
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun addsBuiltInSetsIntroducedAfterTheFirstSeedButNotDeletedOnes() = runTest {
        val name = "ng-${UUID.randomUUID()}.db"
        val db = open(name)
        try {
            // 最初の版で 2 セットを入れ、利用者が「写真・動画目的」を消し、自分のセットを足した状態。
            val money = NgWordSet.builtIn("money")!!
            db.ngWords().put(NgWordSetEntity(money.id, money.label, "販売", true, money.target.name, true, "money", 0))
            db.ngWords().put(NgWordSetEntity("mine", "自分のセット", "合成語", false, NgTarget.NAME_AND_MESSAGE.name, true, null, 2))
            db.presets().put(LocalState("ng_word_seed", "done"))

            val repo = NgWordRepository(db, backgroundScope)
            val sets = repo.sets.first()
            // 後から増えたセットだけを末尾に足す。消したセットは戻さず、手を入れた語もそのまま。
            assertEquals(listOf("builtin-money", "mine", "builtin-newhalf"), sets.map { it.id })
            assertEquals(listOf("販売"), sets[0].words)
            assertEquals(NgWordSet.builtIn("newhalf"), sets[2])
            // もう一度読んでも重ねて足さない。
            assertEquals(3, NgWordRepository(db, backgroundScope).sets.first().size)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun rejectsEmptySets() = runTest {
        val name = "ng-${UUID.randomUUID()}.db"
        val db = open(name)
        try {
            val repo = NgWordRepository(db, backgroundScope)
            for (invalid in listOf(NgWordSet("x", " ", listOf("語")), NgWordSet("x", "空", listOf(" ")))) {
                try { repo.save(invalid); fail("保存できてしまった: $invalid") } catch (e: IllegalArgumentException) { assertNotNull(e.message) }
            }
            // 金額の表記だけのセットは作れる。
            repo.save(NgWordSet("x", "金額だけ", emptyList(), amounts = true))
            assertTrue(repo.sets.first().any { it.id == "x" })
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun searchSideReportsLoadFailureAndRecoversOnRetry() = runTest {
        val name = "ng-${UUID.randomUUID()}.db"
        val db = open(name)
        try {
            val sql = db.openHelper.writableDatabase
            // 表が読めない状態を作る。失敗は「セットなし」と区別して知らせ、判定は何も外さない。
            sql.execSQL("ALTER TABLE ng_word_sets RENAME TO ng_word_sets_away")
            val repo = NgWordRepository(db, backgroundScope)
            assertTrue(repo.state.first { it !is NgLoadState.Loading } is NgLoadState.Failed)
            assertSame(NgWordFilter.NONE, repo.filter.value)

            sql.execSQL("ALTER TABLE ng_word_sets_away RENAME TO ng_word_sets")
            repo.retry()
            val ready = repo.state.first { it is NgLoadState.Ready } as NgLoadState.Ready
            assertEquals(NgWordSet.BUILT_IN.size, ready.filter.setCount)
            assertTrue(repo.filter.first { it.setCount == NgWordSet.BUILT_IN.size }.matches(room.copy(message = "PayPayで")))
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun v4DataSurvivesAddingNgWordSets() = runTest {
        val name = "ng-migration-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        val schema = javaClass.getResourceAsStream("/io.github.springthief1123.lovelyspace.data.PresetDatabase/4.json")!!
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            old.execSQL("INSERT INTO search_presets VALUES ('saved', '保持する条件', 'zenkoku', '合成', '本文', '除外語', 'ALL', NULL, 20, 40, 1, NULL, 1, NULL, 'SITE', '共通')")
            old.execSQL("INSERT INTO message_presets VALUES ('m', '保持する募集', '合成の募集文', 1)")
            old.execSQL("INSERT INTO local_state VALUES ('preset_import', 'done')")
            old.version = 4
        }
        val db = Room.databaseBuilder(context, PresetDatabase::class.java, name).addMigrations(PresetDatabase.MIGRATION_4_5).build()
        try {
            val preset = db.searchPresets().presets().first().single()
            assertEquals("除外語", preset.criteria.excluded)
            assertEquals("共通", preset.criteria.text)
            assertEquals("合成の募集文", db.presets().messages().first().single().message)
            assertEquals(NgWordSet.BUILT_IN, NgWordRepository(db, backgroundScope).sets.first())
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
