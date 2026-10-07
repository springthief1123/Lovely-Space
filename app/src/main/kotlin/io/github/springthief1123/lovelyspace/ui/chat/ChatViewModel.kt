package io.github.springthief1123.lovelyspace.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.springthief1123.lovelyspace.core.ShaloveClient
import io.github.springthief1123.lovelyspace.core.chat.ChatLine
import io.github.springthief1123.lovelyspace.core.chat.ChatPage
import io.github.springthief1123.lovelyspace.core.chat.ChatRoomRef
import io.github.springthief1123.lovelyspace.core.chat.ChatSession
import io.github.springthief1123.lovelyspace.core.chat.ChatUpdate
import io.github.springthief1123.lovelyspace.core.chat.RoomPageUnavailableException
import io.github.springthief1123.lovelyspace.ui.describeError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 画面に出すログ 1 行。[id] は LazyColumn のキー（届いた順の連番）。 */
data class UiLine(val id: Long, val line: ChatLine, val isMine: Boolean)

enum class Connection { CONNECTED, RECONNECTING, FAILED }

data class ChatUiState(
    val isLoading: Boolean = true,
    val loadError: String? = null,
    /** 開いたページが部屋の画面ではなかった（部屋が終わっている可能性が高い）。再試行しても戻れない。 */
    val roomUnavailable: Boolean = false,
    val title: String = "",
    val myName: String? = null,
    /** 自分が部屋の作成者か。作成者の退室は部屋の閉鎖になる。 */
    val isOwner: Boolean = false,
    /** 2 人そろっているか。作成者は相手が来るまで待機する。 */
    val isFilled: Boolean = true,
    /** 新しい順。 */
    val lines: List<UiLine> = emptyList(),
    val information: String = "",
    val connection: Connection = Connection.CONNECTED,
    /** 部屋が終わった理由（閉鎖・無言での終了など）。null でなければ発言できない。 */
    val endMessage: String? = null,
    val input: String = "",
    val isSending: Boolean = false,
    val sendError: String? = null,
    /** 通信失敗時の送信文。送信中に書いた次の下書きとは別に保持する。 */
    val failedMessage: String? = null,
    val isLeaving: Boolean = false,
    /** 部屋を閉じられなかった理由。作成者は閉じるまで部屋に残る。 */
    val leaveError: String? = null,
    /** 退室が済んだ。画面はこれを見て一覧へ戻る。 */
    val left: Boolean = false,
) {
    /** 作成者として相手の入室を待っている。 */
    val isWaitingForPartner: Boolean get() = isOwner && !isFilled && endMessage == null

    /** 相手の名前。最後に発言した自分以外の人。 */
    val partnerName: String?
        get() = lines.firstOrNull { !it.isMine && !it.line.isNotice }?.line?.speaker

    val canSend: Boolean
        get() = !isLoading && loadError == null && endMessage == null && !isLeaving && input.isNotBlank() && !isSending && failedMessage == null

    fun sendingFailed(message: String, error: String) = copy(isSending = false, sendError = error, failedMessage = message)

    /** 自動再送はしない。両方の文章を入力欄で確認・編集できるように戻す。 */
    fun restoreFailedMessage() = failedMessage?.let { message ->
        copy(input = if (input.isEmpty()) message else "$message\n\n$input", failedMessage = null, sendError = null)
    } ?: this

    fun discardFailedMessage() = copy(failedMessage = null, sendError = null)
}

