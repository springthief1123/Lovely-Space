package io.github.springthief1123.lovelyspace.data

import androidx.room.withTransaction
import io.github.springthief1123.lovelyspace.core.Gender
import io.github.springthief1123.lovelyspace.core.Genres
import io.github.springthief1123.lovelyspace.core.Room
import io.github.springthief1123.lovelyspace.core.RoomAction
import io.github.springthief1123.lovelyspace.core.RoomStatus
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration.Companion.seconds

fun RoomPreference.toRoom(): Room = Room(
    id = roomId,
    genreKey = genreKey,
    status = RoomStatus.valueOf(snapshotStatus),
    action = RoomAction.valueOf(snapshotAction),
    elapsed = snapshotElapsedSeconds?.seconds,
    name = snapshotName,
    gender = Gender.valueOf(snapshotGender),
    age = snapshotAge,
    area = snapshotArea,
    message = snapshotMessage,
)

fun RoomPreference.appliesTo(room: Room): Boolean = !stale && sameIdentity(room)

/**
 * 立て直した部屋を見分ける印（サーバー・名前・待機メッセージ）。部屋を閉じて同じ内容で作り直すと ID が変わるので、
 * 非表示は ID のほかにこの印でも効かせる（Chrome 拡張と同じ）。満室で名前が見えない部屋や、短くてよくある待機メッセージには付けない。
 */
internal fun roomSignature(host: String?, name: String?, message: String): String? {
    if (host == null || name.isNullOrBlank() || message.trim().length < MIN_SIGNATURE_MESSAGE) return null
    return "$host\n${name.trim()}\n${message.trim()}"
}

internal fun roomSignature(room: Room): String? = roomSignature(Genres[room.genreKey]?.host, room.name, room.message)

/** 非表示にしたときの部屋の印。名前は非表示にしたときのもの（満室で見えなくなった後の記録でも残る）。 */
internal fun RoomPreference.signature(): String? = roomSignature(host, identityName ?: snapshotName, snapshotMessage)

/** 印で見分けるのに使う待機メッセージの最短の長さ。「よろしく」のような短い文は別の人でも同じになりやすい。 */
internal const val MIN_SIGNATURE_MESSAGE = 8

/**
 * 非表示の記録 [this] のどれかが [room] を隠すか。同じ部屋の記録のほか、名前と待機メッセージが同じ立て直した部屋も隠す。
 * ただし [room] 自身に保存（お気に入り）の記録があれば、その記録に従う。
 */
fun List<RoomPreference>.hides(room: Room): Boolean {
    firstOrNull { it.appliesTo(room) }?.let { return it.hidden }
    val signature = roomSignature(room) ?: return false
    return any { it.hidden && it.signature() == signature }
}

internal fun RoomPreference.sameIdentity(room: Room): Boolean {
    if (room.id != roomId || Genres[room.genreKey]?.host != host || room.genreKey != genreKey) return false
    if (identityName != null && room.name != null && identityName != room.name) return false
    if (identityGender != null && room.gender != Gender.UNKNOWN && identityGender != room.gender.name) return false
    if (identityAge != null && room.age != null && identityAge != room.age) return false
    return true
}

private fun RoomPreference.refreshed(room: Room): RoomPreference = copy(
    genreKey = room.genreKey,
    snapshotStatus = room.status.name,
    snapshotAction = room.action.name,
    snapshotElapsedSeconds = room.elapsed?.inWholeSeconds,
    snapshotName = room.name,
    snapshotGender = room.gender.name,
    snapshotAge = room.age,
    snapshotArea = room.area,
    snapshotMessage = room.message,
)

private fun preferenceFromRoom(room: Room, favorite: Boolean, hidden: Boolean): RoomPreference {
    val host = requireNotNull(Genres[room.genreKey]) { "Unknown genre: ${room.genreKey}" }.host
    return RoomPreference(
        host = host,
        roomId = room.id,
        genreKey = room.genreKey,
        favorite = favorite,
        hidden = hidden,
        snapshotStatus = room.status.name,
        snapshotAction = room.action.name,
        snapshotElapsedSeconds = room.elapsed?.inWholeSeconds,
        snapshotName = room.name,
        snapshotGender = room.gender.name,
        snapshotAge = room.age,
        snapshotArea = room.area,
        snapshotMessage = room.message,
        identityName = room.name,
        identityGender = room.gender.takeUnless { it == Gender.UNKNOWN }?.name,
        identityAge = room.age,
    )
}

interface RoomPreferenceStore {
    val preferences: Flow<List<RoomPreference>>
    suspend fun setFavorite(room: Room, enabled: Boolean)
    suspend fun setHidden(room: Room, enabled: Boolean)
    suspend fun clearFavorite(host: String, roomId: Long)
    suspend fun clearHidden(host: String, roomId: Long)
    /** 解除した保存を「元に戻す」。解除前の記録 [value] をそのまま戻し、その間に非表示にしていればそれも残す。 */
    suspend fun restoreFavorite(value: RoomPreference)
    suspend fun observe(rooms: List<Room>)
}

class RoomPreferenceRepository(private val db: PresetDatabase) : RoomPreferenceStore {
    private val dao = db.roomPreferences()
    override val preferences = dao.preferences()

    override suspend fun setFavorite(room: Room, enabled: Boolean) {
        if (!enabled) return clearFavorite(roomHost(room), room.id)
        db.withTransaction {
            val existing = dao.preference(roomHost(room), room.id)
            val hidden = existing?.takeIf { it.sameIdentity(room) }?.hidden == true
            dao.put(preferenceFromRoom(room, favorite = true, hidden = hidden))
        }
    }

    override suspend fun setHidden(room: Room, enabled: Boolean) {
        if (!enabled) return clearHidden(roomHost(room), room.id)
        db.withTransaction {
            val existing = dao.preference(roomHost(room), room.id)
            val favorite = existing?.takeIf { it.sameIdentity(room) }?.favorite == true
            dao.put(preferenceFromRoom(room, favorite = favorite, hidden = true))
        }
    }

    override suspend fun clearFavorite(host: String, roomId: Long) {
        db.withTransaction {
            dao.clearFavorite(host, roomId)
            dao.deleteUnused(host, roomId)
        }
    }

    override suspend fun clearHidden(host: String, roomId: Long) {
        db.withTransaction {
            dao.clearHidden(host, roomId)
            dao.deleteUnused(host, roomId)
        }
    }

    override suspend fun restoreFavorite(value: RoomPreference) {
        db.withTransaction {
            val existing = dao.preference(value.host, value.roomId)
            dao.put(value.copy(favorite = true, hidden = value.hidden || existing?.hidden == true))
        }
    }

    override suspend fun observe(rooms: List<Room>) {
        if (rooms.isEmpty()) return
        db.withTransaction {
            rooms.forEach { room ->
                val existing = dao.preference(roomHost(room), room.id) ?: return@forEach
                val next = if (existing.sameIdentity(room)) existing.refreshed(room) else existing.copy(stale = true)
                if (next != existing) dao.put(next)
            }
        }
    }

    private fun roomHost(room: Room): String =
        requireNotNull(Genres[room.genreKey]) { "Unknown genre: ${room.genreKey}" }.host
}
