package io.github.springthief1123.lovelyspace.core

import org.junit.Assert.*
import org.junit.Test

class RoomTrackingTest {
    private val target = Room(42, "zenkoku", RoomStatus.WAITING, RoomAction.ENTER, null, "合成", Gender.FEMALE, 25, null, "本文")
    @Test fun partialPageAndHiddenProfileNeverProveAbsenceOrIdentity() {
        assertEquals(RoomIdentityEvidence.NOT_OBSERVED, roomIdentityEvidence(target, null))
        assertEquals(RoomIdentityEvidence.AMBIGUOUS, roomIdentityEvidence(target, target.copy(name = null, status = RoomStatus.FULL)))
        assertEquals(RoomIdentityEvidence.NOT_OBSERVED, roomIdentityEvidence(target, target.copy(genreKey = "talk")))
    }
    @Test fun reusingIdWithConflictingVisibleAttributesStopsTracking() {
        assertEquals(RoomIdentityEvidence.REUSED, roomIdentityEvidence(target, target.copy(name = "別の合成")))
        assertEquals(RoomIdentityEvidence.REUSED, roomIdentityEvidence(target, target.copy(age = 30)))
        assertEquals(RoomIdentityEvidence.REUSED, roomIdentityEvidence(target, target.copy(gender = Gender.MALE)))
        assertEquals(RoomIdentityEvidence.MATCH, roomIdentityEvidence(target, target.copy(message = "変化", age = null)))
    }
    @Test fun combinedSearchSpansNameAndMessageAndKeepsExclusions() {
        assertTrue(RoomSearchCriteria(text = "合成 本文").matches(target))
        assertTrue(RoomSearchCriteria(text = "合成 該当なし", keywordMode = KeywordMode.ANY).matches(target))
        assertFalse(RoomSearchCriteria(text = "合成", excluded = "本文").matches(target))
        assertFalse(RoomSearchCriteria(text = "合成 該当なし").matches(target))
    }
    @Test fun rebuildNeedsTheSameNameAndMessageAndALaterId() {
        val old = target.copy(message = "合成の募集文です、よろしく")
        val rebuilt = old.copy(id = 50, message = "合成の募集文です､よろしく ")
        assertTrue(isRebuildOf(old, rebuilt))
        assertFalse(isRebuildOf(old, rebuilt.copy(id = 41)))
        assertFalse(isRebuildOf(old, rebuilt.copy(name = "別の合成")))
        assertFalse(isRebuildOf(old, rebuilt.copy(message = "書き換えた合成の募集文")))
        assertFalse(isRebuildOf(old, rebuilt.copy(age = 30)))
        assertFalse(isRebuildOf(old, rebuilt.copy(gender = Gender.MALE)))
        assertFalse(isRebuildOf(old, rebuilt.copy(genreKey = "talk")))
        assertFalse(isRebuildOf(old.copy(message = " "), rebuilt.copy(message = "")))
    }
    @Test fun withoutANameOnlyALongMessageIdentifiesARebuild() {
        // 満室の部屋は名前が出ない。短い定型文は別の人と重なりやすいので、名前が分からなければ長い募集文のときだけ照合する。
        val full = target.copy(status = RoomStatus.FULL, name = null, gender = Gender.UNKNOWN)
        assertFalse(isRebuildOf(full, target.copy(id = 50)))
        val long = "合成の長い募集文です。よろしくお願いします"
        assertTrue(isRebuildOf(full.copy(message = long), target.copy(id = 50, message = long)))
    }
    @Test fun recentMatchesFindTheClosedRoomARebuildCameFrom() {
        val recent = RecentMatches().add(1_000L, listOf(target, target.copy(id = 43, name = "別の合成", message = "別の本文")))
        val rebuilt = target.copy(id = 50)
        // 元の部屋がまだ一覧にあれば立て直しではない。
        assertNull(recent.rebuildOf(rebuilt, present = setOf(roomIdentity(target))))
        assertEquals(target, recent.rebuildOf(rebuilt, present = emptySet()))
        // 名前も募集文も違えば別の部屋。
        assertNull(recent.rebuildOf(target.copy(id = 51, name = "新しい合成", message = "新しい本文"), emptySet()))
    }
    @Test fun recentMatchesKeepTheVisibleNameAndForgetOldMatches() {
        val recent = RecentMatches().add(1_000L, listOf(target)).add(2_000L, listOf(target.copy(name = null, gender = Gender.UNKNOWN, age = null, status = RoomStatus.FULL)))
        assertEquals("合成", recent.entries.values.single().second.name)
        assertTrue(recent.add(1_000L + RecentMatches.MAX_AGE_MS + 1_000L, emptyList()).entries.isEmpty())
    }
    @Test fun twoPossibleOriginalsAreNotGuessed() {
        val recent = RecentMatches().add(1_000L, listOf(target, target.copy(id = 43)))
        assertNull(recent.rebuildOf(target.copy(id = 50), emptySet()))
    }
}
