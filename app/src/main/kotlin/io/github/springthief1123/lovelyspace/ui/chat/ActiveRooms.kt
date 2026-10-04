package io.github.springthief1123.lovelyspace.ui.chat

import io.github.springthief1123.lovelyspace.core.chat.ChatRoomRef
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 入室中の部屋をプロセス内だけで持つ。部屋の pwd は資格情報なので、画面遷移の route や保存状態には入れず、
 * ここで発行した一時 ID だけを渡す。プロセスが終了したら消える（その場合は一覧へ戻す）。
 */
class ActiveRooms {
    private val rooms = ConcurrentHashMap<String, ChatRoomRef>()

    fun register(room: ChatRoomRef): String = UUID.randomUUID().toString().also { rooms[it] = room }

    operator fun get(id: String): ChatRoomRef? = rooms[id]

    fun remove(id: String) {
        rooms.remove(id)
    }
}
