package io.github.springthief1123.lovelyspace.ui.rooms

import io.github.springthief1123.lovelyspace.core.*
import io.github.springthief1123.lovelyspace.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class RoomPreferenceUndoTest {
    @Test fun onlySuccessfulHideOffersUndoAndProcessingBlocksCompetingActions() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val room = Room(1, Genres.default.key, RoomStatus.WAITING, RoomAction.ENTER, null, "合成", Gender.UNKNOWN, null, null, "本文")
            var gate = CompletableDeferred<Unit>()
            var failWrite = false
            val calls = mutableListOf<Boolean>()
            val store = object : RoomPreferenceStore {
                override val preferences = MutableStateFlow<List<RoomPreference>>(emptyList())
                override suspend fun setFavorite(room: Room, enabled: Boolean) { org.junit.Assert.fail("処理中の競合操作は実行しない") }
                override suspend fun setHidden(room: Room, enabled: Boolean) {
                    calls += enabled
                    gate.await()
                    if (failWrite) throw IOException("合成の保存失敗")
                }
                override suspend fun clearFavorite(host: String, roomId: Long) = Unit
                override suspend fun clearHidden(host: String, roomId: Long) = Unit
                override suspend fun restoreFavorite(value: RoomPreference) = Unit
                override suspend fun observe(rooms: List<Room>) = Unit
            }
            val vm = RoomPreferenceViewModel(store)
            val events = mutableListOf<Room>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.hiddenRooms.toList(events) }
            vm.hide(room); runCurrent()
            assertFalse(vm.state.value.canEdit(room))
            vm.toggleFavorite(room)
            assertTrue(events.isEmpty())
            gate.complete(Unit); runCurrent()
            assertEquals(listOf(room), events)
            assertTrue(vm.state.value.canEdit(room))
            vm.unhide(room); runCurrent()
            assertEquals(listOf(true, false), calls)
            failWrite = true; gate = CompletableDeferred(Unit)
            vm.hide(room); runCurrent()
            assertEquals(1, events.size)
            assertNotNull(vm.state.value.error)
        } finally { Dispatchers.resetMain() }
    }
}
