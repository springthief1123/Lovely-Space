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
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import org.json.JSONObject
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

/** 照合用の名前・性別・年齢のうち、記録にないものを [room] から埋める。 */
private fun RoomPreference.withIdentity(room: Room): RoomPreference = copy(
    identityName = identityName ?: room.name,
    identityGender = identityGender ?: room.gender.takeUnless { it == Gender.UNKNOWN }?.name,
    identityAge = identityAge ?: room.age,
)

/**
 * 一覧で見た最新の姿を記録する。満室でピンして名前の記録がなかった部屋は、名前が見えたときに照合用の値も埋める。
 * 部屋の番号は使い回されるので、照合用の値を埋めるのは待機メッセージが記録と同じときだけ。
 */
private fun RoomPreference.refreshed(room: Room): RoomPreference =
    (if (identityName == null && room.name != null && room.message == snapshotMessage) withIdentity(room) else this).copy(
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

/** 立て直しは前の状態を引き継ぎ、利用者が承認した似た部屋は新しい状態を基準にする。 */
enum class PinMoveKind { REBUILD, ADOPTED }

/** Room DB の移動先と同じトランザクションで保存するイベント。ピン側が反映してから確認済みにする。 */
data class PendingPinMove(val id: String, val from: String, val to: Room, val kind: PinMoveKind = PinMoveKind.REBUILD)

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
    /** 保存した部屋 [from] を、利用者が同じ人と答えた新しい部屋 [to] へ移す（似た部屋の立て直し）。 */
    suspend fun moveFavorite(from: RoomPreference, to: Room) {}
    /**
     * 名前の記録がない（満室でピンした）[from] に、同じ部屋の名前が見えていた姿 [room] の名前・性別・年齢を照合用の値として残す。
     * 立て直しの照合（[followRebuilds]）やピンの表示に使う。
     */
    suspend fun rememberIdentity(from: RoomPreference, room: Room) {}
    /** ピン記録の移動がディスクに保存された後、対応する移動イベントを消す。 */
    suspend fun acknowledgePinMove(id: String) {}
}

