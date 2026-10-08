package io.github.springthief1123.lovelyspace.ui.chat

import io.github.springthief1123.lovelyspace.core.chat.ChatRoomRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 進行中の部屋の記録。[sessionId] は画面遷移の route に載せる一時 ID。 */
data class ResumableRoom(val sessionId: String, val room: ChatRoomRef, val savedAt: Long)

/** 入室中の部屋との接続（新着の取得）。[ActiveRooms] が部屋ごとに 1 つだけ持つ。 */
interface RoomConnection {
    /** アプリが前面にあるか。背景にいる間は取得を止める。 */
    fun setForeground(value: Boolean)
    /** 取得をすべて止める。退室・部屋を閉じた後。 */
    fun close()
    /** 別の部屋に入ったとき。この部屋から退室（作成者は閉じる）してから取得を止める。 */
    fun leaveAndClose()
}

/** 進行中の部屋を端末内に残す先。pwd を含むので、実装は暗号化して保存する。 */
interface ActiveRoomPersistence {
    fun load(): ResumableRoom?
    fun save(value: ResumableRoom)
    fun clear()
}

/**
 * 入室中の部屋を持つ。部屋の pwd は資格情報なので、画面遷移の route や保存状態には入れず、
 * ここで発行した一時 ID だけを渡す。
 *
 * 会話中にプロセスが終了しても戻れるよう、最後に入った部屋を 1 つだけ [persistence] に残す。
 * 退室・閉鎖・部屋の終了で消し、古すぎる記録は読み込み時に捨てる。
 *
 * 部屋との接続（[RoomConnection]）も画面ではなくここで持つ。会話画面を離れて一覧を見ている間も、
 * アプリが前面にある間は同じ接続で新着の取得を続ける。接続は部屋ごとに 1 つで、別の部屋に入ったら前の部屋から退室して接続を止める。
 */
class ActiveRooms(
    private val persistence: ActiveRoomPersistence? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val rooms = ConcurrentHashMap<String, ChatRoomRef>()
    private val _resumable = MutableStateFlow(loadSaved())

    /** 起動時などに「会話に戻る」を出す対象。 */
    val resumable: StateFlow<ResumableRoom?> = _resumable.asStateFlow()

    private val connections = ConcurrentHashMap<String, RoomConnection>()
    @Volatile private var foreground = true

    private val _collapsed = MutableStateFlow<String?>(null)

    /** 「会話に戻る」の帯を小さくした部屋の一時 ID。帯は消さず、小さな形で入口を残す。プロセスの間だけ持つ。 */
    val collapsed: StateFlow<String?> = _collapsed.asStateFlow()

    fun collapse(id: String) {
        _collapsed.value = id
    }

    /** [id] の部屋の接続。まだ無ければ [create] で作る。同じ部屋には同じ接続を返すので、取得が 2 本にならない。 */
    @Suppress("UNCHECKED_CAST")
    fun <T : RoomConnection> connection(id: String, create: (ChatRoomRef) -> T): T? {
        val room = get(id) ?: return null
        return connections.computeIfAbsent(id) { create(room).also { it.setForeground(foreground) } } as T
    }

    /** すでにある接続だけを返す（帯に新着の数を出すときなど、接続を作りたくない場合）。 */
    fun existingConnection(id: String): RoomConnection? = connections[id]

    /** アプリが前面・背景に移った。すべての接続へ伝える。 */
    fun setForeground(value: Boolean) {
        foreground = value
        connections.values.forEach { it.setForeground(value) }
    }

    fun register(room: ChatRoomRef): String {
        // 入室できる部屋は 1 つ。一覧から別の部屋に入ったら、前の部屋は退室（作成者は閉じる）して接続を止める。
        connections.keys.toList().forEach { id -> connections.remove(id)?.leaveAndClose() }
        _collapsed.value = null
        val id = UUID.randomUUID().toString()
        rooms[id] = room
        val saved = ResumableRoom(id, room, now())
        _resumable.value = saved
        // 保存できなかったときは前の部屋の記録を消す。残すと再起動後に前の部屋へ戻ってしまう。
        runCatching { persistence?.save(saved) }.onFailure { runCatching { persistence?.clear() } }
        return id
    }

    /** プロセスの再起動後は、記録した部屋を同じ ID で引けるようにする（画面遷移の復元に使われる）。 */
    operator fun get(id: String): ChatRoomRef? =
        rooms[id] ?: _resumable.value?.takeIf { it.sessionId == id }?.room?.also { rooms[id] = it }

    /** 記録した部屋に戻る。戻る先の一時 ID を返す。 */
    fun resume(): String? = _resumable.value?.let { saved ->
        rooms[saved.sessionId] = saved.room
        saved.sessionId
    }

    /** 部屋が終わった。会話画面はそのままにして、戻る対象からだけ外す。 */
    fun ended(id: String) = forget(id)

    /** 退室した。 */
    fun remove(id: String) {
        rooms.remove(id)
        closeConnection(id)
        forget(id)
    }

    private fun closeConnection(id: String) {
        connections.remove(id)?.close()
    }

    private fun forget(id: String) {
        if (_resumable.value?.sessionId != id) return
        _resumable.value = null
        runCatching { persistence?.clear() }
    }

    private fun loadSaved(): ResumableRoom? {
        val saved = runCatching { persistence?.load() }.getOrNull() ?: return null
        if (now() - saved.savedAt !in 0..MAX_AGE_MS) {
            runCatching { persistence?.clear() }
            return null
        }
        return saved
    }

    companion object {
        /** これより古い記録の部屋は終わっているとみなす（無言・利用時間の制限で本家が閉じる）。 */
        const val MAX_AGE_MS = 12 * 60 * 60 * 1000L
    }
}
