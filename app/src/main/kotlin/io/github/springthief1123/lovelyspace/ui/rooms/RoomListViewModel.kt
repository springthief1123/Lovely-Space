package io.github.springthief1123.lovelyspace.ui.rooms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.springthief1123.lovelyspace.core.Gender
import io.github.springthief1123.lovelyspace.core.Genre
import io.github.springthief1123.lovelyspace.core.Genres
import io.github.springthief1123.lovelyspace.core.HttpStatusException
import io.github.springthief1123.lovelyspace.core.Room
import io.github.springthief1123.lovelyspace.core.RoomQuery
import io.github.springthief1123.lovelyspace.core.ShaloveClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

data class RoomListUiState(
    val genre: Genre = Genres.default,
    val sex: Gender? = null,
    val rooms: List<Room> = emptyList(),
    val waitingCount: Int? = null,
    val fullCount: Int? = null,
    val genreCounts: Map<String, Int> = emptyMap(),
    val page: Int = 0,
    val lastPage: Int = 1,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: String? = null,
) {
    val canLoadMore: Boolean get() = page in 1 until lastPage
}

class RoomListViewModel(private val client: ShaloveClient) : ViewModel() {
    private val _state = MutableStateFlow(RoomListUiState())
    val state: StateFlow<RoomListUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        refresh(force = false)
    }

    fun selectGenre(genre: Genre) {
        if (genre == _state.value.genre) return
        _state.update { it.copy(genre = genre, rooms = emptyList(), page = 0, lastPage = 1, error = null) }
        refresh(force = false)
    }

    fun selectSex(sex: Gender?) {
        if (sex == _state.value.sex) return
        _state.update { it.copy(sex = sex, rooms = emptyList(), page = 0, lastPage = 1, error = null) }
        refresh(force = false)
    }

    /** 1 ページ目を取り直す。[force] が false なら少し前の取得結果を使い回す。 */
    fun refresh(force: Boolean = true) {
        loadJob?.cancel()
        _state.update { it.copy(isRefreshing = true, isLoadingMore = false, error = null) }
        loadJob = viewModelScope.launch { load(page = 1, force = force) }
    }

    fun loadMore() {
        val s = _state.value
        if (!s.canLoadMore || s.isLoadingMore || s.isRefreshing) return
        _state.update { it.copy(isLoadingMore = true, error = null) }
        loadJob = viewModelScope.launch { load(page = s.page + 1, force = false) }
    }

    private suspend fun load(page: Int, force: Boolean) {
        val s = _state.value
        try {
            val result = client.fetchRoomList(RoomQuery(genre = s.genre, sex = s.sex, page = page), forceRefresh = force)
            _state.update { cur ->
                // ページの境目で部屋が前後に動くことがあるため、ID で重複を除く。
                val rooms = if (page == 1) result.rooms else (cur.rooms + result.rooms).distinctBy { it.id }
                cur.copy(
                    rooms = rooms,
                    waitingCount = result.waitingCount,
                    fullCount = result.fullCount,
                    genreCounts = cur.genreCounts + result.genreCounts,
                    page = result.page,
                    lastPage = result.lastPage,
                    isRefreshing = false,
                    isLoadingMore = false,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(isRefreshing = false, isLoadingMore = false, error = describe(e)) }
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is HttpStatusException -> "ラブルームから応答エラーが返りました（${e.code}）"
        is IOException -> "通信できませんでした。電波の状態を確認してください"
        else -> "読み込みに失敗しました（${e.javaClass.simpleName}）"
    }
}
