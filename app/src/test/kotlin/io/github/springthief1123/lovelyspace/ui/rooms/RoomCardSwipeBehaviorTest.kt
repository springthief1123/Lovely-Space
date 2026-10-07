package io.github.springthief1123.lovelyspace.ui.rooms

import org.junit.Assert.assertEquals
import org.junit.Test

class RoomCardSwipeBehaviorTest {
    @Test
    fun shortSwipeRevealsMenuWithoutCommitting() {
        assertEquals(
            RoomCardSwipeRelease.REVEAL_FAVORITE,
            release(offset = 52f, velocity = 0f),
        )
        assertEquals(
            RoomCardSwipeRelease.REVEAL_HIDDEN,
            release(offset = -52f, velocity = 0f),
        )
    }

    @Test
    fun flickRightAfterTouchDoesNotThrowCardAheadOfFinger() {
        assertEquals(
            RoomCardSwipeRelease.CLOSED,
            release(offset = 12f, velocity = 900f),
        )
        assertEquals(
            RoomCardSwipeRelease.CLOSED,
            release(offset = -12f, velocity = -900f),
        )
    }

    @Test
    fun flickAfterMovingCardRevealsMenu() {
        assertEquals(
            RoomCardSwipeRelease.REVEAL_FAVORITE,
            release(offset = 30f, velocity = 900f),
        )
        assertEquals(
            RoomCardSwipeRelease.REVEAL_HIDDEN,
            release(offset = -30f, velocity = -900f),
        )
    }

    @Test
    fun fullSwipeCommitsOnlyWhenReleasedPastCommitThreshold() {
        assertEquals(
            RoomCardSwipeRelease.COMMIT_FAVORITE,
            release(offset = 285f, velocity = 0f),
        )
        assertEquals(
            RoomCardSwipeRelease.COMMIT_HIDDEN,
            release(offset = -285f, velocity = 0f),
        )
    }

    @Test
    fun returningTowardOriginBeforeReleaseDoesNotCommit() {
        assertEquals(
            RoomCardSwipeRelease.CLOSED,
            release(offset = 18f, velocity = 0f),
        )
        assertEquals(
            RoomCardSwipeRelease.CLOSED,
            release(offset = -18f, velocity = 0f),
        )
    }

    @Test
    fun oppositeFlickClosesAlreadyRevealedMenu() {
        assertEquals(
            RoomCardSwipeRelease.CLOSED,
            release(offset = 90f, velocity = -900f),
        )
        assertEquals(
            RoomCardSwipeRelease.CLOSED,
            release(offset = -90f, velocity = 900f),
        )
    }

    @Test
    fun openCardClosesWithSmallerReturnThanItTakesToOpen() {
        assertEquals(40.6f, roomCardRevealThreshold(116f, startedOpen = false), 0.01f)
        assertEquals(81.2f, roomCardRevealThreshold(116f, startedOpen = true), 0.01f)
    }

    private fun release(offset: Float, velocity: Float) = resolveRoomCardSwipeRelease(
        offsetPx = offset,
        velocityPx = velocity,
        revealThresholdPx = 36f,
        commitThresholdPx = 280f,
        velocityThresholdPx = 720f,
        favoriteEnabled = true,
        hiddenEnabled = true,
        flickMinOffsetPx = 24f,
    )
}
