package io.github.springthief1123.lovelyspace.ui.rooms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.springthief1123.lovelyspace.core.Gender
import io.github.springthief1123.lovelyspace.core.Genre
import io.github.springthief1123.lovelyspace.core.Genres
import io.github.springthief1123.lovelyspace.core.Room
import io.github.springthief1123.lovelyspace.core.RoomQuery
import io.github.springthief1123.lovelyspace.data.RoomListSource
import io.github.springthief1123.lovelyspace.settings.RoomListPreferenceStore
import io.github.springthief1123.lovelyspace.ui.describeError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RoomListUiState(
    val genre: Genre = Genres.default,
    /** 直前まで見ていたジャンル（新しい順、選択中は除く）。上部のすぐ切り替えられる候補に出す。 */
    val recentGenres: List<Genre> = emptyList(),
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
    val preferenceError: String? = null,
    /** [error] が次ページの読み込みで起きたものなら true（再試行は次ページを読み直す）。 */
    val errorOnLoadMore: Boolean = false,
) {
    val canLoadMore: Boolean get() = page in 1 until lastPage
}

/** 条件を変えたときに、前の条件の結果（部屋・件数・ページ・エラー）を消す。 */
private fun RoomListUiState.resetResults() = copy(
    rooms = emptyList(),
    waitingCount = null,
    fullCount = null,
    page = 0,
    lastPage = 1,
    error = null,
    errorOnLoadMore = false,
)

class RoomListViewModel(
    private val repository: RoomListSource,
    private val preferences: RoomListPreferenceStore,
) : ViewModel() {
    private val _state = MutableStateFlow(RoomListUiState())
    val state: StateFlow<RoomListUiState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var hasUserSelectedGenre = false

    init {
        viewModelScope.launch {
            val initialKey = try {
                preferences.roomListPreferences.first().initialGenreKey()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(preferenceError = describeError(e)) }
                Genres.default.key
            }
            if (!hasUserSelectedGenre) {
                _state.update { it.copy(genre = Genres[initialKey] ?: Genres.default) }
                refresh(force = false)
                rememberGenre(_state.value.genre.key)
            }
        }
    }

    fun selectGenre(genre: Genre) {
        val current = _state.value
        if (genre == current.genre) {
            if (current.page == 0 && !current.isRefreshing) {
                hasUserSelectedGenre = true
                rememberGenre(genre.key)
                refresh(force = false)
            }
            return
        }
        hasUserSelectedGenre = true
        _state.update {
            val recent = (listOf(it.genre) + it.recentGenres).filter { g -> g != genre }.distinct().take(RECENT_GENRES)
            it.copy(genre = genre, recentGenres = recent).resetResults()
        }
        rememberGenre(genre.key)
        refresh(force = false)
    }

    private fun rememberGenre(key: String) {
        viewModelScope.launch {
            try {
                preferences.setLastRoomGenre(key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(preferenceError = describeError(e)) }
            }
        }
    }

    fun selectSex(sex: Gender?) {
        if (sex == _state.value.sex) return
        _state.update { it.copy(sex = sex).resetResults() }
        refresh(force = false)
    }

    /** 1 ページ目を取り直す。[force] が false なら少し前の取得結果を使い回す。 */
    fun refresh(force: Boolean = true) {
        loadJob?.cancel()
        _state.update { it.copy(isRefreshing = true, isLoadingMore = false, error = null) }
        loadJob = viewModelScope.launch { load(page = 1, force = force) }
    }

    private var handledRefreshKey = 0

    /**
     * 画面から渡される取り直しの合図。画面に戻るたびに同じ値で呼ばれるので、
     * 値が変わったときだけ取り直す（チャットから戻ったときなど）。
     */
    fun onRefreshKey(key: Int) {
        if (key == handledRefreshKey) return
        handledRefreshKey = key
        refresh(force = true)
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
            val result = repository.fetch(RoomQuery(genre = s.genre, sex = s.sex, page = page), force = force)
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
            _state.update { it.copy(isRefreshing = false, isLoadingMore = false, error = describeError(e), errorOnLoadMore = page > 1) }
        }
    }

    private companion object {
        const val RECENT_GENRES = 3
    }
}