class RoomPreferenceRepository(
    private val db: PresetDatabase,
    private val named: (Room) -> Room? = { null },
) : RoomPreferenceStore {
    private val dao = db.roomPreferences()
    override val preferences = dao.preferences()
    /** 移動を DB に永続化してから配信。アプリが途中で終了しても再起動後に同じイベントを復元する。 */
    val moved: Flow<PendingPinMove> = db.presets().pinMoves().transform { rows ->
        rows.forEach { row ->
            runCatching {
                val json = JSONObject(row.value)
                // 旧形式は立て直しのイベントのみ。種別が無いレコードとの互換性を保つ。
                val kind = if (json.has("kind")) PinMoveKind.valueOf(json.getString("kind")) else PinMoveKind.REBUILD
                PendingPinMove(row.key.removePrefix(MOVE_PREFIX), json.getString("from"),
                    readPinRoom(json.getJSONObject("to")), kind)
            }.getOrNull()?.let { emit(it) }
        }
    }

    private suspend fun queuePinMove(from: String, to: Room, kind: PinMoveKind) {
        val key = MOVE_PREFIX + UUID.randomUUID().toString()
        db.presets().put(LocalState(key, JSONObject().put("from", from)
            .put("to", pinRoomJson(to)).put("kind", kind.name).toString()))
    }

    override suspend fun acknowledgePinMove(id: String) {
        db.presets().deleteState(MOVE_PREFIX + id)
    }

    private companion object { const val MOVE_PREFIX = "pin-move/" }

    /** 登録操作でも既知の名前を保存する。満室の姿で上書きして、ピンの照合用の名前を消さない。 */
    private fun rememberedRoom(room: Room, existing: RoomPreference?): Room {
        val stored = existing?.takeIf { !it.stale && it.sameIdentity(room) }?.identityRoom()
        return borrowNamed(room, stored) ?: borrowNamed(room, named(room)) ?: room
    }

    override suspend fun setFavorite(room: Room, enabled: Boolean) {
        if (!enabled) return clearFavorite(roomHost(room), room.id)
        db.withTransaction {
            val existing = dao.preference(roomHost(room), room.id)
            val hidden = existing?.takeIf { it.sameIdentity(room) }?.hidden == true
            dao.put(preferenceFromRoom(rememberedRoom(room, existing), favorite = true, hidden = hidden))
        }
    }

    override suspend fun setHidden(room: Room, enabled: Boolean) {
        if (!enabled) return clearHidden(roomHost(room), room.id)
        db.withTransaction {
            val existing = dao.preference(roomHost(room), room.id)
            val favorite = existing?.takeIf { it.sameIdentity(room) }?.favorite == true
            dao.put(preferenceFromRoom(rememberedRoom(room, existing), favorite = favorite, hidden = true))
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
                val next = when {
                    !existing.sameIdentity(room) -> existing.copy(stale = true)
                    // 名前の記録がない（満室でピンした）部屋に、待機メッセージの違う名前付きの部屋が載った。番号の使い回しかもしれないので、
                    // 記録の待機メッセージを書き換えない（書き換えると、次に見たときにその人を部屋主として覚えてしまう）。
                    existing.identityName == null && room.message != existing.snapshotMessage -> existing
                    else -> existing.refreshed(room)
                }
                // 経過時間（「◯分前」）は取得のたびに変わる。それだけの違いで書き直すと、取得のたびに保存の一覧が流れ直して
                // 画面が計算し直すので、ほかの項目が変わったときだけ書く（経過時間もそのとき更新する。監査 C-45）。
                if (next.copy(snapshotElapsedSeconds = existing.snapshotElapsedSeconds) != existing) dao.put(next)
            }
        }
    }

    override suspend fun rememberIdentity(from: RoomPreference, room: Room) {
        if (room.name == null || roomHost(room) != from.host || room.id != from.roomId) return
        db.withTransaction {
            val existing = dao.preference(from.host, from.roomId) ?: return@withTransaction
            if (existing.identityName != null || existing.snapshotMessage != room.message || !existing.sameIdentity(room)) return@withTransaction
            dao.put(existing.withIdentity(room))
        }
    }

    override suspend fun followRebuilds(window: RoomPageWindow) {
        db.withTransaction {
            val all = dao.all()
            val claimed = all.mapTo(HashSet()) { "${it.host}/${it.roomId}" }
            all.filterNot { it.stale }.forEach { existing ->
                val key = "${existing.host}/${existing.roomId}"
                // 満室の一覧で見た後は、名前・性別・年齢が記録から消えているので、照合用に残した値で比べる。
                val snapshot = existing.toRoom()
                val old = snapshot.copy(name = existing.identityName ?: snapshot.name,
                    gender = existing.identityGender?.let(Gender::valueOf) ?: snapshot.gender, age = existing.identityAge ?: snapshot.age)
                val rebuilt = window.rebuildOf(old, claimed - key) ?: return@forEach
                dao.delete(existing.host, existing.roomId)
                dao.put(existing.movedTo(rebuilt))
                claimed -= key
                claimed += "${existing.host}/${rebuilt.id}"
                if (existing.favorite) queuePinMove(key, rebuilt, PinMoveKind.REBUILD)
            }
        }
    }

    override suspend fun moveFavorite(from: RoomPreference, to: Room) {
        if (Genres[to.genreKey]?.host != from.host) return
        val key = "${from.host}/${from.roomId}"
        db.withTransaction {
            val current = dao.preference(from.host, from.roomId)?.takeIf { it.favorite } ?: return@withTransaction
            val existing = dao.preference(from.host, to.id)
            if (current.hidden) dao.clearFavorite(from.host, from.roomId).also { dao.deleteUnused(from.host, from.roomId) }
            else dao.delete(from.host, from.roomId)
            // 似た部屋は名前が違うことがあるので、照合用の名前は新しい部屋のものにする。
            dao.put(current.movedTo(to, keepIdentity = false).copy(favorite = true, hidden = existing?.takeIf { it.sameIdentity(to) }?.hidden == true))
            queuePinMove(key, to, PinMoveKind.ADOPTED)
        }
    }

    private fun RoomPreference.movedTo(room: Room, keepIdentity: Boolean = true): RoomPreference = refreshed(room).copy(
        roomId = room.id,
        stale = false,
        identityName = room.name ?: identityName.takeIf { keepIdentity },
        identityGender = room.gender.takeUnless { it == Gender.UNKNOWN }?.name ?: identityGender.takeIf { keepIdentity },
        identityAge = room.age ?: identityAge.takeIf { keepIdentity },
    )

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
