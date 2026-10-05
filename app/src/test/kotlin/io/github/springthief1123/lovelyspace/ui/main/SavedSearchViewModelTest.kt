package io.github.springthief1123.lovelyspace.ui.main

import androidx.compose.runtime.saveable.SaverScope
import io.github.springthief1123.lovelyspace.core.*
import io.github.springthief1123.lovelyspace.data.SearchPreset
import io.github.springthief1123.lovelyspace.data.SearchPresetStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SavedSearchViewModelTest {
    @Test fun failedWriteKeepsDialogOpenAndRetrySavesOnceWhileBusy() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val values = MutableStateFlow<List<SearchPreset>>(emptyList())
            var fail = true
            var calls = 0
            val gate = CompletableDeferred<Unit>()
            val vm = SavedSearchViewModel(object : SearchPresetStore {
                override val presets = values
                override suspend fun save(value: SearchPreset) {
                    calls++
                    gate.await()
                    if (fail) throw IOException("合成の保存失敗")
                    values.value = listOf(value)
                }
                override suspend fun delete(id: String) { values.value = emptyList() }
            })
            runCurrent()
            var closed = false
            val value = SearchPreset(label = "テスト", genreKey = "talk", criteria = RoomSearchCriteria())
            vm.save(value) { closed = true }
            vm.save(value) { closed = true }
            runCurrent()
            assertEquals(1, calls)
            assertTrue(vm.state.value.working)
            gate.complete(Unit); runCurrent()
            assertFalse(closed)
            assertFalse(vm.state.value.working)
            assertNotNull(vm.state.value.editError)
            assertTrue(vm.state.value.presets.isEmpty())
            fail = false
            vm.save(value) { closed = true }; runCurrent()
            assertTrue(closed)
            assertNull(vm.state.value.editError)
            assertEquals(listOf(value), vm.state.value.presets)
            vm.delete(value.id) {}; runCurrent()
            assertTrue(vm.state.value.presets.isEmpty())
        } finally { Dispatchers.resetMain() }
    }

    @Test fun reloadRecoversReadFailureAndRetainsPersistedSearches() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var fail = true
            val value = SearchPreset(label = "残る条件", genreKey = "kinki", criteria = RoomSearchCriteria())
            val vm = SavedSearchViewModel(object : SearchPresetStore {
                override val presets = flow {
                    if (fail) throw IOException("合成の読取失敗")
                    emit(listOf(value))
                }
                override suspend fun save(value: SearchPreset) = Unit
                override suspend fun delete(id: String) = Unit
            })
            runCurrent()
            assertNotNull(vm.state.value.loadError)
            assertFalse(vm.state.value.loading)
            fail = false
            vm.reload(); runCurrent()
            assertNull(vm.state.value.loadError)
            assertEquals(listOf(value), vm.state.value.presets)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun confirmationRestoresIdentityAndAllConditionsBeforeDatabaseLoads() {
        val scope = object : SaverScope { override fun canBeSaved(value: Any) = true }
        for (waiting in listOf(null, true, false)) for (publicOnly in listOf(null, true, false)) {
            val criteria = RoomSearchCriteria(name = "テスト", message = "合成 募集", excluded = "除外", keywordMode = KeywordMode.ANY,
                gender = Gender.FEMALE, minAge = 20, maxAge = 35, includeUnknownAge = false,
                area = Prefectures.names.first(), waitingOnly = waiting, publicOnly = publicOnly, sort = RoomSort.AGE)
            val value = SearchPreset("existing-id", "確認中の条件", "talk", criteria)
            val saved = with(SearchPresetSaver) { scope.save(value) }!!
            assertEquals(value, SearchPresetSaver.restore(saved))
        }
        val defaults = SearchPreset("blank", "未指定", "zenkoku", RoomSearchCriteria())
        assertEquals(defaults, SearchPresetSaver.restore(with(SearchPresetSaver) { scope.save(defaults) }!!))
        // listSaverは空のリストを「保存値なし」のnullとして扱う。
        assertNull(with(SearchPresetSaver) { scope.save(null) })
        assertNull(SearchPresetSaver.restore(emptyList<Any>()))
    }
}
