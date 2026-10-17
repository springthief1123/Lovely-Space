package io.github.springthief1123.lovelyspace.ui.main

import io.github.springthief1123.lovelyspace.core.RoomSearchCriteria
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchKeywordTest {
    @Test fun keywordGoesToTheSelectedField() {
        val c = RoomSearchCriteria()
        assertEquals(RoomSearchCriteria(text = "映画"), c.withKeyword(KeywordScope.ALL, "映画"))
        assertEquals(RoomSearchCriteria(name = "映画"), c.withKeyword(KeywordScope.NAME, "映画"))
        assertEquals(RoomSearchCriteria(message = "映画"), c.withKeyword(KeywordScope.MESSAGE, "映画"))
    }

    @Test fun changingScopeMovesTheTypedWord() {
        val c = RoomSearchCriteria(text = "映画")
        assertEquals(RoomSearchCriteria(name = "映画"), c.moveKeyword(KeywordScope.ALL, KeywordScope.NAME))
        assertEquals(RoomSearchCriteria(message = "映画"), c.moveKeyword(KeywordScope.ALL, KeywordScope.NAME).moveKeyword(KeywordScope.NAME, KeywordScope.MESSAGE))
        // 検索欄が空なら、移した先に残っている語を消さない。
        val saved = RoomSearchCriteria(name = "合成")
        assertEquals(saved, saved.moveKeyword(KeywordScope.ALL, KeywordScope.NAME))
        // 移した先に語が残っていれば、どちらの条件も変えない（つなげると「どれかを含む」で緩むため）。
        val legacy = RoomSearchCriteria(text = "映画", name = "合成")
        assertEquals(legacy, legacy.moveKeyword(KeywordScope.ALL, KeywordScope.NAME))
        assertEquals(listOf(KeywordScope.ALL to "映画"), hiddenKeywords(legacy, KeywordScope.NAME))
    }

    @Test fun savedCriteriaPickTheFieldThatHoldsTheWord() {
        assertEquals(KeywordScope.ALL, keywordScopeOf(RoomSearchCriteria()))
        assertEquals(KeywordScope.ALL, keywordScopeOf(RoomSearchCriteria(text = "映画", name = "合成")))
        assertEquals(KeywordScope.NAME, keywordScopeOf(RoomSearchCriteria(name = "合成")))
        assertEquals(KeywordScope.MESSAGE, keywordScopeOf(RoomSearchCriteria(message = "雑談")))
        // 名前と待機メッセージの両方に語がある古い条件は共通検索欄を出し、残りは別に示す。
        val both = RoomSearchCriteria(name = "合成", message = "雑談")
        assertEquals(KeywordScope.ALL, keywordScopeOf(both))
        assertEquals(listOf(KeywordScope.NAME to "合成", KeywordScope.MESSAGE to "雑談"), hiddenKeywords(both, KeywordScope.ALL))
        assertEquals(emptyList<Pair<KeywordScope, String>>(), hiddenKeywords(RoomSearchCriteria(name = "合成"), KeywordScope.NAME))
    }

    @Test fun clearingHiddenWordsKeepsOnlyTheShownField() {
        val both = RoomSearchCriteria(text = "映画", name = "合成", message = "雑談", excluded = "業者")
        assertEquals(RoomSearchCriteria(name = "合成", excluded = "業者"), both.onlyKeyword(KeywordScope.NAME))
    }

    @Test fun ageInputsFollowTheSameRuleOnEveryScreen() {
        assertTrue(validAgeInputs("", ""))
        assertTrue(validAgeInputs("20", "30"))
        assertTrue(validAgeInputs("30", "30"))
        assertFalse(validAgeInputs("17", ""))
        assertFalse(validAgeInputs("", "100"))
        assertFalse(validAgeInputs("40", "30"))
    }
}
