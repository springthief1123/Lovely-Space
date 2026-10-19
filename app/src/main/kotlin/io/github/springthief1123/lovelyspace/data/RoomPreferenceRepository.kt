package io.github.springthief1123.lovelyspace.data

import androidx.room.withTransaction
import io.github.springthief1123.lovelyspace.core.Gender
import io.github.springthief1123.lovelyspace.core.Genres
import io.github.springthief1123.lovelyspace.core.Room
import io.github.springthief1123.lovelyspace.core.RoomAction
import io.github.springthief1123.lovelyspace.core.RoomPageWindow
import io.github.springthief1123.lovelyspace.core.RoomQuery
import io.github.springthief1123.lovelyspace.core.RoomStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
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
    /** 保存・非表示の部屋が閉じて立て直されていれば（[RoomPageWindow.rebuildOf]）、記録を新しい部屋へ移す。[window] はジャンル全体の一覧。 */
    suspend fun followRebuilds(window: RoomPageWindow) {}
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

    override suspend fun followRebuilds(window: RoomPageWindow) {
        db.withTransaction {
            val all = dao.all()
            val claimed = all.mapTo(HashSet()) { "${it.host}/${it.roomId}" }
            all.filterNot { it.stale }.forEach { existing ->
                val key = "${existing.host}/${existing.roomId}"
                val old = existing.toRoom().copy(name = existing.identityName ?: existing.snapshotName)
                val rebuilt = window.rebuildOf(old, claimed - key) ?: return@forEach
                dao.delete(existing.host, existing.roomId)
                dao.put(existing.refreshed(rebuilt).copy(
                    roomId = rebuilt.id,
                    identityName = rebuilt.name ?: existing.identityName,
                    identityGender = rebuilt.gender.takeUnless { it == Gender.UNKNOWN }?.name ?: existing.identityGender,
                    identityAge = rebuilt.age ?: existing.identityAge,
                ))
                claimed -= key
                claimed += "${existing.host}/${rebuilt.id}"
            }
        }
    }

    /**
     * 一覧を読むたびに（どの画面・背景の確認が読んだページでも）、そのジャンルの全体の一覧で [followRebuilds] を行う。
     * 立て直された非表示の部屋がまた出たり、保存した部屋を見失ったりしないようにする。
     */
    suspend fun followRebuilds(observations: StateFlow<Map<RoomQuery, ObservedRoomPage>>) {
        var seen = 0L
        observations.collect { all ->
            val fresh = all.values.filter { it.revision > seen && it.query == RoomQuery(it.query.genre, page = it.query.page) }
            seen = maxOf(seen, all.values.maxOfOrNull { it.revision } ?: 0L)
            fresh.map { it.query.genre }.distinct().forEach { genre ->
                // 保存の失敗などで一覧の監視を止めない。次に読んだページでやり直す。
                try { followRebuilds(all.genreWindow(genre)) } catch (e: CancellationException) { throw e } catch (e: Exception) { }
            }
        }
    }

    private fun roomHost(room: Room): String =
        requireNotNull(Genres[room.genreKey]) { "Unknown genre: ${room.genreKey}" }.host
}
