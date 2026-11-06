package io.github.springthief1123.lovelyspace.ui

import io.github.springthief1123.lovelyspace.core.AutomaticAccessPausedException
import io.github.springthief1123.lovelyspace.core.chat.ChatResponseUnavailableException
import io.github.springthief1123.lovelyspace.core.chat.ChatSendUnconfirmedException
import org.junit.Assert.assertEquals
import org.junit.Test

/** 共通のIOException表示へ落ちず、統合前の両方の案内を保つ。 */
class ErrorTextMergeTest {
    @Test fun mergedErrorHandlingPreservesPauseAndUnconfirmedSendMessages() {
        val invalidResponse = ChatResponseUnavailableException()
        for (error in listOf(AutomaticAccessPausedException(), invalidResponse, ChatSendUnconfirmedException(invalidResponse))) {
            assertEquals(error.message, describeError(error))
        }
    }
}
