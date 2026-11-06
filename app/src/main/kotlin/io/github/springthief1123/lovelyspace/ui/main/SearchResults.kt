package io.github.springthief1123.lovelyspace.ui.main

import io.github.springthief1123.lovelyspace.core.Genre
import io.github.springthief1123.lovelyspace.core.NgExcluded
import io.github.springthief1123.lovelyspace.core.NgWordFilter
import io.github.springthief1123.lovelyspace.core.Room
import io.github.springthief1123.lovelyspace.core.RoomSearchCriteria
import io.github.springthief1123.lovelyspace.core.partitionNg
import io.github.springthief1123.lovelyspace.core.searchRooms
import io.github.springthief1123.lovelyspace.core.roomIdentity
import io.github.springthief1123.lovelyspace.core.roomIdentityEvidence
import io.github.springthief1123.lovelyspace.core.RoomIdentityEvidence
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate

/**
 * 見つける画面の絞り込みの結果と、計算に使ったジャンル・一覧・条件・NG ワード。
 * [excluded] は条件に合ったうち NG ワードで外した部屋で、件数と中身の確認に使う。
 */
internal class SearchResults(
    val genre: Genre,
    val rooms: List<Room>,
    val criteria: RoomSearchCriteria,
    val results: List<Room>,
    val ng: NgWordFilter = NgWordFilter.NONE,
    val excluded: List<NgExcluded> = emptyList(),
) {
    /** いまのジャンル・一覧・条件から計算した結果か。一覧は自動更新で毎回新しく作られるので、同じ一覧かどうかで比べる。 */
    fun isFor(genre: Genre, rooms: List<Room>, criteria: RoomSearchCriteria, ng: NgWordFilter = NgWordFilter.NONE): Boolean =
        this.genre == genre && this.rooms === rooms && this.criteria == criteria && this.ng === ng

    /**
     * 自動更新で一覧だけが新しくなった間、裏の計算が終わるまでこの結果を出してよいか。
     * ジャンルか条件か NG ワードが違う結果は、別の部屋や条件に合わない部屋を出すことになるので使わない。
     */
    fun canStandIn(genre: Genre, criteria: RoomSearchCriteria, ng: NgWordFilter = NgWordFilter.NONE): Boolean =
        this.genre == genre && this.criteria == criteria && this.ng === ng

    /**
     * 裏での絞り直しを待つ間も、カードの在室状態と入室動作は最新の一覧に合わせる。
     * 前の結果にしかない部屋や、同じ ID が別人に再利用された部屋は操作させない。
     */
    fun currentCards(latestRooms: List<Room>): List<Room> {
        if (rooms === latestRooms) return results
        val latestByKey = latestRooms.associateBy(::roomIdentity)
        return results.mapNotNull { old ->
            val current = latestByKey[roomIdentity(old)] ?: return@mapNotNull null
            if (roomIdentityEvidence(old, current) == RoomIdentityEvidence.REUSED ||
                (old.message != current.message && (old.name == null || current.name == null))
            ) return@mapNotNull null
            if (criteria.waitingOnly != null && !current.isFull != criteria.waitingOnly) return@mapNotNull null
            if (criteria.publicOnly != null && current.isPublic != criteria.publicOnly) return@mapNotNull null
            current
        }
    }

    companion object {
        fun of(genre: Genre, rooms: List<Room>, criteria: RoomSearchCriteria, ng: NgWordFilter = NgWordFilter.NONE): SearchResults {
            // 条件で絞ってから NG ワードを見る（判定する部屋を減らす）。
            val (kept, excluded) = partitionNg(searchRooms(rooms, criteria), ng)
            return SearchResults(genre, rooms, criteria, kept, ng, excluded)
        }
    }
}

/**
 * いま画面に出す結果。いまの一覧・条件の結果があればそれを、自動更新で一覧だけが変わった間は前の結果を使う。
 * ジャンルか条件が変わったときは、前の結果を出さずにその場で計算する（条件を変えた直後に古い結果を操作させない）。
 */
internal fun SearchResults?.resolve(genre: Genre, rooms: List<Room>, criteria: RoomSearchCriteria, ng: NgWordFilter = NgWordFilter.NONE): SearchResults =
    if (this != null && (isFor(genre, rooms, criteria, ng) || canStandIn(genre, criteria, ng))) this
    else SearchResults.of(genre, rooms, criteria, ng)

/**
 * 入力ごとに [compute] し、結果を [onResult] に渡す。計算中に届いた入力は取り消しの理由にせず、最新の 1 つにまとめて次に計算する。
 * 自動更新で一覧が計算より短い間隔で届き続けても、結果がいつまでも出ないことがない（取り消してやり直すと、絞り込んだ一覧が前の結果のまま止まる）。
 * [skip] が true の入力は計算しない。
 */
internal suspend fun <I, R> Flow<I>.finishThenLatest(skip: (I) -> Boolean, compute: suspend (I) -> R, onResult: (R) -> Unit) =
    conflate().collect { input -> if (!skip(input)) onResult(compute(input)) }
