package io.github.springthief1123.lovelyspace.ui.rooms

import io.github.springthief1123.lovelyspace.core.Genres
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
}
