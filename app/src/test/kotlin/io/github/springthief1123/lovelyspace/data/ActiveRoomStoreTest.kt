package io.github.springthief1123.lovelyspace.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.springthief1123.lovelyspace.core.chat.ChatRoomRef
import io.github.springthief1123.lovelyspace.ui.chat.ResumableRoom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ActiveRoomStoreTest {
    // 合成データ。本家の実データは使わない。
    private val saved = ResumableRoom("session-1", ChatRoomRef("2shot.chat.shalove.net", 900000001L, "0123456789abcdef", "zenkoku"), 1_000L)

    /** Robolectric では Android Keystore が使えないので、可逆な変換で代用する。 */
    private class XorBox : SecretBox {
        override fun seal(plain: ByteArray) = ByteArray(plain.size) { (plain[it].toInt() xor 0x5A).toByte() }
        override fun open(sealed: ByteArray) = seal(sealed)
    }

    private val prefs = ApplicationProvider.getApplicationContext<Application>()
        .getSharedPreferences(ActiveRoomStore.PREFS, Context.MODE_PRIVATE)

    @Test fun roundTripsAndKeepsPwdOutOfPlainText() {
        val store = ActiveRoomStore(prefs, XorBox())
        store.save(saved)
        assertEquals(saved, ActiveRoomStore(prefs, XorBox()).load())
        val raw = prefs.all.values.joinToString()
        assertFalse(raw.contains(saved.room.pwd))
    }

    @Test fun clearRemovesTheRecord() {
        val store = ActiveRoomStore(prefs, XorBox())
        store.save(saved)
        store.clear()
        assertNull(store.load())
    }

    @Test fun undecryptableRecordIsDropped() {
        ActiveRoomStore(prefs, XorBox()).save(saved)
        val broken = object : SecretBox {
            override fun seal(plain: ByteArray) = plain
            override fun open(sealed: ByteArray): ByteArray = throw javax.crypto.AEADBadTagException()
        }
        assertNull(ActiveRoomStore(prefs, broken).load())
        assertNull(ActiveRoomStore(prefs, XorBox()).load())
    }
}
