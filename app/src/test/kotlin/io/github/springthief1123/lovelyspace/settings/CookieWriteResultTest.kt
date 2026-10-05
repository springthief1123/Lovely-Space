package io.github.springthief1123.lovelyspace.settings

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class CookieWriteResultTest {
    @Test fun returnsOnlyWhenAccepted() {
        CookieWriteResult().apply { complete(true) }.awaitApplied(0)
    }
    @Test fun acceptedWriteCompletesOnlyAfterPersistence() {
        val result = CookieWriteResult()
        var persisted = false
        result.complete(true) {
            assertThrows(IOException::class.java) { result.awaitApplied(0) }
            persisted = true
        }
        result.awaitApplied(0)
        assertTrue(persisted)
    }
    @Test fun rejectionSkipsPersistenceAndPersistenceFailureIsReported() {
        var persisted = false
        val rejected = CookieWriteResult().apply { complete(false) { persisted = true } }
        assertThrows(IOException::class.java) { rejected.awaitApplied(0) }
        assertFalse(persisted)
        val cause = IOException("合成のディスク書き出し失敗")
        val failed = CookieWriteResult().apply { complete(true) { throw cause } }
        assertSame(cause, assertThrows(IOException::class.java) { failed.awaitApplied(0) }.cause)
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
