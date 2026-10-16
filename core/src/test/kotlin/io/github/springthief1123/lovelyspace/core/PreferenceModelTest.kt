package io.github.springthief1123.lovelyspace.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

class PreferenceModelTest {
    private var nextId = 1L
    private fun room(message: String, name: String = "合成") =
        Room(nextId++, "zenkoku", RoomStatus.WAITING, RoomAction.ENTER, 1.minutes, name, Gender.FEMALE, 25, null, message)

    private val liked = listOf(room("のんびり雑談しましょう"), room("まったりのんびりお話"), room("寝る前にのんびり雑談"))
    private val disliked = listOf(room("即決できる人だけ"), room("短時間で即決希望"))

    @Test
    fun needsEnoughSavedRooms() {
        assertNull(PreferenceModel.train(liked.take(2), disliked, emptyList()))
        // 同じ部屋を重ねても数えない。
        assertNull(PreferenceModel.train(liked.take(2) + liked.first(), disliked, emptyList()))
        assertNotNull(PreferenceModel.train(liked, emptyList(), emptyList()))
        // 保存したまま非表示にした部屋は保存の側に数えない。
        assertNull(PreferenceModel.train(liked, listOf(liked.first()), emptyList()))
    }

    @Test
    fun hiddenTakesPrecedenceOverSaved() {
        val hiddenSaved = room("即決できる人だけ")
        val model = PreferenceModel.train(liked + hiddenSaved, listOf(hiddenSaved), emptyList())!!
        assertEquals(3, model.likedCount)
        assertEquals(1, model.dislikedCount)
        assertTrue(model.score(room("即決の人")) < model.score(room("映画")))
    }

    @Test
    fun ranksRoomsLikeSavedOnesFirstAndHiddenLikeOnesLast() {
        val model = PreferenceModel.train(liked, disliked, emptyList())!!
        val calm = room("今夜はのんびり雑談したい")
        val hurried = room("即決の人だけ来て")
        val plain = room("映画の話をしよう")
        assertEquals(listOf(calm, plain, hurried), model.rank(listOf(hurried, plain, calm)))
        assertEquals(3, model.likedCount)
        assertEquals(2, model.dislikedCount)
    }

    @Test
    fun learnsFromTheListAsWeakContrastWithoutHiddenRooms() {
        val common = (1..10).map { room("よろしくお願いします") }
        val model = PreferenceModel.train(liked, emptyList(), common)!!
        val calm = room("のんびりよろしく")
        val usual = room("よろしくお願いします")
        assertEquals(listOf(calm, usual), model.rank(listOf(usual, calm)))
    }

    @Test
    fun absorbsNotationVariantsAndKeepsSiteOrderOnTies() {
        val model = PreferenceModel.train(liked, disliked, emptyList())!!
        // カタカナ・半角で書いても同じ語として数える。
        assertTrue(model.score(room("ﾉﾝﾋﾞﾘ")) > model.score(room("映画")))
        val a = room("")
        val b = room("")
        assertEquals(listOf(a, b), model.rank(listOf(a, b)))
    }
}
