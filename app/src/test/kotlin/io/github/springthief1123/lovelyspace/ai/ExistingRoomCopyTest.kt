package io.github.springthief1123.lovelyspace.ai

import android.app.Application
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 合成文の原文コピーだけを検査。本家の実募集文はテストへ含めない。 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class ExistingRoomCopyTest {
    @Test fun copiesSelectedMessageWithoutRedactionOrMetadata() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val message = "カカオを使って話したい。記号：@sample_user"
        copyPublicMessage(context, message)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(message, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        assertEquals(1, clipboard.primaryClip?.itemCount)
        assertTrue(clipboard.primaryClipDescription?.extras?.getBoolean(
            ClipDescription.EXTRA_IS_SENSITIVE,
        ) == true)
    }
}
