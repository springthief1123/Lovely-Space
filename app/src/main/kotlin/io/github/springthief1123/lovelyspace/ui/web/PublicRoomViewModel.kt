package io.github.springthief1123.lovelyspace.ui.web

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.springthief1123.lovelyspace.core.RoomPageSchedule
import io.github.springthief1123.lovelyspace.core.chat.PublicRoomPage
import io.github.springthief1123.lovelyspace.core.chat.PublicRoomUnavailableException
import io.github.springthief1123.lovelyspace.ui.chat.UiLine
import io.github.springthief1123.lovelyspace.ui.describeError
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class PublicRoomUiState(
    val opened: Boolean = false, val loading: Boolean = false, val automatic: Boolean = true,
    val lines: List<UiLine> = emptyList(), val information: String = "", val error: String? = null,
)

class PublicRoomViewModel(private val fetch: suspend () -> PublicRoomPage) : ViewModel() {
    private val _state = MutableStateFlow(PublicRoomUiState())
    val state = _state.asStateFlow()
    private val mutex = Mutex()
    private var nextId = 0L
    private var refreshJob: Job? = null
    fun automatic(value: Boolean) = _state.update { it.copy(automatic = value) }
    fun refresh() { if (!_state.value.loading && refreshJob?.isActive != true) refreshJob = viewModelScope.launch { read() } }
    fun stopRefresh() { refreshJob?.cancel() }
    suspend fun monitor() {
        while (currentCoroutineContext().isActive && _state.value.automatic) {
            read()
            delay(RoomPageSchedule.HEAD_INTERVAL_MS)
        }
    }
    private suspend fun read() = mutex.withLock {
        _state.update { it.copy(loading = true) }
        try {
            val page = fetch()
            currentCoroutineContext().ensureActive()
            // 同文・同時刻の発言も行数分保持し、更新後も既存のスクロール位置を維持する。
            val existing = _state.value.lines.groupBy { it.line }.mapValues { it.value.asReversed().toMutableList() }
            val lines = page.lines.asReversed().map { line ->
                existing[line]?.removeFirstOrNull() ?: UiLine(nextId++, line, false)
            }.asReversed()
            _state.update { it.copy(opened = true, lines = lines, information = page.information, error = null) }
        } catch (e: CancellationException) { throw e }
        catch (e: PublicRoomUnavailableException) {
            _state.update { it.copy(lines = emptyList(), opened = false, automatic = false, error = e.message) }
        } catch (e: Exception) { _state.update { it.copy(error = describeError(e)) } }
        finally { _state.update { it.copy(loading = false) } }
    }
}
