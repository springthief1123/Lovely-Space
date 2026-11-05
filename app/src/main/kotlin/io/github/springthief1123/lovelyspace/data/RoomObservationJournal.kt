package io.github.springthief1123.lovelyspace.data

import io.github.springthief1123.lovelyspace.core.RoomListPage
import io.github.springthief1123.lovelyspace.core.RoomQuery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 一覧の取得1回に対応する観測イベントと、その時点のページ集合。
 * 後で同じページが更新されても、立て直しの照合に新しいページを混ぜない。
 */
data class ObservedRoomChange(
    val observation: ObservedRoomPage,
    val pages: Map<RoomQuery, ObservedRoomPage>,
)

/**
 * 表示用の最新StateFlowと、検出用の順序付きイベントを分ける。
 * SharedFlowのバッファが一杯になったら取得結果の配信を待ち、イベントを破棄しない。
 * 未購読中の変化は復元できないが、購読開始時には現時点のページを一度配信する。
 */
internal class RoomObservationJournal {
    private val mutex = Mutex()
    private val _observations = MutableStateFlow<Map<RoomQuery, ObservedRoomPage>>(emptyMap())
    val observations: StateFlow<Map<RoomQuery, ObservedRoomPage>> = _observations.asStateFlow()
    private val _changes = MutableSharedFlow<ObservedRoomChange>(extraBufferCapacity = 64)
    private var revision = 0L

    val changes: Flow<ObservedRoomChange> = _changes.onSubscription {
        // 購読開始後に同じMutexでスナップショットを取る。以降のイベントとの順序を保つ。
        val snapshot = mutex.withLock { _observations.value }
        snapshot.values.sortedBy { it.revision }.forEach { emit(ObservedRoomChange(it, snapshot)) }
    }

    /** キャッシュが返した同一ページのインスタンスは観測として数えない。 */
    suspend fun record(query: RoomQuery, page: RoomListPage, confirmedAt: Long, onNewPage: () -> Unit = {}) {
        mutex.withLock {
            if (_observations.value[query]?.page === page) return@withLock
            onNewPage()
            val observed = ObservedRoomPage(query, page, confirmedAt, ++revision)
            val snapshot = _observations.value + (query to observed)
            _observations.value = snapshot
            _changes.emit(ObservedRoomChange(observed, snapshot))
        }
    }
}
