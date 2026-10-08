package io.github.springthief1123.lovelyspace.ui.main

import io.github.springthief1123.lovelyspace.core.Gender
import io.github.springthief1123.lovelyspace.core.RoomSearchCriteria
import io.github.springthief1123.lovelyspace.core.RoomSort
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchHeaderTest {
    @Test fun quickChipConditionsAreNotCountedAsAdvancedFilters() {
        val quick = RoomSearchCriteria(gender = Gender.FEMALE, waitingOnly = true, publicOnly = true, text = "映画")
        assertEquals(0, advancedFilterCount(quick))
    }

    @Test fun advancedFiltersAreCountedOncePerCondition() {
        val criteria = RoomSearchCriteria(
            name = "名前", message = "雑談", excluded = "業者",
            minAge = 20, maxAge = 30, includeUnknownAge = false, area = "東京",
            waitingOnly = false, sort = RoomSort.ELAPSED,
        )
        assertEquals(8, advancedFilterCount(criteria))
        assertEquals(1, advancedFilterCount(RoomSearchCriteria(publicOnly = false)))
    }

    @Test fun statusTextSummarizesListInOneLine() {
        assertEquals("一覧を読み込んでいます", searchStatusText(0, 0, 0, loading = true, oldestCheck = null))
        assertEquals("下に引いて一覧を取得", searchStatusText(0, 0, 0, loading = false, oldestCheck = null))
        assertEquals("41件・3/5ページ・10/07 12:04 確認", searchStatusText(3, 5, 41, loading = false, oldestCheck = "10/07 12:04"))
        assertEquals("0件・1/1ページ", searchStatusText(1, 1, 0, loading = true, oldestCheck = null))
    }

    @Test fun activeFilterCountIncludesSearchTextAndQuickChips() {
        assertEquals(0, activeFilterCount(RoomSearchCriteria()))
        val quick = RoomSearchCriteria(gender = Gender.FEMALE, waitingOnly = true, publicOnly = true, text = "映画")
        assertEquals(4, activeFilterCount(quick))
        assertEquals(5, activeFilterCount(quick.copy(sort = RoomSort.AGE)))
        // 「満室」「非公開」はチップではなく詳しい条件として 1 件ずつ数える。
        assertEquals(2, activeFilterCount(RoomSearchCriteria(waitingOnly = false, publicOnly = false)))
    }
}
