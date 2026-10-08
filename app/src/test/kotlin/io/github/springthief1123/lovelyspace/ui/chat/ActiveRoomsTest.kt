package io.github.springthief1123.lovelyspace.ui.chat

import io.github.springthief1123.lovelyspace.core.chat.ChatRoomRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ActiveRoomsTest {
    // 合成データ。本家の実データは使わない。
    private val room = ChatRoomRef("2shot.chat.shalove.net", 900000001L, "0123456789abcdef", "zenkoku")

    private class MemoryPersistence(var value: ResumableRoom? = null) : ActiveRoomPersistence {
        override fun load() = value
        override fun save(value: ResumableRoom) { this.value = value }
        override fun clear() { value = null }
    }

    @Test fun roomSurvivesProcessRestartUnderSameSessionId() {
        val disk = MemoryPersistence()
        val id = ActiveRooms(disk, now = { 1_000L }).register(room)

        val restarted = ActiveRooms(disk, now = { 2_000L })
        assertEquals(id, restarted.resumable.value?.sessionId)
        // 画面遷移の復元は同じ一時 ID で部屋を引く。
        assertEquals(room, restarted[id])
        assertEquals(id, restarted.resume())
    }

    @Test fun leavingForgetsTheRoom() {
        val disk = MemoryPersistence()
        val rooms = ActiveRooms(disk)
        val id = rooms.register(room)
        rooms.remove(id)
        assertNull(rooms.resumable.value)
        assertNull(disk.value)
        assertNull(rooms[id])
    }

    @Test fun endedRoomStaysOpenOnScreenButIsNotResumable() {
        val disk = MemoryPersistence()
        val rooms = ActiveRooms(disk)
        val id = rooms.register(room)
        rooms.ended(id)
        assertNotNull(rooms[id])
        assertNull(rooms.resumable.value)
        assertNull(disk.value)
    }

    @Test fun oldRecordIsDiscarded() {
        val disk = MemoryPersistence(ResumableRoom("old", room, savedAt = 0L))
        val rooms = ActiveRooms(disk, now = { ActiveRooms.MAX_AGE_MS + 1 })
        assertNull(rooms.resumable.value)
        assertNull(disk.value)
        assertNull(rooms["old"])
    }

    @Test fun newRoomReplacesThePreviousRecord() {
        val disk = MemoryPersistence()
        val rooms = ActiveRooms(disk)
        val first = rooms.register(room)
        val second = rooms.register(room.copy(roomId = 900000002L))
        assertEquals(second, disk.value?.sessionId)
        // 前の部屋の退室は、新しい部屋の記録を消さない。
        rooms.remove(first)
        assertEquals(second, rooms.resumable.value?.sessionId)
    }

    @Test fun failedSaveDoesNotLeaveThePreviousRoomResumable() {
        val disk = object : ActiveRoomPersistence {
            var value: ResumableRoom? = null
            var failSave = false
            override fun load() = value
            override fun save(value: ResumableRoom) { if (failSave) throw IllegalStateException("keystore"); this.value = value }
            override fun clear() { value = null }
        }
        ActiveRooms(disk).register(room)
        disk.failSave = true
        ActiveRooms(disk).register(room.copy(roomId = 900000002L))
        // 再起動後に前の部屋が「会話に戻る」に出ない。
        assertNull(ActiveRooms(disk).resumable.value)
    }

    @Test fun collapsingLastsForTheProcessOnlyAndANewRoomShowsTheFullBar() {
        val disk = MemoryPersistence()
        val rooms = ActiveRooms(disk)
        val id = rooms.register(room)
        rooms.collapse(id)
        assertEquals(id, rooms.collapsed.value)
        // 再起動後（新しいインスタンス）は記録が残っていても小さくした状態は戻らない。
        val restarted = ActiveRooms(disk)
        assertEquals(id, restarted.resumable.value?.sessionId)
        assertNull(restarted.collapsed.value)
        // 別の部屋に入ったら、帯は元の大きさで出す。
        rooms.register(room.copy(roomId = 900000002L))
        assertNull(rooms.collapsed.value)
    }

    private class FakeConnection : RoomConnection {
        var running = true
        var closed = false
        override fun setForeground(value: Boolean) { running = value }
        override fun close() { closed = true }
    }

    @Test fun oneConnectionPerRoomSurvivesLeavingTheChatScreen() {
        val rooms = ActiveRooms(MemoryPersistence())
        val id = rooms.register(room)
        var created = 0
        val first = rooms.connection(id) { created++; FakeConnection() }!!
        // 一覧から会話に戻っても、同じ接続を使う（取得が 2 本にならない）。
        val again = rooms.connection(id) { created++; FakeConnection() }!!
        assertSame(first, again)
        assertEquals(1, created)
        assertSame(first, rooms.existingConnection(id))
        // 背景に回ったら止め、前面に戻ったら再開を伝える。
        rooms.setForeground(false)
        assertFalse(first.running)
        rooms.setForeground(true)
        assertTrue(first.running)
        // 退室で接続を止めて外す。
        rooms.remove(id)
        assertTrue(first.closed)
        assertNull(rooms.existingConnection(id))
        assertNull(rooms.connection(id) { created++; FakeConnection() })
        assertEquals(1, created)
    }

    @Test fun enteringAnotherRoomStopsThePreviousConnection() {
        val rooms = ActiveRooms(MemoryPersistence())
        val first = rooms.connection(rooms.register(room)) { FakeConnection() }!!
        val nextId = rooms.register(room.copy(roomId = 900000002L))
        assertTrue(first.closed)
        // 背景にいる間に作った接続は、止めた状態から始める。
        rooms.setForeground(false)
        val next = rooms.connection(nextId) { FakeConnection() }!!
        assertFalse(next.running)
        assertFalse(next.closed)
    }

    @Test fun storageFailureDoesNotBreakTheChat() {
        val broken = object : ActiveRoomPersistence {
            override fun load(): ResumableRoom? = throw IllegalStateException("keystore")
            override fun save(value: ResumableRoom): Unit = throw IllegalStateException("keystore")
            override fun clear(): Unit = throw IllegalStateException("keystore")
        }
        val rooms = ActiveRooms(broken)
        val id = rooms.register(room)
        assertEquals(room, rooms[id])
        rooms.remove(id)
        assertNull(rooms[id])
    }
}
