package io.github.springthief1123.lovelyspace.ui.entry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.springthief1123.lovelyspace.core.ShaloveClient
import io.github.springthief1123.lovelyspace.core.chat.ChatRoomRef
import io.github.springthief1123.lovelyspace.core.chat.EntryForm
import io.github.springthief1123.lovelyspace.core.chat.EntryProfile
import io.github.springthief1123.lovelyspace.core.chat.EntryResult
import io.github.springthief1123.lovelyspace.settings.SettingsRepository
import io.github.springthief1123.lovelyspace.ui.describeError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EntryUiState(
    val isLoading: Boolean = true,
    val form: EntryForm? = null,
    /** 入室前画面が開けなかった（満室になった・閉じられた・通信エラー）。 */
    val loadError: String? = null,
    val name: String = "",
    /** 1=男, 2=女 */
    val sex: Int = 1,
    /** 空欄は「秘密」。 */
    val years: String = "",
    val isEntering: Boolean = false,
    /** 入室ボタンを押した結果のエラー。 */
    val entryError: String? = null,
    /** 入室できた部屋。画面はこれを見てチャット画面へ移る。 */
    val entered: ChatRoomRef? = null,
) {
    val yearsValue: Int? get() = years.toIntOrNull()
    val yearsValid: Boolean get() = years.isEmpty() || (yearsValue ?: 0) in MIN_YEARS..MAX_YEARS
    /** 入室前画面の取り直し中は、使用済みのトークンで送らないよう押せなくする。 */
    val canEnter: Boolean
        get() = form != null && !isLoading && !form.requiresCaptcha && name.isNotBlank() && yearsValid && !isEntering && entered == null

    companion object {
        const val MIN_YEARS = 18
        const val MAX_YEARS = 99
    }
}

class EntryViewModel(
    private val client: ShaloveClient,
    private val settings: SettingsRepository,
    private val host: String,
    private val genreKey: String,
    private val roomId: Long,
) : ViewModel() {
    private val _state = MutableStateFlow(EntryUiState())
    val state: StateFlow<EntryUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(isLoading = true, loadError = null) }
        viewModelScope.launch {
            // 前回の値は入力の手間を省くためだけのもの。読めなくても入室前画面は開く。
            val last = try {
                settings.lastEntryProfile.first()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            try {
                val form = client.openEntry(host, roomId, genreKey)
                _state.update { s ->
                    s.copy(
                        isLoading = false,
                        form = form,
                        loadError = if (form == null) "この部屋には入室できません。満室になったか、閉じられた可能性があります" else null,
                        // 入力済みの値は残す。未入力なら前回の値、無ければサイトの既定値。
                        name = s.name.ifEmpty { last?.name ?: form?.defaultName?.trim().orEmpty() },
                        sex = if (s.name.isEmpty()) last?.sex ?: s.sex else s.sex,
                        years = s.years.ifEmpty { last?.years?.toString().orEmpty() },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, loadError = describeError(e)) }
            }
        }
    }

    fun setName(v: String) = _state.update { it.copy(name = v, entryError = null) }
    fun setSex(v: Int) = _state.update { it.copy(sex = v) }
    fun setYears(v: String) = _state.update { it.copy(years = v.filter(Char::isDigit).take(2), entryError = null) }

    fun enter() {
        val s = _state.value
        val form = s.form ?: return
        if (!s.canEnter) return
        val profile = EntryProfile(name = s.name.trim(), sex = s.sex, years = s.yearsValue)
        _state.update { it.copy(isEntering = true, entryError = null) }
        viewModelScope.launch {
            saveProfileQuietly(profile)
            try {
                when (val result = client.enter(form, profile)) {
                    is EntryResult.Entered -> _state.update { it.copy(isEntering = false, entered = result.room) }
                    is EntryResult.Rejected -> {
                        _state.update { it.copy(isEntering = false, entryError = result.message) }
                        // トークンは 1 回きりなので、入室前画面を取り直して再挑戦できるようにする。
                        load()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(isEntering = false, entryError = describeError(e)) }
            }
        }
    }

    /** ブラウザ画面（ロボット確認）へ進む前に、入力した値を次回用に残す。 */
    fun saveProfile() {
        val s = _state.value
        viewModelScope.launch { saveProfileQuietly(EntryProfile(s.name.trim(), s.sex, s.yearsValue)) }
    }

    /** 次回用の保存。失敗しても入室は止めない。 */
    private suspend fun saveProfileQuietly(profile: EntryProfile) {
        try {
            settings.setLastEntryProfile(profile)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /** ブラウザ画面（ロボット確認）で入室できたとき。 */
    fun onEnteredInBrowser(room: ChatRoomRef) = _state.update { it.copy(entered = room) }
}
