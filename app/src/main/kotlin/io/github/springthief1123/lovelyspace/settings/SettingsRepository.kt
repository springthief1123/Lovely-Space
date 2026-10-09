package io.github.springthief1123.lovelyspace.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.springthief1123.lovelyspace.core.Genres
import io.github.springthief1123.lovelyspace.core.RefreshPacing
import io.github.springthief1123.lovelyspace.core.chat.EntryProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode(val label: String) {
    SYSTEM("端末の設定に合わせる"),
    LIGHT("ライト"),
    DARK("ダーク"),
}

enum class TextScale(val label: String, val multiplier: Float) {
    COMPACT("小さめ", 0.92f),
    STANDARD("標準", 1f),
    LARGE("大きめ", 1.1f),
}

/** 部屋カードに表示する待機メッセージの最大行数。 */
enum class RoomMessageLines(val label: String, val maxLines: Int) {
    TWO("2行", 2),
    THREE("3行", 3),
    FOUR("4行", 4),
    ALL("すべて", Int.MAX_VALUE),
}

/** 端末通知に出す内容。他の利用者の待機メッセージ（募集文）をどこまで見せるか。 */
enum class NotificationPreview(val label: String, val description: String) {
    FULL("すべて表示", "ロック画面でも待機メッセージまで表示"),
    HIDE_ON_LOCK_SCREEN("ロック画面では隠す", "ロック中は「新しいお知らせ」とだけ表示"),
    NO_MESSAGE("待機メッセージを出さない", "通知には待機メッセージを出さず、ロック画面でも隠す"),
}

enum class RoomListStartMode(val label: String) {
    LAST_USED("最後に見たジャンル"),
    DEFAULT("指定したジャンル"),
}

data class RoomListPreferences(
    val startMode: RoomListStartMode = RoomListStartMode.LAST_USED,
    val defaultGenreKey: String = Genres.default.key,
    val lastGenreKey: String = Genres.default.key,
) {
    fun initialGenreKey(): String = when (startMode) {
        RoomListStartMode.LAST_USED -> lastGenreKey
        RoomListStartMode.DEFAULT -> defaultGenreKey
    }.takeIf { Genres[it] != null } ?: Genres.default.key
}

interface RoomListPreferenceStore {
    val roomListPreferences: Flow<RoomListPreferences>
    suspend fun setLastRoomGenre(genreKey: String)
}

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) : RoomListPreferenceStore {
    private val themeKey = stringPreferencesKey("theme_mode")
    private val textScaleKey = stringPreferencesKey("text_scale")
    private val roomMessageLinesKey = stringPreferencesKey("room_message_lines")
    private val roomListStartModeKey = stringPreferencesKey("room_list_start_mode")
    private val defaultRoomGenreKey = stringPreferencesKey("default_room_genre")
    private val lastRoomGenreKey = stringPreferencesKey("last_room_genre")
    private val entryNameKey = stringPreferencesKey("entry_name")
    private val entrySexKey = intPreferencesKey("entry_sex")
    private val entryYearsKey = intPreferencesKey("entry_years")
    private val roomPrefectureKey = intPreferencesKey("room_prefecture")
    private val roomMessageKey = stringPreferencesKey("room_message")
    private val notificationPreviewKey = stringPreferencesKey("notification_preview")
    private val minIntervalKey = longPreferencesKey("pacing_min_interval_ms")
    private val searchHeadKey = longPreferencesKey("pacing_search_head_ms")
    private val radarHeadKey = longPreferencesKey("pacing_radar_head_ms")
    private val waitlistKey = longPreferencesKey("pacing_waitlist_ms")
    private val publicRoomKey = longPreferencesKey("pacing_public_room_ms")

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { prefs ->
        prefs[themeKey]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM
    }

    val textScale: Flow<TextScale> = context.dataStore.data.map { prefs ->
        prefs[textScaleKey]?.let { runCatching { TextScale.valueOf(it) }.getOrNull() } ?: TextScale.STANDARD
    }

    val roomMessageLines: Flow<RoomMessageLines> = context.dataStore.data.map { prefs ->
        prefs[roomMessageLinesKey]?.let { runCatching { RoomMessageLines.valueOf(it) }.getOrNull() } ?: RoomMessageLines.FOUR
    }

    override val roomListPreferences: Flow<RoomListPreferences> = context.dataStore.data.map { prefs ->
        RoomListPreferences(
            startMode = prefs[roomListStartModeKey]
                ?.let { runCatching { RoomListStartMode.valueOf(it) }.getOrNull() }
                ?: RoomListStartMode.LAST_USED,
            defaultGenreKey = prefs[defaultRoomGenreKey]
                ?.takeIf { Genres[it] != null }
                ?: Genres.default.key,
            lastGenreKey = prefs[lastRoomGenreKey]
                ?.takeIf { Genres[it] != null }
                ?: Genres.default.key,
        )
    }

    val notificationPreview: Flow<NotificationPreview> = context.dataStore.data.map { prefs ->
        prefs[notificationPreviewKey]?.let { runCatching { NotificationPreview.valueOf(it) }.getOrNull() }
            ?: NotificationPreview.HIDE_ON_LOCK_SCREEN
    }

    /** 本家への自動の取り直しの間隔。未設定の項目は既定値、下限を割る値は下限にする。 */
    val refreshPacing: Flow<RefreshPacing> = context.dataStore.data.map { prefs ->
        RefreshPacing(
            minIntervalMs = prefs[minIntervalKey] ?: RefreshPacing.DEFAULT_MIN_INTERVAL_MS,
            searchHeadMs = prefs[searchHeadKey] ?: RefreshPacing.DEFAULT_SEARCH_HEAD_MS,
            radarHeadMs = prefs[radarHeadKey] ?: RefreshPacing.DEFAULT_RADAR_HEAD_MS,
            waitlistMs = prefs[waitlistKey] ?: RefreshPacing.DEFAULT_WAITLIST_MS,
            publicRoomMs = prefs[publicRoomKey] ?: RefreshPacing.DEFAULT_PUBLIC_ROOM_MS,
        ).sanitized()
    }

    suspend fun setRefreshPacing(pacing: RefreshPacing) {
        val value = pacing.sanitized()
        context.dataStore.edit {
            it[minIntervalKey] = value.minIntervalMs
            it[searchHeadKey] = value.searchHeadMs
            it[radarHeadKey] = value.radarHeadMs
            it[waitlistKey] = value.waitlistMs
            it[publicRoomKey] = value.publicRoomMs
        }
    }

    suspend fun setNotificationPreview(preview: NotificationPreview) {
        context.dataStore.edit { it[notificationPreviewKey] = preview.name }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[themeKey] = mode.name }
    }

    suspend fun setTextScale(scale: TextScale) {
        context.dataStore.edit { it[textScaleKey] = scale.name }
    }

    suspend fun setRoomMessageLines(lines: RoomMessageLines) {
        context.dataStore.edit { it[roomMessageLinesKey] = lines.name }
    }

    suspend fun setRoomListStartMode(mode: RoomListStartMode) {
        context.dataStore.edit { it[roomListStartModeKey] = mode.name }
    }

    suspend fun setDefaultRoomGenre(genreKey: String) {
        requireNotNull(Genres[genreKey]) { "Unknown genre: $genreKey" }
        context.dataStore.edit { it[defaultRoomGenreKey] = genreKey }
    }

    override suspend fun setLastRoomGenre(genreKey: String) {
        requireNotNull(Genres[genreKey]) { "Unknown genre: $genreKey" }
        context.dataStore.edit { it[lastRoomGenreKey] = genreKey }
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
