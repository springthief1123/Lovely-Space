package io.github.springthief1123.lovelyspace.ui.web

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.springthief1123.lovelyspace.core.RefreshPacing
import io.github.springthief1123.lovelyspace.core.RoomPageSchedule
import io.github.springthief1123.lovelyspace.core.chat.PublicRoomPage
import io.github.springthief1123.lovelyspace.core.chat.PublicRoomUnavailableException
import io.github.springthief1123.lovelyspace.core.chat.RoomRoles
import io.github.springthief1123.lovelyspace.ui.chat.UiLine
import io.github.springthief1123.lovelyspace.ui.describeError
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class PublicRoomUiState(
    val opened: Boolean = false, val loading: Boolean = false,
    /** 閲覧中は常に true。部屋が閉じられたら false にして自動の読み直しを止める。 */
    val automatic: Boolean = true,
    val lines: List<UiLine> = emptyList(), val information: String = "", val error: String? = null,
    /** 部屋主・入室者の名前。読み直しても引き継ぎ、入室者の発言を右に出す。 */
    val roles: RoomRoles = RoomRoles(),
    /** 部屋主・入室者が分からない間に左に出す人（最初に発言した人）。名前の違う人は右に出す。 */
    val leftSpeaker: String? = null,
    /** 一度も読めないまま、開いてから [PublicRoomViewModel.FRESH_RETRY_MS] の間は読み直している（入室された直後の部屋）。 */
    val waitingForLog: Boolean = false,
)

/**
 * [fetch] の引数は利用者が押した読み込みか（「覗く」で開いた最初の読み込み・「再読み込み」）。押した読み込みは間隔を空けずに取る。
 * [clock] は前回の読み込みからの経過時間の計測用（ミリ秒）。
 */
class PublicRoomViewModel(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val fetch: suspend (manual: Boolean) -> PublicRoomPage,
) : ViewModel() {
    private val _state = MutableStateFlow(PublicRoomUiState())
    val state = _state.asStateFlow()
    private val mutex = Mutex()
    private var nextId = 0L
    private var refreshJob: Job? = null
    /** 前回の読み込みが終わった時刻。自動の読み直しはここから間隔を数える。 */
    private var readAt: Long? = null
    /** 最初の読み込みを始めた時刻。入室された直後で公開ログが読めない間、読み直し続ける期間を数える。 */
    private var firstReadAt: Long? = null
    /**
     * 画面を開いたらすぐ読み込む。画面の切り替えの動きが終わるのを待たない。
     * 一度読み込んだ後（画面の回転など）は何もしない。
     */
    fun open() { if (readAt == null && refreshJob == null) refreshJob = viewModelScope.launch { read(manual = true) } }
    fun refresh() { if (!_state.value.loading && refreshJob?.isActive != true) refreshJob = viewModelScope.launch { read(manual = true) } }
    fun stopRefresh() { refreshJob?.cancel() }
    /**
     * 閲覧中、[interval]（設定の間隔、ミリ秒）ごとに読み直す。読めなかったときは間を空けてから試す。
     * 間隔は前回の読み込みから数えるので、開いた直後の読み込みの後や、戻ってきたときにすぐ二重には読まない。
     */
    suspend fun monitor(interval: () -> Long = { RefreshPacing.DEFAULT_PUBLIC_ROOM_MS }) {
        // 開いた直後の読み込みが終わるのを待ってから数え始める。
        refreshJob?.takeIf { it.isActive }?.join()
        while (currentCoroutineContext().isActive && _state.value.automatic) {
            // 通信の失敗は間を空ける。ページは届いたが公開ログがまだ無い間は、設定の間隔で読み直す。
            val failed = _state.value.error != null && !_state.value.waitingForLog
            val gap = if (failed) maxOf(interval(), RoomPageSchedule.ERROR_INTERVAL_MS) else interval()
            val wait = readAt?.let { it + gap - clock() } ?: 0
            if (wait > 0) { delay(wait); continue }
            read(manual = false)
        }
    }
    /** 一度でも公開ログを読めたか。 */
    private var seen = false
    private suspend fun read(manual: Boolean) = mutex.withLock {
        _state.update { it.copy(loading = true) }
        val startedAt = firstReadAt ?: clock().also { firstReadAt = it }
        try {
            val page = fetch(manual)
            currentCoroutineContext().ensureActive()
            // 同文・同時刻の発言も行数分保持し、更新後も既存のスクロール位置を維持する。
            val existing = _state.value.lines.groupBy { it.line }.mapValues { it.value.asReversed().toMutableList() }
            val roles = _state.value.roles.learn(page.lines)
            val leftSpeaker = roles.firstSpeaker(page.lines, _state.value.leftSpeaker)
            val lines = page.lines.asReversed().map { line ->
                val isRight = roles.isRight(line, leftSpeaker)
                existing[line]?.removeFirstOrNull()?.copy(isMine = isRight) ?: UiLine(nextId++, line, isRight)
            }.asReversed()
            // 閉じられた後に「再読み込み」で読めたら、自動の読み直しへ戻す。
            _state.update { it.copy(opened = true, automatic = true, roles = roles, leftSpeaker = leftSpeaker, lines = lines, information = page.information, error = null, waitingForLog = false) }
            seen = true
        } catch (e: CancellationException) { throw e }
        catch (e: PublicRoomUnavailableException) {
            // 入室された直後の部屋は、本家の公開ログがまだ読めないことがある（2026-10-18 Yuya）。
            // 一度も読めていなければ、開いてからしばらくは閉じたと決めずに読み直す。読めていた部屋が読めなくなったら閉じた。
            val fresh = !seen && clock() - startedAt < FRESH_RETRY_MS
            _state.update {
                it.copy(lines = emptyList(), opened = false, automatic = fresh, waitingForLog = fresh,
                    error = if (fresh) "$FRESH_NOTE\n${e.message}" else e.message)
            }
        } catch (e: Exception) { _state.update { it.copy(error = describeError(e), waitingForLog = false) } }
        finally {
            // 取り消した読み込み（読み込み中に画面を離れたなど）は読んだことにしない。戻ったらすぐ読み直す。
            if (currentCoroutineContext().isActive) readAt = clock()
            _state.update { it.copy(loading = false) }
        }
    }

    companion object {
        /** 一度も読めない部屋を、開いてから読み直し続ける時間。 */
        const val FRESH_RETRY_MS = 2 * 60_000L
        const val FRESH_NOTE = "入室された直後は、本家の公開ログがまだ読めないことがあります。自動で読み直します。"
    }
}
