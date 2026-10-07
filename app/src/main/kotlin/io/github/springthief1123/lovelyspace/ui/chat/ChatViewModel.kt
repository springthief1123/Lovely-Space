package io.github.springthief1123.lovelyspace.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.springthief1123.lovelyspace.core.ShaloveClient
import io.github.springthief1123.lovelyspace.core.messageWidth
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

/** 作成者だけが使える部屋の操作（本家の待機・チャット画面のボタン）。 */
enum class OwnerAction(val failure: String) {
    BAN_GUEST("相手を退室させられませんでした"),
    CLEAR_LOG("発言をクリアできませんでした"),
    CHANGE_MESSAGE("待機メッセージを変更できませんでした"),
    MAKE_PRIVATE("非公開にできませんでした"),
    MAKE_PUBLIC("公開にできませんでした"),
}

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
    /** 公開ルームか（会話を他の人が閲覧できる）。 */
    val isPublic: Boolean = false,
    /** 作成者が相手を退室させられるか。本家の「相手を退室」ボタンと同じ条件で切り替える。 */
    val canBanGuest: Boolean = false,
    /** 作成者に公開・非公開の切り替えがあるか。 */
    val canChangePublic: Boolean = false,
    val waitingMessage: String? = null,
    /** 実行中の作成者の操作。 */
    val ownerAction: OwnerAction? = null,
    /** 作成者の操作の結果（失敗の理由など）。閉じるまで表示する。 */
    val ownerNotice: String? = null,
) {
    /** 作成者の操作を出すか。部屋が終わったら出さない。 */
    val showsOwnerActions: Boolean
        get() = isOwner && !isLoading && loadError == null && endMessage == null && !isLeaving

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

    /**
     * 作成者の操作が済んだ後の状態。[page] は応答の部屋の画面（読めなければ null）。
     * [lastLineIdAtStart] は操作を始めた時点の最新行の id。
     */
    fun afterOwnerAction(action: OwnerAction, page: ChatPage?, lastLineIdAtStart: Long? = null): ChatUiState {
        val done = copy(ownerAction = null)
        // 転送・認証切れ・エラーページなど、部屋画面として確認できない応答を成功扱いしない。
        // 特にクリアや公開設定を楽観的に反映すると、サーバーの状態と表示が食い違う。
        if (page == null) return done.copy(ownerNotice = action.failure)
        return when (action) {
            OwnerAction.BAN_GUEST -> done.copy(canBanGuest = page.canBanGuest)
            // 自分の画面からも消す。操作中に新着の取得で届いた行は、クリアの後の発言なので残す。
            OwnerAction.CLEAR_LOG -> done.copy(lines = lines.filter { lastLineIdAtStart == null || it.id > lastLineIdAtStart })
            OwnerAction.CHANGE_MESSAGE -> done.copy(waitingMessage = page.waitingMessage ?: waitingMessage, ownerNotice = "待機メッセージを変更しました")
            OwnerAction.MAKE_PRIVATE, OwnerAction.MAKE_PUBLIC -> {
                val wanted = action == OwnerAction.MAKE_PUBLIC
                val actual = page.isPublic
                done.copy(
                    isPublic = actual,
                    ownerNotice = when {
                        actual != wanted && !wanted -> "非公開にできませんでした。非公開にするには、参加者全員の年齢確認が必要です"
                        actual != wanted -> action.failure
                        else -> null
                    },
                )
            }
        }
    }
}

/** 本家の 2shot.js と同じ: 入室者が来たら「相手を退室」を出し、相手が抜けたらサーバーの判定に従う。 */
internal fun canBanGuestAfter(current: Boolean, update: ChatUpdate): Boolean = when {
    update.someoneEntered -> true
    update.guestLeft -> update.canBanGuest
    else -> current
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
                isPublic = page.isPublic,
                canBanGuest = page.canBanGuest,
                canChangePublic = page.canChangePublic,
                waitingMessage = page.waitingMessage,
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
                isPublic = update.isPublic ?: s.isPublic,
                canBanGuest = canBanGuestAfter(s.canBanGuest, update),
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

    fun banGuest() = runOwnerAction(OwnerAction.BAN_GUEST) { client.banGuest(room) }
    fun clearLog() = runOwnerAction(OwnerAction.CLEAR_LOG) { client.clearLog(room) }
    fun setPublic(public: Boolean) =
        runOwnerAction(if (public) OwnerAction.MAKE_PUBLIC else OwnerAction.MAKE_PRIVATE) { client.setPublic(room, public) }

    fun changeWaitingMessage(message: String) {
        val text = message.trim()
        if (text.isEmpty() || messageWidth(text) > WAITING_MESSAGE_MAX_WIDTH) return
        runOwnerAction(OwnerAction.CHANGE_MESSAGE) { client.changeWaitingMessage(room, text) }
    }

    fun dismissOwnerNotice() = _state.update { it.copy(ownerNotice = null) }

    /**
     * 作成者の操作を 1 つずつ送る。応答が部屋の画面なら、反映後の状態（公開設定・待機メッセージ・
     * 「相手を退室」の有無）をそこから読む。読めなければ成功を確認できないため失敗扱いにする。
     * ログ・読み出し位置は新着の取得に任せる（クリアは新着の取得でも届く）。
     */
    private fun runOwnerAction(action: OwnerAction, send: suspend () -> ChatPage?) {
        val s = _state.value
        if (!s.showsOwnerActions || s.ownerAction != null) return
        val lastLineId = s.lines.firstOrNull()?.id
        _state.update { it.copy(ownerAction = action, ownerNotice = null) }
        viewModelScope.launch {
            try {
                val page = send()
                _state.update { it.afterOwnerAction(action, page, lastLineId) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(ownerAction = null, ownerNotice = "${action.failure}。${describeError(e)}") }
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

    companion object {
        /** 本家の待機メッセージの上限（半角 1、全角 2 で数える）。 */
        const val WAITING_MESSAGE_MAX_WIDTH = 500
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 10_000L
    }
}
