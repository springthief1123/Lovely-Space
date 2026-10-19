package io.github.springthief1123.lovelyspace.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

class RoomSearchTest {
    private val room = Room(1, "zenkoku", RoomStatus.WAITING, RoomAction.ENTER, 2.minutes,
        "合成 Alice", Gender.FEMALE, 25, "東京", "猫と雑談")
    @Test fun appliesAndOrAndExclusionsAcrossFields() {
        assertTrue(RoomSearchCriteria(name = "alice 合成", message = "猫　雑談").matches(room))
        assertFalse(RoomSearchCriteria(message = "猫 犬").matches(room))
        assertTrue(RoomSearchCriteria(message = "猫 犬", keywordMode = KeywordMode.ANY).matches(room))
        assertFalse(RoomSearchCriteria(excluded = "犬 ALICE").matches(room))
        assertFalse(RoomSearchCriteria(name = "隠された名前").matches(room.copy(name = null)))
    }
    @Test fun distinguishesUnknownAgesAndInclusiveRange() {
        val criteria = RoomSearchCriteria(minAge = 25, maxAge = 25)
        assertTrue(criteria.matches(room))
        assertTrue(criteria.matches(room.copy(age = null)))
        assertFalse(criteria.copy(includeUnknownAge = false).matches(room.copy(age = null)))
        assertFalse(criteria.copy(minAge = 30, maxAge = 20).isValid)
        assertFalse(criteria.copy(minAge = 17).isValid)
    }
    @Test fun combinesGenderAreaAndPublicFullState() {
        assertTrue(RoomSearchCriteria(gender = Gender.FEMALE, area = "東京", waitingOnly = true, publicOnly = false).matches(room))
        assertFalse(RoomSearchCriteria(gender = Gender.MALE).matches(room))
        assertFalse(RoomSearchCriteria(area = "大阪").matches(room))
        val peek = room.copy(status = RoomStatus.FULL, action = RoomAction.PEEK)
        assertTrue(RoomSearchCriteria(waitingOnly = false, publicOnly = true).matches(peek))
        assertFalse(RoomSearchCriteria(waitingOnly = true).matches(peek))
    }
    @Test fun deduplicatesByHostAndSortsUnknownValuesLastStably() {
        val older = room.copy(id = 2, age = 30, elapsed = 1.minutes)
        val unknown = room.copy(id = 3, age = null, elapsed = null, name = null)
        val otherHost = room.copy(genreKey = "talk")
        val rooms = listOf(unknown, older, room, room.copy(genreKey = "kanto"), otherHost)
        assertEquals(4, searchRooms(rooms, RoomSearchCriteria()).size)
        assertEquals(listOf(25, 25, 30, null), searchRooms(rooms, RoomSearchCriteria(sort = RoomSort.AGE)).map { it.age })
        assertEquals(listOf(2L, 1L, 1L, 3L), searchRooms(rooms, RoomSearchCriteria(sort = RoomSort.ELAPSED)).map { it.id })
        assertNull(searchRooms(rooms, RoomSearchCriteria(sort = RoomSort.NAME)).last().name)
    }

    @Test
    fun absorbsWidthCaseKanaAndDashVariants() {
        val r = room.copy(name = "ｹﾞｰﾑ好き", message = "まったり〜ゲームしよ ＡＢＣ")
        assertTrue(RoomSearchCriteria(text = "げーむ").matches(r))
        assertTrue(RoomSearchCriteria(name = "ゲーム").matches(r))
        assertTrue(RoomSearchCriteria(message = "まったりー abc").matches(r))
        assertTrue(RoomSearchCriteria(message = "まったり～").matches(r))
        assertFalse(RoomSearchCriteria(excluded = "げーむ").matches(r))
        assertEquals("ちゃh", KeywordFolding.fold("ﾁｬＨ"))
    }

    @Test
    fun synonymsMatchEveryWordOfTheGroupOnlyForWholeTerms() {
        val r = room.copy(message = "今夜はチャHしたい")
        assertTrue(RoomSearchCriteria(message = "ちゃえっち").matches(r))
        assertTrue(RoomSearchCriteria(text = "チャットエッチ").matches(r))
        assertFalse(RoomSearchCriteria(message = "ちゃえっち").matches(r, SynonymDictionary.NONE))
        // 言い換えは語がまとまりの語と同じときだけ。「えっち」は「チャH」に広げない。
        assertFalse(RoomSearchCriteria(message = "えっち").matches(r))
        assertFalse(RoomSearchCriteria(excluded = "ちゃえっち").matches(r))
        val custom = SynonymDictionary(listOf(listOf("猫", "ねこ", "ニャンコ")))
        assertTrue(RoomSearchCriteria(message = "にゃんこ").matches(room, custom))
        assertEquals(setOf("猫", "ねこ", "にゃんこ"), custom.variants("ネコ"))
    }

    @Test
    fun minusWordsExcludeInEachField() {
        assertFalse(RoomSearchCriteria(text = "猫 -雑談").matches(room))
        assertTrue(RoomSearchCriteria(text = "猫 -犬").matches(room))
        assertFalse(RoomSearchCriteria(text = "－ALICE").matches(room))
        // 除外は「どれかを含む」でも必ず効く。
        assertFalse(RoomSearchCriteria(text = "猫 犬 -雑談", keywordMode = KeywordMode.ANY).matches(room))
        // 名前欄の -語 は名前だけ、待機メッセージ欄は待機メッセージだけを見る。
        assertTrue(RoomSearchCriteria(name = "-雑談").matches(room))
        assertFalse(RoomSearchCriteria(message = "-雑談").matches(room))
        assertFalse(RoomSearchCriteria(name = "-alice").matches(room))
        // 長音で始まる語や「-」だけは除外にしない。
        assertFalse(RoomSearchCriteria(text = "ーゲーム").matches(room))
        assertTrue(RoomSearchCriteria(text = "猫 -").matches(room))
    }

    @Test
    fun areaMatchesTagPrefixAndWrittenLocations() {
        assertTrue(RoomSearchCriteria(area = "東京").matches(room.copy(area = "東京都")))
        assertFalse(RoomSearchCriteria(area = "京都").matches(room.copy(area = "東京都")))
        // タグが無ければ、名前・募集文に地域として書いてあるときだけ含める。
        val untagged = room.copy(area = null)
        assertTrue(RoomSearchCriteria(area = "神奈川").matches(untagged.copy(message = "神奈川住みです")))
        assertTrue(RoomSearchCriteria(area = "大阪").matches(untagged.copy(message = "大阪府の人")))
        assertFalse(RoomSearchCriteria(area = "大分").matches(untagged.copy(message = "大分慣れました")))
        assertFalse(RoomSearchCriteria(area = "京都").matches(untagged.copy(message = "東京都在住")))
        assertTrue(RoomSearchCriteria(area = "東京").matches(untagged.copy(message = "東京都在住")))
        assertFalse(RoomSearchCriteria(area = "東京").matches(untagged))
        // タグのある部屋はタグで決める。
        assertFalse(RoomSearchCriteria(area = "大阪").matches(room.copy(message = "大阪住みの友達")))
    }
}
