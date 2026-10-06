package io.github.springthief1123.lovelyspace.data

import io.github.springthief1123.lovelyspace.core.*
import org.junit.Assert.*
import org.junit.Test

class CandidateWatchTest {
    private val room = Room(1, "zenkoku", RoomStatus.WAITING, RoomAction.ENTER, null, "合成◆AbC", Gender.UNKNOWN, null, null, "募集文◆別の文字列")
    @Test fun exactNameAndVisibleIdentifierTextAreCaseSensitiveAndNeverSearchTheMessage() {
        val exact = CandidateRule(label = "合成", genreKey = "zenkoku", term = "合成◆AbC")
        assertTrue(exact.matches(room))
        assertFalse(exact.copy(term = "合成").matches(room))
        val text = exact.copy(mode = CandidateMode.DISPLAY_TEXT, term = "◆AbC")
        assertTrue(text.matches(room))
        assertFalse(text.copy(term = "◆abc").matches(room))
        assertFalse(text.copy(term = "◆別の文字列").matches(room))
        assertFalse(text.matches(room.copy(name = null)))
        assertFalse(text.matches(room.copy(genreKey = "talk")))
        assertFalse(text.copy(term = " ").matches(room))
    }
    @Test fun severalDifferentRoomsCanBeCandidatesWithoutBecomingOneTrackedIdentity() {
        val rule = CandidateRule(label = "合成", genreKey = "zenkoku", term = "合成◆AbC")
        assertTrue(rule.matches(room))
        assertTrue(rule.matches(room.copy(id = 2, gender = Gender.FEMALE, age = 40)))
        assertNotEquals(roomIdentity(room), roomIdentity(room.copy(id = 2)))
    }
}
