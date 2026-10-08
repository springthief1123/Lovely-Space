package io.github.springthief1123.lovelyspace.ui.chat

import io.github.springthief1123.lovelyspace.core.chat.ChatLine
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatActivityTest {
    // 合成データ。本家の実データは使わない。
    private fun line(id: Long, mine: Boolean) = UiLine(id, ChatLine(speaker = "合成$id", speakerIsFemale = false, text = "合成の発言", time = null), isMine = mine)

    @Test fun onlyLinesFromOthersArrivingWhileAwayAreCounted() {
        val added = listOf(line(1, mine = false), line(2, mine = true), line(3, mine = false))
        assertEquals(2, unseenAfter(0, visible = false, added))
        assertEquals(5, unseenAfter(3, visible = false, added))
        // 会話画面が見えている間は数えない。
        assertEquals(0, unseenAfter(3, visible = true, added))
    }

    @Test fun resumeBarSummaryPrefersEndThenNewMessagesThenWaiting() {
        assertEquals("全国の進行中の部屋", resumeSummary("全国", null))
        assertEquals("新着 2件・全国", resumeSummary("全国", ChatActivity(unseen = 2, waitingForPartner = true)))
        assertEquals("相手の入室を待っています・全国", resumeSummary("全国", ChatActivity(waitingForPartner = true)))
        assertEquals("全国の進行中の部屋・新着を確認しています", resumeSummary("全国", ChatActivity()))
        assertEquals("部屋は終了しました・開くと理由を確認できます", resumeSummary("全国", ChatActivity(unseen = 1, ended = true)))
    }

    @Test fun emptyChatTextDependsOnWhoIsWaiting() {
        assertEquals("相手を待っています", chatEmptyText(ChatUiState(isOwner = true, isFilled = false)).first)
        assertEquals("まだ発言はありません", chatEmptyText(ChatUiState(isOwner = true, isFilled = true)).first)
        assertEquals("入室しました", chatEmptyText(ChatUiState()).first)
    }
}
