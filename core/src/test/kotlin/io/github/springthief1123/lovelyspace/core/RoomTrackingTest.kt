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
}