class ChatViewModel(
    private val client: ShaloveClient,
    private val room: ChatRoomRef,
) : ViewModel() {
    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var session: ChatSession? = null
    private var updatesJob: Job? = null
    private var nextId = 0L

    init {
        open()
    }

    fun open() {
        _state.update { it.copy(isLoading = true, loadError = null, roomUnavailable = false) }
        viewModelScope.launch {
            try {
                val page = client.openChat(room)
                onOpened(page)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, loadError = describeError(e), roomUnavailable = e is RoomPageUnavailableException) }
            }
        }
    }

    private fun onOpened(page: ChatPage) {
        session = client.chatSession(page)
        _state.update {
            it.copy(
                isLoading = false,
                title = page.title,
                myName = page.myName,
                isOwner = page.isOwner,
                isFilled = page.state.isFilledRoom,
                // ページのログは新しい順なので、古いほうから番号を振る。
                lines = page.lines.asReversed().map { line -> uiLine(line, page.myName) }.asReversed(),
            )
        }
        startUpdates()
    }

    /** 新着の取得を（再）開始する。通信が続けて失敗したら少しずつ間を空けて取り直し、上限を超えたら止める。 */
    fun startUpdates() {
        val session = session ?: return
        updatesJob?.cancel()
        _state.update { it.copy(connection = Connection.CONNECTED) }
        updatesJob = viewModelScope.launch {
            var failures = 0
            while (true) {
                try {
                    session.updates().collect { update ->
                        failures = 0
                        apply(update)
                    }
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures++
                    if (failures > MAX_RETRIES) {
                        _state.update { it.copy(connection = Connection.FAILED) }
                        return@launch
                    }
                    _state.update { it.copy(connection = Connection.RECONNECTING) }
                    delay(RETRY_DELAY_MS * failures)
                }
            }
        }
    }

    private fun apply(update: ChatUpdate) {
        _state.update { s ->
            val added = update.newLines.map { uiLine(it, s.myName) }.asReversed()
            s.copy(
                lines = if (update.clearLog) added else added + s.lines,
                information = update.information,
                endMessage = update.endMessage ?: s.endMessage,
                isFilled = update.state.isFilledRoom,
                connection = Connection.CONNECTED,
            )
        }
    }

    private fun uiLine(line: ChatLine, myName: String?) =
        UiLine(nextId++, line, isMine = myName != null && line.speaker == myName)

    fun setInput(text: String) = _state.update { it.copy(input = text) }
    fun restoreFailedMessage() = _state.update { it.restoreFailedMessage() }
    fun discardFailedMessage() = _state.update { it.discardFailedMessage() }

    fun send() {
        val session = session ?: return
        val s = _state.value
        if (!s.canSend) return
        val text = s.input.trim()
        _state.update { it.copy(input = "", isSending = true, sendError = null) }
        viewModelScope.launch {
            try {
                apply(session.send(text))
                _state.update { it.copy(isSending = false) }
                // 受信が止まっている（つなぎ直しを諦めた）なら、発言できた今のうちに受信を再開する。
                // apply() が表示を「接続中」に戻すので、実際の受信と表示を一致させる。
                if (updatesJob?.isActive != true && _state.value.endMessage == null) startUpdates()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.sendingFailed(text, describeError(e)) }
            }
        }
    }

    /**
     * 退室する（作成者は部屋を閉じる）。終了済みの部屋や、入室者の退室の通信エラーでも画面は閉じる。
     * 作成者が閉じられなかったときは、部屋が一覧に残ってしまうので画面に留まり、やり直せるようにする。
     */
    fun leave() {
        if (_state.value.isLeaving) return
        updatesJob?.cancel()
        _state.update { it.copy(isLeaving = true, leaveError = null) }
        viewModelScope.launch {
            val s = _state.value
            if (s.endMessage == null && session != null) {
                try {
                    if (s.isOwner) client.close(room) else client.leave(room)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (s.isOwner) {
                        _state.update { it.copy(isLeaving = false, leaveError = "部屋を閉じられませんでした。${describeError(e)}") }
                        startUpdates()
                        return@launch
                    }
                    // 退室の通知が届かなくても、部屋は無言の時間切れでサイト側が閉じる。
                }
            }
            _state.update { it.copy(isLeaving = false, left = true) }
        }
    }

    private companion object {
        const val MAX_RETRIES = 3
        const val RETRY_DELAY_MS = 10_000L
    }
}
