package io.github.springthief1123.lovelyspace.ui.entry

import io.github.springthief1123.lovelyspace.ui.create.CreateRoomUiState
import org.junit.Assert.assertEquals
import org.junit.Test

/** 押せないボタンの下に出す、足りない入力。合成の値だけを使う。 */
class MissingInputsTest {
    @Test fun entryListsNameAndInvalidAge() {
        assertEquals(listOf("名前", "年齢（18〜99、空欄なら秘密）"), EntryUiState(name = " ", years = "17").missingInputs)
    }

    @Test fun entryWithNameAndSecretAgeNeedsNothing() {
        assertEquals(emptyList<String>(), EntryUiState(name = "合成", years = "").missingInputs)
    }

    @Test fun createListsTooLongMessage() {
        val state = CreateRoomUiState(isLoaded = true, name = "合成", message = "あ".repeat(251))
        assertEquals(listOf("待機メッセージ（500文字以内、全角は2文字）"), state.missingInputs)
    }
}
