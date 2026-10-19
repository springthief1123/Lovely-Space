package io.github.springthief1123.lovelyspace.ui.rooms

import io.github.springthief1123.lovelyspace.core.Gender
import io.github.springthief1123.lovelyspace.core.Genres
import io.github.springthief1123.lovelyspace.core.Room
import io.github.springthief1123.lovelyspace.core.RoomAction
import io.github.springthief1123.lovelyspace.core.RoomStatus
import io.github.springthief1123.lovelyspace.data.RoomPreference
import io.github.springthief1123.lovelyspace.data.hides
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomPreferenceUiStateTest {
    @Test fun `同じ部屋の保存と非表示を処理中は両方とも操作できない`() {
        val host = Genres.default.host
        val state = RoomPreferenceUiState(workingKeys = setOf(RoomPreferenceUiState.key(host, 1)))
        assertFalse(state.canEdit(host, 1))
        assertTrue(state.canEdit(host, 2))
        assertTrue(state.canEdit("another.example", 1))
        assertTrue(state.copy(workingKeys = emptySet()).canEdit(host, 1))
    }

    // 合成データ。本家の実データは使わない。
    private val room = Room(1, Genres.default.key, RoomStatus.WAITING, RoomAction.ENTER, null, "合成の名前", Gender.FEMALE, 25, null, "合成の待機メッセージです")
    private fun preference(of: Room, hidden: Boolean = true, favorite: Boolean = false) = RoomPreference(
        Genres.default.host, of.id, of.genreKey, favorite, hidden, of.status.name, of.action.name, null, of.name, of.gender.name, of.age,
        of.area, of.message, of.name, of.gender.name, of.age,
    )

    @Test fun `非表示にした部屋を立て直しても、名前と待機メッセージが同じなら隠す`() {
        val state = RoomPreferenceUiState(preferences = listOf(preference(room)), loading = false)
        val rebuilt = room.copy(id = 99)
        assertTrue(state.isHidden(rebuilt))
        assertTrue(listOf(preference(room)).hides(rebuilt))
        // 待機メッセージが違う・名前が見えない（満室）・待機メッセージが短い部屋は隠さない。
        assertFalse(state.isHidden(rebuilt.copy(message = "別の合成の待機メッセージ")))
        assertFalse(state.isHidden(rebuilt.copy(name = null, status = RoomStatus.FULL)))
        val short = room.copy(message = "よろしく")
        assertFalse(RoomPreferenceUiState(preferences = listOf(preference(short)), loading = false).isHidden(short.copy(id = 99)))
        // 立て直した部屋を保存していれば、その記録に従う。
        val saved = RoomPreferenceUiState(preferences = listOf(preference(room), preference(rebuilt, hidden = false, favorite = true)), loading = false)
        assertFalse(saved.isHidden(rebuilt))
    }
}
