package io.github.springthief1123.lovelyspace.data

import io.github.springthief1123.lovelyspace.core.RoomListPage
import io.github.springthief1123.lovelyspace.core.RoomQuery
import kotlinx.coroutines.CancellationException

/** 1 回の同期で各ページがどうなったか。 */
sealed interface ListSyncOutcome {
    data class Fetched(val page: RoomListPage) : ListSyncOutcome
    data class Failed(val error: Exception) : ListSyncOutcome
    /** 要求した機能が取得前に不要になった。 */
    data object Skipped : ListSyncOutcome
    /** 1 回あたりの上限を超えたので次回へ回した。 */
    data object Deferred : ListSyncOutcome
}

/**
 * 巡回・追跡・背景実行などから集まった一覧の取得要求を 1 本にまとめる。
 * 同じジャンル・ページ・絞り込みは 1 回だけ取得し、1 回の同期で取得するページ数に上限を設ける。
 * 取得は [lists]（＝ `ShaloveClient` の間隔制限とキャッシュ）を通り、ページは順番に 1 つずつ取る。
 */
class ListSync(private val lists: RoomListSource) {
    /** 前回の上限で延期した先頭ページ。次回はここから始め、後続の要求を取りこぼさない。 */
    private var nextFirst: RoomQuery? = null

    /**
     * [requests] を重複を除いた順に取得する。[shouldFetch] が false を返したページは取得しない。
     * 取得のたびに [onResult] を呼び、次のページへ進む前に各機能が結果を反映できるようにする。
     */
    suspend fun sync(
        requests: Iterable<RoomQuery>,
        maxPages: Int = Int.MAX_VALUE,
        force: Boolean = false,
        shouldFetch: suspend (RoomQuery) -> Boolean = { true },
        onResult: suspend (RoomQuery, ListSyncOutcome) -> Unit = { _, _ -> },
    ): Map<RoomQuery, ListSyncOutcome> {
        val planned = plan(requests)
        val start = nextFirst?.let { planned.indexOf(it) }?.takeIf { it >= 0 } ?: 0
        val ordered = if (start == 0) planned else planned.drop(start) + planned.take(start)

        val outcomes = linkedMapOf<RoomQuery, ListSyncOutcome>()
        var fetched = 0
        for (query in ordered) {
            val outcome = when {
                fetched >= maxPages -> ListSyncOutcome.Deferred
                !shouldFetch(query) -> ListSyncOutcome.Skipped
                else -> {
                    fetched++
                    try { ListSyncOutcome.Fetched(lists.fetch(query, force)) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { ListSyncOutcome.Failed(e) }
                }
            }
            outcomes[query] = outcome
            onResult(query, outcome)
        }
        val deferred = outcomes.entries.firstOrNull { it.value is ListSyncOutcome.Deferred }?.key
        // 今回の要求に含まれなかった延期分は、別の要求の同期で上書きせずに持ち越す。
        nextFirst = deferred ?: nextFirst?.takeUnless { it in outcomes }
        return outcomes
    }

    companion object {
        /** 要求を出た順に並べ、同じページを 1 つにする。 */
        fun plan(requests: Iterable<RoomQuery>): List<RoomQuery> = requests.distinct()
    }
}
