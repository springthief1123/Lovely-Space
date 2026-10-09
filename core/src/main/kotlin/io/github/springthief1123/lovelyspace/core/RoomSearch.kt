package io.github.springthief1123.lovelyspace.core

import java.util.Locale

enum class KeywordMode { ALL, ANY }
enum class RoomSort { SITE, NAME, AGE, ELAPSED }

data class RoomSearchCriteria(
    val name: String = "",
    val message: String = "",
    val excluded: String = "",
    val keywordMode: KeywordMode = KeywordMode.ALL,
    val gender: Gender? = null,
    val minAge: Int? = null,
    val maxAge: Int? = null,
    val includeUnknownAge: Boolean = true,
    val area: String? = null,
    val waitingOnly: Boolean? = null,
    val publicOnly: Boolean? = null,
    val sort: RoomSort = RoomSort.SITE,
    /** 名前と募集文をまとめて探す、共通検索欄。 */
    val text: String = "",
) {
    val isValid: Boolean get() = (minAge == null || minAge in 18..99) &&
        (maxAge == null || maxAge in 18..99) && (minAge == null || maxAge == null || minAge <= maxAge)

    fun matches(room: Room): Boolean {
        if (!isValid) return false
        if (gender != null && room.gender != gender) return false
        if (room.age == null) {
            if (!includeUnknownAge) return false
        } else if ((minAge != null && room.age < minAge) || (maxAge != null && room.age > maxAge)) return false
        if (area != null && room.area != area) return false
        if (waitingOnly != null && !room.isFull != waitingOnly) return false
        if (publicOnly != null && room.isPublic != publicOnly) return false
        if (!keywordsMatch(room.name.orEmpty(), name) || !keywordsMatch(room.message, message)) return false
        val allText = "${room.name.orEmpty()} ${room.message}".lowercase(Locale.ROOT)
        if (!keywordsMatch(allText, this.text)) return false
        return words(excluded).none { allText.contains(it) }
    }

    private fun keywordsMatch(text: String, query: String): Boolean {
        val terms = words(query)
        if (terms.isEmpty()) return true
        val normalized = text.lowercase(Locale.ROOT)
        return if (keywordMode == KeywordMode.ALL) terms.all { normalized.contains(it) } else terms.any { normalized.contains(it) }
    }
    private fun words(text: String) = text.trim().lowercase(Locale.ROOT).split(Regex("[\\s　]+" )).filter(String::isNotEmpty)
}

/**
 * 本家の一覧の絞り込み（URL）に載せられる条件だけを載せた [RoomQuery]。
 * 本家で絞った一覧が、端末側の判定（[RoomSearchCriteria.matches]）で残る部屋を取りこぼさないものだけを選ぶ。
 * 端末側の判定は取得後にもう一度すべて掛けるので、本家側は一次絞り込みとして働く。
 *
 * - 性別・待機状態・公開状態はそのまま載せる。
 * - 年齢は範囲が本家の年齢帯 1 つに収まり、年齢未記入の部屋を含めないときだけ載せる
 *   （本家の年齢帯で未記入の部屋がどう扱われるかは未確認なので、含める設定では載せない）。
 * - 地域は載せない。端末側の地域は募集文の地域タグで、本家の vpref（プロフィールの都道府県）とは別物のため。
 * - 名前・募集文は載せない。本家は かな/カナ・大文字小文字を同一視せず、複数語・除外も扱えないため。
 */
fun RoomSearchCriteria.siteQuery(genre: Genre, page: Int = 1): RoomQuery = RoomQuery(
    genre = genre,
    sex = gender?.takeIf { it != Gender.UNKNOWN },
    ageBand = if (includeUnknownAge || !isValid) null else ageBandFor(minAge, maxAge),
    publicOnly = publicOnly,
    waitingOnly = waitingOnly,
    page = page,
)

/** [min]〜[max] が本家の年齢帯 1 つに収まればその値（"20-29" など）。またがる・指定なしなら null。 */
internal fun ageBandFor(min: Int?, max: Int?): String? {
    if (min == null && max == null) return null
    val lo = min ?: 0
    val hi = max ?: Int.MAX_VALUE
    return AGE_BANDS.firstOrNull { (_, range) -> lo >= range.first && hi <= range.last }?.first
}

private val AGE_BANDS = listOf(
    "-19" to 0..19, "20-29" to 20..29, "30-39" to 30..39, "40-49" to 40..49, "50-59" to 50..59, "60-" to 60..Int.MAX_VALUE,
)

/** hostと部屋IDで重複を除く。ジャンルを横断しても別サーバーの同じ番号は別の部屋。 */
fun roomIdentity(room: Room): String = "${Genres[room.genreKey]?.host ?: room.genreKey}/${room.id}"

fun searchRooms(rooms: List<Room>, criteria: RoomSearchCriteria): List<Room> {
    val matches = rooms.distinctBy(::roomIdentity).filter(criteria::matches)
    return when (criteria.sort) {
        RoomSort.SITE -> matches
        RoomSort.NAME -> matches.sortedWith(compareBy(nullsLast()) { it.name?.lowercase(Locale.ROOT) })
        RoomSort.AGE -> matches.sortedWith(compareBy(nullsLast()) { it.age })
        RoomSort.ELAPSED -> matches.sortedWith(compareBy(nullsLast()) { it.elapsed })
    }
}
