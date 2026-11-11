package io.github.springthief1123.lovelyspace.data

import io.github.springthief1123.lovelyspace.core.*
import org.junit.Assert.assertEquals
import org.junit.Test

/** 満室のピンを順番待ちで追うときの登録内容（[watchTarget]）。データは合成。 */
class PinWatchTargetTest {
    private val waiting = Room(900L, "kanto", RoomStatus.WAITING, RoomAction.ENTER, null, "合成", Gender.FEMALE, 30, null, "合成の募集文です、よろしく")

    private fun preference(room: Room) = RoomPreference(
        host = Genres[room.genreKey]!!.host, roomId = room.id, genreKey = room.genreKey, favorite = true, hidden = false,
        snapshotStatus = room.status.name, snapshotAction = room.action.name, snapshotElapsedSeconds = null,
        snapshotName = room.name, snapshotGender = room.gender.name, snapshotAge = room.age, snapshotArea = room.area,
        snapshotMessage = room.message, identityName = room.name, identityGender = room.gender.name, identityAge = room.age,
    )

    @Test
    fun `起動直後の満室のピンは、記録の様子が待機中でも満室として最後に見つけたページから追う`() {
        // ピンしたときは待機中。その後ピンの見張りだけが 12 ページ目で満室を確かめ、アプリを起動し直した。
        val record = PinRecord(roomIdentity(waiting), state = PinState.FULL, lastKnownPage = 12, lastPageCount = 18)
        val pin = PinView(preference(waiting), record, PinState.FULL, waiting, page = null, similar = null)

        val (room, page) = pin.watchTarget()

        assertEquals(RoomStatus.FULL, room.status)
        assertEquals(roomIdentity(waiting), roomIdentity(room))
        assertEquals(12, page)
    }

    @Test
    fun `一覧で見えている満室のピンは、そのページから追う`() {
        val full = waiting.copy(status = RoomStatus.FULL, action = RoomAction.PEEK)
        val record = PinRecord(roomIdentity(full), state = PinState.FULL, lastKnownPage = 12)
        val pin = PinView(preference(full), record, PinState.FULL, full, page = 3, similar = null, listed = true)

        assertEquals(full to 3, pin.watchTarget())
    }
}
