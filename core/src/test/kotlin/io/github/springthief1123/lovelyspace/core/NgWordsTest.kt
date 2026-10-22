package io.github.springthief1123.lovelyspace.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

class NgWordsTest {
    private val room = Room(1, "zenkoku", RoomStatus.WAITING, RoomAction.ENTER, 2.minutes,
        "合成 Alice", Gender.FEMALE, 25, "東京", "猫と雑談")
    private val money = NgWordSet.builtIn("money")!!

    private fun filter(vararg sets: NgWordSet) = NgWordFilter.of(sets.toList())

    @Test fun absorbsKanaWidthAndCaseVariants() {
        val f = filter(money)
        assertEquals(NgMatch("お金目的", "PayPay"), f.matchOf(room.copy(message = "ｐａｙｐａｙで")))
        assertEquals(NgMatch("お金目的", "メルペイ"), f.matchOf(room.copy(message = "めるぺい可")))
        assertEquals(NgMatch("お金目的", "先払い"), f.matchOf(room.copy(message = "サキバライ→先払いのみ")))
        assertEquals(NgMatch("お金目的", "ペイ"), f.matchOf(room.copy(name = "合成ﾍﾟｲ", message = "なし")))
        assertEquals(NgMatch("お金目的", "販売"), f.matchOf(room.copy(name = "合成販売中")))
        assertNull(f.matchOf(room))
    }

    @Test fun newHalfSetMatchesItsWordsInNameOrMessage() {
        val f = filter(NgWordSet.builtIn("newhalf")!!)
        assertEquals(NgMatch(NgWordSet.NEWHALF_LABEL, "ニューハーフ"), f.matchOf(room.copy(message = "にゅーはーふです")))
        assertEquals(NgMatch(NgWordSet.NEWHALF_LABEL, "NH"), f.matchOf(room.copy(name = "合成ｎｈ")))
        assertEquals(NgMatch(NgWordSet.NEWHALF_LABEL, "女装"), f.matchOf(room.copy(message = "女装してます")))
        assertEquals(NgMatch(NgWordSet.NEWHALF_LABEL, "男の娘"), f.matchOf(room.copy(name = "男の娘")))
        assertEquals(NgMatch(NgWordSet.NEWHALF_LABEL, "レズビアン"), f.matchOf(room.copy(message = "レズビアンです")))
        assertEquals(NgMatch(NgWordSet.NEWHALF_LABEL, "レズチャ"), f.matchOf(room.copy(message = "れずちゃしよ")))
        assertEquals(NgMatch(NgWordSet.NEWHALF_LABEL, "レズ"), f.matchOf(room.copy(name = "合成ﾚｽﾞ")))
        assertEquals(NgMatch(NgWordSet.NEWHALF_LABEL, "ビアン"), f.matchOf(room.copy(message = "ビアンの方")))
        assertEquals(NgMatch(NgWordSet.NEWHALF_LABEL, "百合"), f.matchOf(room.copy(message = "百合好き")))
        assertNull(f.matchOf(room))
    }

    @Test fun findsAmountsButNotOtherUsesOfYenOrNumbers() {
        val f = filter(money.copy(words = emptyList()))
        listOf("3000円から", "３，０００円", "1.5万円", "5千円", "千円", "五千円", "一万円", "¥500", "￥500", "\\1000", "2000 円")
            .forEach { assertEquals(it, NgMatch("お金目的", NgAmount.LABEL), f.matchOf(room.copy(message = it))) }
        listOf("円満な関係", "関東一円で", "20歳です", "3時まで", "円山公園")
            .forEach { assertNull(it, f.matchOf(room.copy(message = it))) }
    }

    @Test fun respectsTargetAndEnabled() {
        val words = NgWordSet("x", "合成", listOf("合成語"))
        val named = room.copy(name = "合成語さん", message = "雑談")
        val messaged = room.copy(name = "合成", message = "合成語あり")
        assertTrue(filter(words).matches(named) && filter(words).matches(messaged))
        assertTrue(filter(words.copy(target = NgTarget.NAME)).matches(named))
        assertFalse(filter(words.copy(target = NgTarget.NAME)).matches(messaged))
        assertFalse(filter(words.copy(target = NgTarget.MESSAGE)).matches(named))
        assertTrue(filter(words.copy(enabled = false)).isEmpty)
        // 満室の部屋は名前が無い。
        assertFalse(filter(words.copy(target = NgTarget.NAME)).matches(named.copy(name = null)))
    }

    @Test fun doesNotJoinNameAndMessage() {
        val f = filter(NgWordSet("x", "合成", listOf("スペース")))
        assertFalse(f.matches(room.copy(name = "合成ス", message = "ペース")))
    }

    @Test fun reportsFirstMatchingSetAndPartitionsInOrder() {
        val sample = room.copy(id = 2, message = "サンプルあり")
        val both = room.copy(id = 3, message = "写真販売 PayPay")
        val (kept, excluded) = partitionNg(listOf(room, sample, both), filter(money))
        assertEquals(listOf(room), kept)
        assertEquals(listOf(2L, 3L), excluded.map { it.room.id })
        assertEquals(NgMatch("お金目的", "サンプル"), excluded[0].match)
        // 長い語を先に並べているので、「販売」より「写真販売」が当たった語として出る。
        assertEquals(NgMatch("お金目的", "写真販売"), excluded[1].match)
        assertEquals(listOf(room, sample) to emptyList<NgExcluded>(), partitionNg(listOf(room, sample), NgWordFilter.NONE))
    }

    @Test fun ignoresBlankWordsAndSplitsInput() {
        assertTrue(filter(NgWordSet("x", "空", listOf(" ", ""))).isEmpty)
        assertEquals(listOf("販売", "PayPay", "先払い"), NgWordSet.splitWords(" 販売　PayPay\n先払い 販売 "))
    }
}
