package io.github.springthief1123.lovelyspace.ui.create

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.springthief1123.lovelyspace.core.chat.ChatRoomRef
import io.github.springthief1123.lovelyspace.core.chat.EntryProfile
import io.github.springthief1123.lovelyspace.settings.RoomDetails
import io.github.springthief1123.lovelyspace.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CreateRoomUiState(
    val isLoaded: Boolean = false,
    val name: String = "",
    /** 1=男, 2=女 */
    val sex: Int = 1,
    /** 空欄は「秘密」。 */
    val years: String = "",
    /** 1〜47 都道府県, 48 海外, null は秘密。 */
    val prefecture: Int? = null,
    val message: String = "",
    /** 作成できた部屋。画面はこれを見てチャット画面へ移る。 */
    val created: ChatRoomRef? = null,
) {
    val yearsValue: Int? get() = years.toIntOrNull()
    val yearsValid: Boolean get() = years.isEmpty() || (yearsValue ?: 0) in MIN_YEARS..MAX_YEARS

    /** サイトの数え方（半角 1、全角 2）での待機メッセージの長さ。 */
    val messageWidth: Int get() = message.fold(0) { width, c -> width + if (c.isHalfWidth()) 1 else 2 }
    val messageValid: Boolean get() = messageWidth <= MESSAGE_MAX_WIDTH

    val canContinue: Boolean get() = isLoaded && name.isNotBlank() && yearsValid && messageValid

    companion object {
        const val MIN_YEARS = 18
        const val MAX_YEARS = 99
        const val MESSAGE_MAX_WIDTH = 500
    }
}

private fun Char.isHalfWidth(): Boolean = code < 0x80 || code in 0xFF61..0xFF9F

class CreateRoomViewModel(private val settings: SettingsRepository) : ViewModel() {
    private val _state = MutableStateFlow(CreateRoomUiState())
    val state: StateFlow<CreateRoomUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // 前回の値は入力の手間を省くためだけのもの。読めなくても空欄で始める。
            val profile = quietly { settings.lastEntryProfile.first() }
            val details = quietly { settings.lastRoomDetails.first() } ?: RoomDetails(null, "")
            _state.update {
                it.copy(
                    isLoaded = true,
                    name = profile?.name.orEmpty(),
                    sex = profile?.sex ?: 1,
                    years = profile?.years?.toString().orEmpty(),
                    prefecture = details.prefecture,
                    message = details.message,
                )
            }
        }
    }

    fun setName(v: String) = _state.update { it.copy(name = v) }
    fun setSex(v: Int) = _state.update { it.copy(sex = v) }
    fun setYears(v: String) = _state.update { it.copy(years = v.filter(Char::isDigit).take(2)) }
    fun setPrefecture(v: Int?) = _state.update { it.copy(prefecture = v) }

    /** サイトの待機メッセージは 1 行の入力欄なので、改行は空白にする。 */
    fun setMessage(v: String) = _state.update { it.copy(message = v.replace('\n', ' ')) }

    /** ブラウザ画面へ進む前に、入力した値を次回用に残す。 */
    fun saveInputs() {
        val s = _state.value
        viewModelScope.launch {
            quietly { settings.setLastEntryProfile(EntryProfile(s.name.trim(), s.sex, s.yearsValue)) }
            quietly { settings.setLastRoomDetails(RoomDetails(s.prefecture, s.message.trim())) }
        }
    }

    /** 設定の読み書きの失敗で画面を止めない。 */
    private suspend fun <T> quietly(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    fun onCreated(room: ChatRoomRef) = _state.update { it.copy(created = room) }
}
