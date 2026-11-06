package io.github.springthief1123.lovelyspace.ui.shell

import io.github.springthief1123.lovelyspace.Routes
import io.github.springthief1123.lovelyspace.core.*
import io.github.springthief1123.lovelyspace.data.PinRecord
import io.github.springthief1123.lovelyspace.data.PinView
import io.github.springthief1123.lovelyspace.data.RoomPreference
import io.github.springthief1123.lovelyspace.data.newlyPinnedKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinTabIconTest {
    @Test fun newPinsAreOnlyThoseAddedAfterTheFirstLoad() {
        // 起動して最初に読んだピンは新しくピンしたものではない。
        assertEquals(emptySet<String>(), newlyPinnedKeys(null, setOf("a", "b")))
        assertEquals(setOf("c"), newlyPinnedKeys(setOf("a", "b"), setOf("a", "b", "c")))
        // 立て直し・似た部屋への引き継ぎは 1 件外れて 1 件増えるだけなので数えない。
        assertEquals(emptySet<String>(), newlyPinnedKeys(setOf("a", "b"), setOf("a", "c")))
        assertEquals(emptySet<String>(), newlyPinnedKeys(setOf("a", "b"), setOf("a")))
    }

    @Test fun pinStabsOnlyOnTheFindingPage() {
        assertTrue(shouldStabPinIcon(Routes.ROOMS))
        assertTrue(shouldStabPinIcon(Routes.SEARCH))
        assertFalse(shouldStabPinIcon(Routes.RADAR))
        assertFalse(shouldStabPinIcon(Routes.PROFILE))
        assertFalse(shouldStabPinIcon(null))
    }

    @Test fun badgeCountsPinsWhoseRoomIsOpenNowIncludingRebuilds() {
        val genre = Genres["zenkoku"]!!
        // 合成データ。
        fun room(id: Long) = Room(id, genre.key, RoomStatus.WAITING, RoomAction.ENTER, null, "合成$id", Gender.FEMALE, 25, null, "合成の募集文$id")
        fun pin(id: Long, state: PinState, similar: SimilarRoom? = null): PinView {
            val r = room(id)
            val pref = RoomPreference(
                host = genre.host, roomId = id, genreKey = genre.key, favorite = true, hidden = false,
                snapshotStatus = r.status.name, snapshotAction = r.action.name, snapshotElapsedSeconds = null,
                snapshotName = r.name, snapshotGender = r.gender.name, snapshotAge = r.age,
                snapshotArea = null, snapshotMessage = r.message, identityName = r.name,
                identityGender = r.gender.name, identityAge = r.age,
            )
            return PinView(pref, PinRecord("k$id"), state, r, 1, similar)
        }
        val pins = listOf(
            pin(1, PinState.OPEN), pin(2, PinState.FULL), pin(3, PinState.UNSEEN),
            pin(4, PinState.CLOSED), pin(5, PinState.CLOSED, SimilarRoom(room(6), 0.9)),
        )
        assertEquals(3, livePinCount(pins))
        assertEquals(0, livePinCount(emptyList()))
        assertEquals("3", pinBadgeText(3))
        assertEquals("99+", pinBadgeText(120))
    }
}
