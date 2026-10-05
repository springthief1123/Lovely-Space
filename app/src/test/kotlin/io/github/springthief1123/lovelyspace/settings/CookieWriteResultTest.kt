package io.github.springthief1123.lovelyspace.settings

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class CookieWriteResultTest {
    @Test fun returnsOnlyWhenAccepted() {
        CookieWriteResult().apply { complete(true) }.awaitApplied(0)
    }
    @Test fun rejectionDoesNotContinueWithThePreviousCookie() {
        val result = CookieWriteResult().apply { complete(false) }
        assertThrows(IOException::class.java) { result.awaitApplied(0) }
    }
    @Test fun preservesHandlerFailureAndHandlesMissingCallback() {
        val cause = IllegalArgumentException("合成の失敗")
        val result = CookieWriteResult().apply { fail(cause) }
        val failure = assertThrows(IOException::class.java) { result.awaitApplied(0) }
        assertSame(cause, failure.cause)
        assertThrows(IOException::class.java) { CookieWriteResult().awaitApplied(0) }
    }
}
