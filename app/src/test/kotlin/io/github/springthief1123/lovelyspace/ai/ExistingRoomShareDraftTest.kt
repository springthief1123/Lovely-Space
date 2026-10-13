package io.github.springthief1123.lovelyspace.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExistingRoomShareDraftTest {
    @Test fun redactContactsWithoutErasingAllTopicWords() {
        val original = "カカオID: sample_user123 で話そう。メール test@example.com と https://example.org/abc は使わない"
        val text = ExistingRoomShareDraft.prepare(original)
        assertTrue(text.contains("カカオ ID [アカウント削除]"))
        assertTrue(text.contains("[メール削除]"))
        assertTrue(text.contains("[URL削除]"))
        assertFalse(text.contains("sample_user123"))
        assertFalse(text.contains("test@example.com"))
        assertFalse(text.contains("example.org"))
        assertFalse(ExistingRoomShareDraft.hasRecognizableIdentifiers(text))
    }

    @Test fun redactPhoneHandleAndAuthenticationStrings() {
        val text = ExistingRoomShareDraft.prepare(
            "連絡先は090-1234-5678 @user_name123、pwd=secretpass room_id:4567",
        )
        assertFalse(text.contains("090-1234"))
        assertFalse(text.contains("user_name123"))
        assertFalse(text.contains("secretpass"))
        assertTrue(text.contains("[電話番号削除]"))
        assertTrue(text.contains("[アカウント削除]"))
        assertTrue(text.contains("[認証情報削除]"))
    }

    @Test fun leaveTopicWordsAndNonIdentifyingJapaneseIntact() {
        assertEquals("カカオで話したい", ExistingRoomShareDraft.prepare(" カカオで話したい "))
        assertEquals("ゆっくり雑談しませんか", ExistingRoomShareDraft.prepare("ゆっくり雑談しませんか"))
    }

    @Test fun requireManualReviewAndBlockObviousIdentifiers() {
        assertFalse(ExistingRoomShareDraft.canCopy("雑談希望", reviewed = false))
        assertTrue(ExistingRoomShareDraft.canCopy("雑談希望", reviewed = true))
        assertFalse(ExistingRoomShareDraft.canCopy("LINE ID: abcdef", reviewed = true))
        assertFalse(ExistingRoomShareDraft.canCopy("sample@example.org", reviewed = true))
        assertFalse(ExistingRoomShareDraft.canCopy("", reviewed = true))
        assertFalse(ExistingRoomShareDraft.canCopy("あ".repeat(241), reviewed = true))
    }

    @Test fun copiedTextNeverContainsAutomaticRankingMetadata() {
        val text = ExistingRoomShareDraft.prepare("雑談が好きです")
        assertEquals("雑談が好きです", text)
        assertFalse(text.contains("room_id"))
        assertFalse(text.contains("コサイン"))
    }
}
