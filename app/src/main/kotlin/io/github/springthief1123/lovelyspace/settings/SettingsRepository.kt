package io.github.springthief1123.lovelyspace.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.springthief1123.lovelyspace.core.chat.EntryProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode(val label: String) {
    SYSTEM("端末の設定に合わせる"),
    LIGHT("ライト"),
    DARK("ダーク"),
}

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private val themeKey = stringPreferencesKey("theme_mode")
    private val entryNameKey = stringPreferencesKey("entry_name")
    private val entrySexKey = intPreferencesKey("entry_sex")
    private val entryYearsKey = intPreferencesKey("entry_years")
    private val roomPrefectureKey = intPreferencesKey("room_prefecture")
    private val roomMessageKey = stringPreferencesKey("room_message")

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { prefs ->
        prefs[themeKey]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[themeKey] = mode.name }
    }

    /** 前回入室したときの名前・性別・年齢。既定のプリセットが無い場合の入力補助。 */
    val lastEntryProfile: Flow<EntryProfile?> = context.dataStore.data.map { prefs ->
        val name = prefs[entryNameKey] ?: return@map null
        EntryProfile(name = name, sex = prefs[entrySexKey] ?: 1, years = prefs[entryYearsKey])
    }

    suspend fun setLastEntryProfile(profile: EntryProfile) {
        context.dataStore.edit {
            it[entryNameKey] = profile.name
            it[entrySexKey] = profile.sex
            val years = profile.years
            if (years != null) it[entryYearsKey] = years else it.remove(entryYearsKey)
        }
    }

    /** 前回の部屋作成で使った都道府県（null は秘密）と待機メッセージ。 */
    val lastRoomDetails: Flow<RoomDetails> = context.dataStore.data.map { prefs ->
        RoomDetails(prefecture = prefs[roomPrefectureKey], message = prefs[roomMessageKey].orEmpty())
    }

    suspend fun setLastRoomDetails(details: RoomDetails) {
        context.dataStore.edit {
            val prefecture = details.prefecture
            if (prefecture != null) it[roomPrefectureKey] = prefecture else it.remove(roomPrefectureKey)
            it[roomMessageKey] = details.message
        }
    }
}

data class RoomDetails(val prefecture: Int?, val message: String)
