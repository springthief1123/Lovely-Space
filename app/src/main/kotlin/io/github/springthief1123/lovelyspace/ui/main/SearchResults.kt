package io.github.springthief1123.lovelyspace.ui.main

import io.github.springthief1123.lovelyspace.core.Genre
import io.github.springthief1123.lovelyspace.core.Room
import io.github.springthief1123.lovelyspace.core.RoomSearchCriteria
import io.github.springthief1123.lovelyspace.core.searchRooms

/** 見つける画面の絞り込みの結果と、計算に使ったジャンル・一覧・条件。 */
internal class SearchResults(
    val genre: Genre,
    val rooms: List<Room>,
    val criteria: RoomSearchCriteria,
    val results: List<Room>,
) {
    /** いまのジャンル・一覧・条件から計算した結果か。一覧は自動更新で毎回新しく作られるので、同じ一覧かどうかで比べる。 */
    fun isFor(genre: Genre, rooms: List<Room>, criteria: RoomSearchCriteria): Boolean =
        this.genre == genre && this.rooms === rooms && this.criteria == criteria

    /**
     * 自動更新で一覧だけが新しくなった間、裏の計算が終わるまでこの結果を出してよいか。
     * ジャンルか条件が違う結果は、別の部屋や条件に合わない部屋を出すことになるので使わない。
     */
    fun canStandIn(genre: Genre, criteria: RoomSearchCriteria): Boolean = this.genre == genre && this.criteria == criteria

    companion object {
        fun of(genre: Genre, rooms: List<Room>, criteria: RoomSearchCriteria) =
            SearchResults(genre, rooms, criteria, searchRooms(rooms, criteria))
    }
}

/**
 * いま画面に出す結果。いまの一覧・条件の結果があればそれを、自動更新で一覧だけが変わった間は前の結果を使う。
 * ジャンルか条件が変わったときは、前の結果を出さずにその場で計算する（条件を変えた直後に古い結果を操作させない）。
 */
internal fun SearchResults?.resolve(genre: Genre, rooms: List<Room>, criteria: RoomSearchCriteria): SearchResults =
    if (this != null && (isFor(genre, rooms, criteria) || canStandIn(genre, criteria))) this
    else SearchResults.of(genre, rooms, criteria)
