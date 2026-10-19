package io.github.springthief1123.lovelyspace.data

import io.github.springthief1123.lovelyspace.core.Genre
import io.github.springthief1123.lovelyspace.core.HotPageSchedule
import io.github.springthief1123.lovelyspace.core.RefreshPacing
import io.github.springthief1123.lovelyspace.core.RoomQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 新着の確認。見つける画面・レーダー・順番待ちが「見ているジャンル」を登録し（[want]）、アプリが前面の間（[run]）、
 * そのジャンルの一覧の 1 ページ目だけを設定の間隔ごとに読み直す（[HotPageSchedule]）。
 *
 * 新しい部屋は必ず 1 ページ目の先頭に出るので、画面ごとの周回（全ページを順に読む）を待たずに数秒で気づける。
 * 読んだページは [lists] の観測として見つける画面・順番待ち・立て直しの追跡にそのまま届き、レーダーには [onFetched] で渡す。
 * 通信は [lists]（＝ `ShaloveClient`）の priority で、設定の最小間隔を空けたうえで後ろのページの巡回より先に通す。
 */
class HotPagePoller(
    private val lists: ObservedRoomListSource,
    private val pacing: () -> RefreshPacing,
    private val onFetched: suspend (ObservedRoomPage) -> Unit = {},
    clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val schedule = HotPageSchedule(clock, pacing)
    private val owners = MutableStateFlow<Map<String, Set<Genre>>>(emptyMap())
    private val _genres = MutableStateFlow<Set<Genre>>(emptySet())
    /** いま 1 ページ目を読み直しているジャンル。 */
    val genres: StateFlow<Set<Genre>> = _genres.asStateFlow()

    /** [owner]（見つける画面・レーダー・順番待ち）が見ているジャンルを [genres] にする。空なら外す。 */
    fun want(owner: String, genres: Set<Genre>) {
        owners.update { if (genres.isEmpty()) it - owner else it + (owner to genres) }
        _genres.value = owners.value.values.flatten().toSet()
    }

    /** アプリが前面の間だけ呼ぶ（取消で止まる）。 */
    suspend fun run() {
        while (currentCoroutineContext().isActive) {
            val genres = _genres.value
            val next = schedule.next(genres)
            if (next == null) { _genres.first { it.isNotEmpty() }; continue }
            val (genre, wait) = next
            if (wait > 0) {
                // 待つ間に見るジャンルが変われば、選び直す。
                withTimeoutOrNull(wait) { _genres.first { it != genres } }
                continue
            }
            val query = RoomQuery(genre)
            val ok = try {
                lists.fetchPriority(query, force = true)
                true
            } catch (e: CancellationException) { throw e }
            // 失敗（部屋の表が無いページを含む）は 20 秒空けて読み直す（HotPageSchedule）。
            catch (_: Exception) { false }
            schedule.completed(genre, ok)
            if (ok) lists.observation(query)?.let { observed ->
                try { onFetched(observed) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            }
        }
    }
}
