package io.github.springthief1123.lovelyspace.ui.chat

import org.junit.Assert.*
import org.junit.Test

class ChatDraftRecoveryTest {
    @Test fun failedSendKeepsBothSentTextAndTheNextDraftAndRequiresManualResolution() {
        val failed = ChatUiState(isLoading = false, input = "次に書いた文章", isSending = true)
            .sendingFailed("送信した文章", "合成の通信エラー")
        assertFalse(failed.isSending)
        assertFalse(failed.canSend)
        assertEquals("次に書いた文章", failed.input)
        assertEquals("送信した文章", failed.failedMessage)
        val restored = failed.restoreFailedMessage()
        assertEquals("送信した文章\n\n次に書いた文章", restored.input)
        assertNull(restored.failedMessage)
        assertNull(restored.sendError)
        assertTrue(restored.canSend)
        assertEquals(restored, restored.restoreFailedMessage())
    }

    @Test fun restoringIntoEmptyDraftKeepsAllLineBreaks() {
        val failed = ChatUiState(isLoading = false).sendingFailed("一行目\n二行目", "合成のエラー")
        assertEquals("一行目\n二行目", failed.restoreFailedMessage().input)
    }

    @Test fun discardingOnlyFailedTextPreservesNewDraft() {
        val resolved = ChatUiState(isLoading = false, input = "残す下書き")
            .sendingFailed("破棄する文章", "合成のエラー").discardFailedMessage()
        assertEquals("残す下書き", resolved.input)
        assertNull(resolved.failedMessage)
        assertTrue(resolved.canSend)
    }

    @Test fun recoveredDraftCannotSendWhileLeavingOrAfterRoomEnds() {
        val failed = ChatUiState(isLoading = false).sendingFailed("合成の文章", "合成のエラー")
        assertFalse(failed.copy(isLeaving = true).restoreFailedMessage().canSend)
        assertFalse(failed.copy(endMessage = "合成の終了通知").restoreFailedMessage().canSend)
    }
}
