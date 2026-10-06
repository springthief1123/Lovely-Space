package io.github.springthief1123.lovelyspace.ui.rooms

import io.github.springthief1123.lovelyspace.core.Genres
import io.github.springthief1123.lovelyspace.core.RoomListPage
import io.github.springthief1123.lovelyspace.core.RoomQuery
import io.github.springthief1123.lovelyspace.data.RoomListSource
import io.github.springthief1123.lovelyspace.settings.RoomListPreferenceStore
import io.github.springthief1123.lovelyspace.settings.RoomListPreferences
import io.github.springthief1123.lovelyspace.settings.RoomListStartMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RoomListViewModelTest {
    @Test
    fun startsFromConfiguredGenreAndRemembersLaterSelection() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val store = FakeRoomListPreferences(
                RoomListPreferences(
                    startMode = RoomListStartMode.DEFAULT,
                    defaultGenreKey = "kinki",
                    lastGenreKey = "talk",
                ),
            )
            val fetched = mutableListOf<RoomQuery>()
            val source = RoomListSource { query, _ ->
                fetched += query
                RoomListPage(
                    genreKey = query.genre.key,
                    rooms = emptyList(),
                    waitingCount = 0,
                    fullCount = 0,
                    page = 1,
                    lastPage = 1,
                    genreCounts = emptyMap(),
                    totalRooms = 0,
                )
            }

            val vm = RoomListViewModel(source, store)
            advanceUntilIdle()

            assertEquals("kinki", vm.state.value.genre.key)
            assertEquals(listOf("kinki"), fetched.map { it.genre.key })
            assertEquals("kinki", store.lastWritten)

            vm.selectGenre(Genres["talk"]!!)
            advanceUntilIdle()

            assertEquals("talk", vm.state.value.genre.key)
            assertEquals("talk", store.lastWritten)
            assertEquals(listOf("kinki", "talk"), fetched.map { it.genre.key })
            assertFalse(vm.state.value.isRefreshing)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun lastUsedModeFallsBackToKnownGenre() {
        val valid = RoomListPreferences(
            startMode = RoomListStartMode.LAST_USED,
            lastGenreKey = "game",
        )
        val invalid = RoomListPreferences(
            startMode = RoomListStartMode.LAST_USED,
            lastGenreKey = "missing",
        )
        assertEquals("game", valid.initialGenreKey())
        assertEquals(Genres.default.key, invalid.initialGenreKey())
    }
}

private class FakeRoomListPreferences(initial: RoomListPreferences) : RoomListPreferenceStore {
    private val state = MutableStateFlow(initial)
    override val roomListPreferences: Flow<RoomListPreferences> = state
    var lastWritten: String? = null
        private set

    override suspend fun setLastRoomGenre(genreKey: String) {
        lastWritten = genreKey
        state.value = state.value.copy(lastGenreKey = genreKey)
    }
}
