package io.github.springthief1123.lovelyspace.ui.main

import io.github.springthief1123.lovelyspace.core.Genre
import io.github.springthief1123.lovelyspace.core.NgExcluded
import io.github.springthief1123.lovelyspace.core.NgWordFilter
import io.github.springthief1123.lovelyspace.core.Room
import io.github.springthief1123.lovelyspace.core.RoomSearchCriteria
import io.github.springthief1123.lovelyspace.core.partitionNg
import io.github.springthief1123.lovelyspace.core.searchRooms

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
