package io.github.springthief1123.lovelyspace.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.springthief1123.lovelyspace.core.chat.EntryProfile
import io.github.springthief1123.lovelyspace.settings.RoomDetails
import io.github.springthief1123.lovelyspace.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PresetRepositoryTest {
    @Test fun importsOnceKeepsOneDefaultAndPersistsEditsAcrossReopen() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val settings = SettingsRepository(context)
        settings.setLastEntryProfile(EntryProfile("テスト用の名前", 2, null))
        settings.setLastRoomDetails(RoomDetails(13, "合成テストの募集文"))
        val name = "presets-${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, PresetDatabase::class.java, name).build()
        var db = open()
        try {
            var repo = PresetRepository(db, settings)
            repo.initialize()
            repo.initialize()
            assertEquals(1, repo.profiles.first().size)
            assertEquals(1, repo.messages.first().size)
            assertNull(repo.defaultProfile()!!.years)
            assertEquals(13, repo.defaultProfile()!!.prefecture)
            val second = ProfilePreset("second", "別の設定", "別名", 1, 30, null, true)
            repo.save(second)
            repo.save(second.copy(label = "編集後"))
            assertEquals(2, repo.profiles.first().size)
            assertEquals(1, repo.profiles.first().count { it.isDefault })
            assertEquals("second", repo.defaultProfile()!!.id)
            repo.setDefaultProfile("imported-profile")
            assertEquals("imported-profile", repo.defaultProfile()!!.id)
            repo.save(MessagePreset("second-message", "募集文2", "テスト\nだけ", true))
            assertEquals("テスト だけ", repo.defaultMessage()!!.message)
            assertEquals(1, repo.messages.first().count { it.isDefault })
            repo.setDefaultMessage("imported-message")
            assertEquals("imported-message", repo.defaultMessage()!!.id)
            try {
                repo.save(second.copy(years = 17))
                fail("18歳未満を保存できない")
            } catch (_: IllegalArgumentException) { }
            try {
                repo.save(MessagePreset(label = "長すぎる", message = "あ".repeat(251)))
                fail("全角251文字を保存できない")
            } catch (_: IllegalArgumentException) { }
            db.close()
            db = open()
            repo = PresetRepository(db, settings)
            assertEquals("編集後", repo.profiles.first().first { it.id == "second" }.label)
            assertEquals("imported-profile", repo.defaultProfile()!!.id)
            for (p in repo.profiles.first()) repo.deleteProfile(p.id)
            for (m in repo.messages.first()) repo.deleteMessage(m.id)
            repo.initialize()
            assertTrue(repo.profiles.first().isEmpty())
            assertTrue(repo.messages.first().isEmpty())
            assertNull(repo.defaultProfile())
            assertNull(repo.defaultMessage())
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
