package io.github.springthief1123.lovelyspace

import org.junit.Assert.assertEquals
import org.junit.Test

class MainTabDirectionTest {
    @Test fun `右のタブへは左へ、左のタブへは右へ流れる向きになる`() {
        assertEquals(1, mainTabDirection(Routes.ROOMS, Routes.RADAR))
        assertEquals(1, mainTabDirection(Routes.RADAR, Routes.PROFILE))
        assertEquals(1, mainTabDirection(Routes.SEARCH, Routes.PROFILE))
        assertEquals(-1, mainTabDirection(Routes.PROFILE, Routes.RADAR))
        assertEquals(-1, mainTabDirection(Routes.RADAR, Routes.ROOMS))
        assertEquals(-1, mainTabDirection(Routes.PROFILE, Routes.ROOMS))
    }

    @Test fun `タブ同士の移動でなければ向きを決めない`() {
        assertEquals(0, mainTabDirection(Routes.ROOMS, Routes.SEARCH))
        assertEquals(0, mainTabDirection(Routes.ROOMS, Routes.SETTINGS))
        assertEquals(0, mainTabDirection(Routes.SETTINGS, Routes.PROFILE))
        assertEquals(0, mainTabDirection(null, Routes.ROOMS))
    }
}
