package io.github.springthief1123.lovelyspace.data

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.springthief1123.lovelyspace.core.chat.ChatRoomRef
import io.github.springthief1123.lovelyspace.ui.chat.ActiveRoomPersistence
import io.github.springthief1123.lovelyspace.ui.chat.ResumableRoom
import org.json.JSONObject
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 端末内で値を暗号化・復号する。 */
interface SecretBox {
    fun seal(plain: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
}

/** Android Keystore の AES 鍵で暗号化する（鍵は端末の外へ出せない）。先頭 12 バイトが IV。 */
class KeystoreSecretBox(private val alias: String = "lovely-space-active-room") : SecretBox {
    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
        }.generateKey()
    }

    override fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, IV_SIZE))
        return cipher.doFinal(sealed, IV_SIZE, sealed.size - IV_SIZE)
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}

/**
 * 進行中の部屋を 1 つだけ保存する。部屋の pwd を含むので、記録全体を [box] で暗号化してから書く。
 * 復号できない記録（鍵の消失・アプリの再インストールなど）は捨てる。
 */
class ActiveRoomStore(private val prefs: SharedPreferences, private val box: SecretBox) : ActiveRoomPersistence {
    override fun load(): ResumableRoom? {
        val sealed = prefs.getString(KEY, null) ?: return null
        return try {
            val json = JSONObject(String(box.open(Base64.getDecoder().decode(sealed)), Charsets.UTF_8))
            ResumableRoom(
                sessionId = json.getString("session"),
                room = ChatRoomRef(json.getString("host"), json.getLong("roomId"), json.getString("pwd"), json.getString("genre")),
                savedAt = json.getLong("savedAt"),
            )
        } catch (e: Exception) {
            clear()
            null
        }
    }

    override fun save(value: ResumableRoom) {
        val json = JSONObject()
            .put("session", value.sessionId)
            .put("host", value.room.host)
            .put("roomId", value.room.roomId)
            .put("pwd", value.room.pwd)
            .put("genre", value.room.genreKey)
            .put("savedAt", value.savedAt)
        val sealed = Base64.getEncoder().encodeToString(box.seal(json.toString().toByteArray(Charsets.UTF_8)))
        prefs.edit().putString(KEY, sealed).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    companion object {
        const val PREFS = "active_room"
        private const val KEY = "room"
    }
}
