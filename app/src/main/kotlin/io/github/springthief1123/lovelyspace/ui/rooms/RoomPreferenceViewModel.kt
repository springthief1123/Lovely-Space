package io.github.springthief1123.lovelyspace.ui.rooms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.springthief1123.lovelyspace.core.Genres
import io.github.springthief1123.lovelyspace.core.Room
import io.github.springthief1123.lovelyspace.data.RoomPreference
import io.github.springthief1123.lovelyspace.data.RoomPreferenceStore
import io.github.springthief1123.lovelyspace.data.appliesTo
import io.github.springthief1123.lovelyspace.ui.describeError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RoomPreferenceUiState(
    val preferences: List<RoomPreference> = emptyList(),
    val loading: Boolean = true,
    val workingKeys: Set<String> = emptySet(),
    val error: String? = null,
) {
    val favorites: List<RoomPreference> get() = preferences.filter { it.favorite }
    val hidden: List<RoomPreference> get() = preferences.filter { it.hidden }

    fun activePreference(room: Room): RoomPreference? =
        preferences.firstOrNull { it.appliesTo(room) }

    fun isFavorite(room: Room): Boolean = activePreference(room)?.favorite == true
    fun isHidden(room: Room): Boolean = activePreference(room)?.hidden == true

    companion object {
        internal fun key(host: String, roomId: Long) = "$host/$roomId"
    }
}

class RoomPreferenceViewModel(private val store: RoomPreferenceStore) : ViewModel() {
    private val _state = MutableStateFlow(RoomPreferenceUiState())
    val state = _state.asStateFlow()
    private var collection: Job? = null

    init { reload() }

    fun reload() {
        collection?.cancel()
        _state.update { it.copy(loading = true, error = null) }
        collection = viewModelScope.launch {
            try {
                store.preferences.collect { values ->
                    _state.update { it.copy(preferences = values, loading = false) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = describeError(e)) }
            }
        }
    }

    fun observe(rooms: List<Room>) {
        if (rooms.isEmpty()) return
        viewModelScope.launch {
            try {
                store.observe(rooms)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = describeError(e)) }
            }
        }
    }

    fun toggleFavorite(room: Room) {
        val enabled = !_state.value.isFavorite(room)
        action(roomKey(room)) { store.setFavorite(room, enabled) }
    }

    fun hide(room: Room) = action(roomKey(room)) { store.setHidden(room, true) }

    fun clearFavorite(value: RoomPreference) =
        action(RoomPreferenceUiState.key(value.host, value.roomId)) {
            store.clearFavorite(value.host, value.roomId)
        }

    fun clearHidden(value: RoomPreference) =
        action(RoomPreferenceUiState.key(value.host, value.roomId)) {
            store.clearHidden(value.host, value.roomId)
        }

    fun clearError() = _state.update { it.copy(error = null) }

    private fun action(key: String, block: suspend () -> Unit) {
        if (key in _state.value.workingKeys) return
        _state.update { it.copy(workingKeys = it.workingKeys + key, error = null) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = describeError(e)) }
            } finally {
                _state.update { it.copy(workingKeys = it.workingKeys - key) }
            }
        }
    }

    private fun roomKey(room: Room): String {
        val host = Genres[room.genreKey]?.host ?: room.genreKey
        return RoomPreferenceUiState.key(host, room.id)
    }
}
