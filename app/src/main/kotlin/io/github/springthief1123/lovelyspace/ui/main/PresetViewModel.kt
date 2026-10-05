package io.github.springthief1123.lovelyspace.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.springthief1123.lovelyspace.data.PresetRepository
import io.github.springthief1123.lovelyspace.ui.describeError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class PresetViewModel(val repository: PresetRepository) : ViewModel() {
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _working = MutableStateFlow(false)
    val working = _working.asStateFlow()
    private val refresh = MutableStateFlow(0)
    val profiles = refresh.flatMapLatest { repository.profiles.catch { if (it !is Exception) throw it; _error.value = describeError(it); emit(emptyList()) } }
    val messages = refresh.flatMapLatest { repository.messages.catch { if (it !is Exception) throw it; _error.value = describeError(it); emit(emptyList()) } }
    init { reload() }
    fun reload() = action { repository.initialize(); refresh.value += 1 }

    fun action(onSuccess: () -> Unit = {}, block: suspend () -> Unit) {
        if (_working.value) return
        _working.value = true
        _error.value = null
        viewModelScope.launch {
            try { block(); onSuccess() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _error.value = describeError(e) }
            finally { _working.value = false }
        }
    }
}
