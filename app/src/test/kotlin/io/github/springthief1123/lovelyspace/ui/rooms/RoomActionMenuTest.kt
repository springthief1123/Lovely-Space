package io.github.springthief1123.lovelyspace.ui.rooms

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

class RoomActionMenuTest {
    private val window = IntSize(1080, 2400)
    private val menu = IntSize(600, 700)

    private fun at(x: Int, y: Int) = roomMenuPosition(IntOffset(x, y), menu, window, margin = 30, gap = 20)

    @Test fun opensBelowTheFingerCenteredOnIt() {
        assertEquals(IntOffset(240, 520), at(540, 500))
    }

    @Test fun opensAboveTheFingerNearTheBottom() {
        assertEquals(IntOffset(240, 2200 - 20 - 700), at(540, 2200))
    }

    @Test fun staysInsideTheScreenAtTheEdges() {
        assertEquals(30, at(10, 500).x)
        assertEquals(1080 - 30 - 600, at(1070, 500).x)
    }

    @Test fun pinsToTheBottomWhenNeitherSideFits() {
        val tall = roomMenuPosition(IntOffset(540, 900), IntSize(600, 1500), window, margin = 30, gap = 20)
        assertEquals(2400 - 30 - 1500, tall.y)
    }
}
