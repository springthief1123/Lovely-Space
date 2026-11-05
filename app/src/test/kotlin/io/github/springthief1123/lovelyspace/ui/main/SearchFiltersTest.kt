package io.github.springthief1123.lovelyspace.ui.main

import io.github.springthief1123.lovelyspace.core.Gender
import io.github.springthief1123.lovelyspace.core.KeywordMode
import io.github.springthief1123.lovelyspace.core.Prefectures
import io.github.springthief1123.lovelyspace.core.RoomSearchCriteria
import io.github.springthief1123.lovelyspace.core.RoomSort
import io.github.springthief1123.lovelyspace.data.SearchPreset
import io.github.springthief1123.lovelyspace.settings.recentAreasWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchFiltersTest {
    @Test fun ageBandsFollowMinAndMax() {
        assertEquals(emptyList<AgeBand>(), ageBandSelection(null, null))
        assertEquals(listOf(AgeBand.TWENTIES), ageBandSelection(20, 29))
        assertEquals(listOf(AgeBand.TEENS), ageBandSelection(null, 19))
        assertEquals(listOf(AgeBand.THIRTIES, AgeBand.FORTIES, AgeBand.FIFTIES_UP), ageBandSelection(30, null))
        // 年代の境目にそろっていない年齢はチップで表せないので、数字の欄で見せる。
        assertNull(ageBandSelection(25, 33))
        assertNull(ageBandSelection(18, 29))
    }

    @Test fun tappingAgeBandsTogglesAndKeepsOneRange() {
        assertEquals(20 to 29, tapAgeBand(null, null, AgeBand.TWENTIES))
        // 選んでいない年代は足す。離れていれば間も埋める。
        assertEquals(20 to 39, tapAgeBand(20, 29, AgeBand.THIRTIES))
        assertEquals(null to 29, tapAgeBand(20, 29, AgeBand.TEENS))
        assertEquals(20 to 49, tapAgeBand(20, 29, AgeBand.FORTIES))
        // 選んでいる年代は外す。端なら範囲が縮み、1 つだけなら指定なしに戻る。
        assertEquals(30 to 39, tapAgeBand(20, 39, AgeBand.TWENTIES))
        assertEquals(20 to 29, tapAgeBand(20, 39, AgeBand.THIRTIES))
        assertEquals(null to null, tapAgeBand(20, 29, AgeBand.TWENTIES))
        // 真ん中を外すと、多いほうの側を残す（同じなら若いほう）。
        assertEquals(20 to 29, tapAgeBand(20, 49, AgeBand.THIRTIES))
        assertEquals(40 to null, tapAgeBand(20, null, AgeBand.THIRTIES))
        // 数字で決めた年齢から押すと、その年代に置き換える。
        assertEquals(40 to 49, tapAgeBand(25, 33, AgeBand.FORTIES))
    }

    @Test fun ageSummaryReadsNaturally() {
        assertEquals("指定なし", ageSummary(null, null))
        assertEquals("20〜29歳", ageSummary(20, 29))
        assertEquals("30歳以上", ageSummary(30, null))
        assertEquals("19歳以下", ageSummary(null, 19))
    }

    @Test fun activeFiltersListEveryConditionOnceAndCanBeCleared() {
        val c = RoomSearchCriteria(text = "映画 ", keywordMode = KeywordMode.ANY, gender = Gender.FEMALE, waitingOnly = false,
            publicOnly = true, minAge = 20, maxAge = 29, includeUnknownAge = false, area = "東京", excluded = "業者", sort = RoomSort.AGE)
        val filters = activeFilters(c)
        assertEquals(listOf("「映画」（どれか）", "女性", "満室", "公開", "20〜29歳", "年齢が秘密の部屋を除く", "東京", "除外「業者」"),
            filters.map { it.label })
        // 並び順は数えない。バッジの数と同じ数のチップを出す。
        assertEquals(activeFilterCount(c), filters.size)
        var cleared = c
        filters.forEach { cleared = cleared.without(it.kind) }
        assertEquals(RoomSearchCriteria(keywordMode = KeywordMode.ANY, sort = RoomSort.AGE), cleared)
        assertEquals(SearchSheetTab.AGE_AREA, filters.first { it.kind == ActiveFilterKind.AREA }.tab)
        assertEquals(SearchSheetTab.KEYWORD, filters.first { it.kind == ActiveFilterKind.EXCLUDED }.tab)
        assertTrue(activeFilters(RoomSearchCriteria(sort = RoomSort.NAME)).isEmpty())
    }

    @Test fun presetMatchesIgnoresSortOnly() {
        val preset = SearchPreset(label = "合成", genreKey = "zenkoku", criteria = RoomSearchCriteria(text = "雑談", sort = RoomSort.NAME))
        assertTrue(presetMatches(preset, "zenkoku", RoomSearchCriteria(text = "雑談", sort = RoomSort.AGE)))
        assertFalse(presetMatches(preset, "zenkoku", RoomSearchCriteria(text = "雑談", gender = Gender.MALE)))
        assertFalse(presetMatches(preset, "kanto", RoomSearchCriteria(text = "雑談")))
    }

    @Test fun sheetTabsAreMarkedWhenTheyHoldConditions() {
        assertFalse(sheetTabHasFilters(SearchSheetTab.AGE_AREA, RoomSearchCriteria(text = "雑談")))
        assertTrue(sheetTabHasFilters(SearchSheetTab.AGE_AREA, RoomSearchCriteria(area = "大阪")))
        assertTrue(sheetTabHasFilters(SearchSheetTab.KEYWORD, RoomSearchCriteria(excluded = "業者")))
        // 「どれかを含む」は検索欄の下で選ぶので、除外する語のタブには印を付けない。
        assertFalse(sheetTabHasFilters(SearchSheetTab.KEYWORD, RoomSearchCriteria(keywordMode = KeywordMode.ANY)))
    }

    @Test fun regionsCoverEveryPrefectureOnce() {
        assertEquals(Prefectures.names, PrefectureRegions.flatMap { it.second })
    }

    @Test fun areaShortcutsPutSelectionFirstAndRecentAfter() {
        assertEquals(listOf("大阪", "東京", "福岡"), areaShortcuts("大阪", listOf("東京", "大阪", "福岡")))
        assertEquals(listOf("東京", "神奈川", "埼玉", "千葉"), areaShortcuts(null, listOf("東京", "神奈川", "埼玉", "千葉", "茨城")))
        assertEquals(listOf("北海道", "東京"), recentAreasWith(listOf("東京", "北海道"), "北海道"))
        assertEquals(5, recentAreasWith(listOf("東京", "神奈川", "埼玉", "千葉", "茨城"), "栃木").size)
    }

    @Test fun sortChipsToggleDirectionOnSecondTap() {
        assertEquals(RoomSort.SITE_OLDEST, tapSort(RoomSort.SITE, SortChoice.NEWEST))
        assertEquals(RoomSort.SITE, tapSort(RoomSort.SITE_OLDEST, SortChoice.NEWEST))
        // 別のチップを押すと、そのチップの最初の向き（年齢は若い順）。
        assertEquals(RoomSort.AGE, tapSort(RoomSort.SITE, SortChoice.AGE))
        assertEquals(RoomSort.AGE_DESC, tapSort(RoomSort.AGE, SortChoice.AGE))
        assertEquals(RoomSort.SITE, tapSort(RoomSort.AGE_DESC, SortChoice.NEWEST))
        // おすすめには向きがない。
        assertEquals(RoomSort.RECOMMENDED, tapSort(RoomSort.RECOMMENDED, SortChoice.RECOMMENDED))
        // 以前に保存した経過順は新着順に、名前順はどのチップにも当たらない。
        assertEquals(SortChoice.NEWEST, sortChoiceOf(RoomSort.ELAPSED))
        assertNull(sortChoiceOf(RoomSort.NAME))
        assertEquals(RoomSort.SITE, tapSort(RoomSort.NAME, SortChoice.NEWEST))
    }
}
