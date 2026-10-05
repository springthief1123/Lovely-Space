package io.github.springthief1123.lovelyspace.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.springthief1123.lovelyspace.data.SearchPreset
import io.github.springthief1123.lovelyspace.data.SearchPresetStore
import io.github.springthief1123.lovelyspace.ui.describeError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SavedSearchUiState(
    val presets: List<SearchPreset> = emptyList(),
    val loading: Boolean = true,
    val working: Boolean = false,
    val loadError: String? = null,
    val editError: String? = null,
)

class SavedSearchViewModel(private val repository: SearchPresetStore) : ViewModel() {
    private val _state = MutableStateFlow(SavedSearchUiState())
    val state = _state.asStateFlow()
    private var collection: Job? = null
    init { reload() }

    fun reload() {
        collection?.cancel()
        _state.update { it.copy(loading = true, loadError = null) }
        collection = viewModelScope.launch {
            try {
                repository.presets.collect { values -> _state.update { it.copy(presets = values, loading = false) } }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(loading = false, loadError = describeError(e)) } }
        }
    }

    fun clearEditError() = _state.update { it.copy(editError = null) }
    fun save(value: SearchPreset, onSuccess: () -> Unit) = action(onSuccess) { repository.save(value) }
    fun delete(id: String, onSuccess: () -> Unit) = action(onSuccess) { repository.delete(id) }

    private fun action(onSuccess: () -> Unit, block: suspend () -> Unit) {
        if (_state.value.working) return
        _state.update { it.copy(working = true, editError = null) }
        viewModelScope.launch {
            try { block(); onSuccess() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(editError = describeError(e)) } }
            finally { _state.update { it.copy(working = false) } }
        }
    }
}
