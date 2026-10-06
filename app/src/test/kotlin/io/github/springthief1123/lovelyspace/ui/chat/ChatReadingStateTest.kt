package io.github.springthief1123.lovelyspace.ui.chat

import io.github.springthief1123.lovelyspace.core.chat.ChatLine
import org.junit.Assert.*
import org.junit.Test

class ChatReadingStateTest {
    private fun line(id: Long, mine: Boolean = false, notice: Boolean = false) =
        UiLine(id, ChatLine(if (notice) null else "合成の発言者", false, "合成の本文", null), mine)

    @Test fun initialHistoryAndUpdatesAtLatestAreAlreadyRead() {
        val initial = ChatReadingState().onLines(listOf(line(2), line(1)))
        assertTrue(initial.atLatest)
        assertTrue(initial.unreadIds.isEmpty())
        assertTrue(initial.onLines(listOf(line(3), line(2), line(1))).unreadIds.isEmpty())
    }

    @Test fun readingOlderMessagesAccumulatesOnlyNewPartnerMessagesWithoutDuplicates() {
        val history = listOf(line(2), line(1))
        val reading = ChatReadingState().onLines(history).onViewport(1, 0)
        val next = listOf(line(5, notice = true), line(4, mine = true), line(3)) + history
        val updated = reading.onLines(next)
        assertFalse(updated.atLatest)
        assertEquals(setOf(3L), updated.unreadIds)
        assertEquals(setOf(3L), updated.onLines(next).unreadIds)
        assertEquals(setOf(3L, 6L), updated.onLines(listOf(line(6)) + next).unreadIds)
    }

    @Test fun partiallyScrolledNewestMessageDoesNotPullReaderBackToBottom() {
        val history = listOf(line(1))
        val reading = ChatReadingState().onLines(history).onViewport(0, 80)
        assertFalse(reading.atLatest)
        assertEquals(setOf(2L), reading.onLines(listOf(line(2)) + history).unreadIds)
    }

    @Test fun returningToLatestClearsUnreadAndResumesFollowing() {
        val history = listOf(line(1))
        val reading = ChatReadingState().onLines(history).onViewport(2, 0)
            .onLines(listOf(line(2)) + history).onViewport(0, 0)
        assertTrue(reading.atLatest)
        assertTrue(reading.unreadIds.isEmpty())
        assertTrue(reading.onLines(listOf(line(3), line(2)) + history).unreadIds.isEmpty())
    }

    @Test fun clearingLogRemovesUnreadAndStartsAtReplacementLog() {
        val history = listOf(line(1))
        val reading = ChatReadingState().onLines(history).onViewport(2, 0).onLines(listOf(line(2)) + history)
        val cleared = reading.onLines(emptyList())
        assertTrue(cleared.atLatest)
        assertTrue(cleared.unreadIds.isEmpty())
        assertTrue(cleared.onLines(listOf(line(3))).unreadIds.isEmpty())
        assertTrue(reading.onLines(listOf(line(4, notice = true))).unreadIds.isEmpty())
    }
}
