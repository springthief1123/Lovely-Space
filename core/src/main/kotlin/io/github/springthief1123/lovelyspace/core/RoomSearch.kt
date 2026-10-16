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

    /** [room] がこの条件に合うか。語は表記の揺れ（[KeywordFolding]）と [synonyms] の言い換えを吸収して比べる。 */
    fun matches(room: Room, synonyms: SynonymDictionary = SynonymDictionary.BUILT_IN): Boolean {
        if (!isValid) return false
        if (gender != null && room.gender != gender) return false
        if (room.age == null) {
            if (!includeUnknownAge) return false
        } else if ((minAge != null && room.age < minAge) || (maxAge != null && room.age > maxAge)) return false
        if (area != null && room.area != area) return false
        if (waitingOnly != null && !room.isFull != waitingOnly) return false
        if (publicOnly != null && room.isPublic != publicOnly) return false
        if (!keywordsMatch(room.name.orEmpty(), name, synonyms) || !keywordsMatch(room.message, message, synonyms)) return false
        val allText = KeywordFolding.fold("${room.name.orEmpty()} ${room.message}")
        if (!keywordsMatch(allText, this.text, synonyms)) return false
        return words(excluded).none { term -> synonyms.variants(term).any(allText::contains) }
    }

    private fun keywordsMatch(text: String, query: String, synonyms: SynonymDictionary): Boolean {
        val terms = words(query)
        if (terms.isEmpty()) return true
        val folded = KeywordFolding.fold(text)
        val hit = { term: String -> synonyms.variants(term).any(folded::contains) }
        return if (keywordMode == KeywordMode.ALL) terms.all(hit) else terms.any(hit)
    }
    private fun words(text: String) = KeywordFolding.fold(text).split(Regex("[\\s　]+")).filter(String::isNotEmpty)

    /**
     * [query] の語のうち、本家の文字検索（`srchname` / `srchmsg`、1 語の部分一致）に渡しても取りこぼしが出ない 1 語と、
     * その語の本家に渡す書き方（[siteForms]）。すべてを含む（AND）なら 1 語で絞っても一致する部屋は残るので、
     * 書き方の少ない語、同じなら長い語を選ぶ。どれかを含む（OR）は 1 語のときだけ。
     */
    internal fun siteKeyword(query: String, synonyms: SynonymDictionary = SynonymDictionary.BUILT_IN): List<String>? {
        val terms = query.trim().split(Regex("[\\s　]+")).filter(String::isNotEmpty)
        return when {
            terms.size == 1 -> siteForms(terms.single(), synonyms)
            keywordMode == KeywordMode.ALL -> terms.mapNotNull { siteForms(it, synonyms) }
                .minWithOrNull(compareBy<List<String>> { it.size }.thenByDescending { it.first().length })
            else -> null
        }
    }
}

/**
 * 本家の文字検索で [term] に一致する部屋を取りこぼさないために渡す書き方。端末はひらがな・カタカナを同じに扱うが本家は区別するので、
 * 入力どおり・ひらがな・カタカナの書き方を渡す（その分だけ一覧が増える）。次の語は渡さない（null）。
 * - 本家に渡せない語（[isSiteSearchable]）
 * - 半角カナ・長音の類など、かな以外の揺れを含む語（本家では書き方が多すぎて一覧に収まらない）
 * - 言い換え（[SynonymDictionary]）のある語
 */
private fun siteForms(term: String, synonyms: SynonymDictionary): List<String>? {
    if (!isSiteSearchable(term) || synonyms.hasSynonyms(term)) return null
    if (KeywordFolding.fold(term) != KeywordFolding.toHiragana(term)) return null
    return listOf(term, KeywordFolding.toHiragana(term), KeywordFolding.toKatakana(term)).distinct()
}

/** 1 つの検索で読む本家の一覧の数の上限。語の書き方の分だけ一覧が増えるので、増えすぎるなら本家では語を絞らない。 */
const val MAX_SITE_QUERIES = 4

/**
 * 本家の文字検索に渡せる語。端末側は大文字・小文字を区別しないが、本家は区別する（かな/カナはどちらも区別する）ので、
 * 大文字・小文字のある文字を含む語は渡さない。本家は Shift_JIS なので、表せない文字（絵文字など）を含む語も渡さない。
 */
private fun isSiteSearchable(term: String): Boolean =
    term.none { it.isUpperCase() || it.isLowerCase() || it.isTitleCase() } && SITE_CHARSET.newEncoder().canEncode(term)

/**
 * 本家の一覧の絞り込み（URL）に載せられる条件だけを載せた [RoomQuery]。
 * 本家で絞った一覧が、端末側の判定（[RoomSearchCriteria.matches]）で残る部屋を取りこぼさないものだけを選ぶ。
 * 端末側の判定は取得後にもう一度すべて掛けるので、本家側は一次絞り込みとして働く。
 *
 * - 性別・待機状態・公開状態はそのまま載せる。
 * - 年齢は範囲が本家の年齢帯 1 つに収まり、年齢未記入の部屋を含めないときだけ載せる
 *   （本家の年齢帯で未記入の部屋がどう扱われるかは未確認なので、含める設定では載せない）。
 * - 地域は載せない。端末側の地域は募集文の地域タグで、本家の vpref（プロフィールの都道府県）とは別物のため。
 * - 名前・募集文の語は、本家の文字検索に渡せる 1 語（[RoomSearchCriteria.siteKeyword]）を `srchname` / `srchmsg` に載せる。
 *   書き方が 1 つに決まる語（ひらがな・カタカナを含まない語）だけ。共通検索欄（名前か募集文のどちらか）と、
 *   書き方が複数ある語は 1 つの一覧では表せないので、ここでは載せない（[siteQueries] を使う）。
 */
fun RoomSearchCriteria.siteQuery(genre: Genre, page: Int = 1): RoomQuery = RoomQuery(
    genre = genre,
    sex = gender?.takeIf { it != Gender.UNKNOWN },
    ageBand = if (includeUnknownAge || !isValid) null else ageBandFor(minAge, maxAge),
    publicOnly = publicOnly,
    waitingOnly = waitingOnly,
    name = siteKeyword(name)?.singleOrNull(),
    message = siteKeyword(message)?.singleOrNull(),
    page = page,
)

/**
 * 検索に使う本家の一覧（1 ページ目）。ふつうは [siteQuery] の 1 つ。
 * 名前・募集文の語の書き方が複数あるとき（ひらがなとカタカナ）は、書き方ごとの一覧にする。
 * 名前・募集文の欄で本家の文字検索を使わず、共通検索欄に本家へ渡せる語があるときは、その語を名前で探した一覧と
 * 募集文で探した一覧の 2 つ（書き方が複数ならその分）にする。合わせれば、端末側の判定で一致する部屋を取りこぼさない。
 * 文字検索を使うと本家の一覧が数ページに収まるので、全ページをすぐ読み切れる。一覧が [MAX_SITE_QUERIES] を超えるなら語では絞らない。
 */
fun RoomSearchCriteria.siteQueries(genre: Genre): List<RoomQuery> {
    val base = siteQuery(genre).copy(name = null, message = null)
    val names = siteKeyword(name)
    val messages = siteKeyword(message)
    if (names != null || messages != null) {
        val (n, m) = when {
            (names?.size ?: 1) * (messages?.size ?: 1) <= MAX_SITE_QUERIES -> names to messages
            // 上限を超えるのは両方の欄に書き方が複数あるときだけ。書き方の少ない欄で絞る。
            names!!.size <= messages!!.size -> names to null
            else -> null to messages
        }
        return (n ?: listOf(null)).flatMap { nn -> (m ?: listOf(null)).map { mm -> base.copy(name = nn, message = mm) } }
    }
    val forms = siteKeyword(text)?.takeIf { it.size * 2 <= MAX_SITE_QUERIES } ?: return listOf(base)
    return forms.flatMap { listOf(base.copy(name = it), base.copy(message = it)) }
}

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

fun searchRooms(rooms: List<Room>, criteria: RoomSearchCriteria, synonyms: SynonymDictionary = SynonymDictionary.BUILT_IN): List<Room> {
    val matches = rooms.distinctBy(::roomIdentity).filter { criteria.matches(it, synonyms) }
    return when (criteria.sort) {
        RoomSort.SITE -> matches
        RoomSort.NAME -> matches.sortedWith(compareBy(nullsLast()) { it.name?.lowercase(Locale.ROOT) })
        RoomSort.AGE -> matches.sortedWith(compareBy(nullsLast()) { it.age })
        RoomSort.ELAPSED -> matches.sortedWith(compareBy(nullsLast()) { it.elapsed })
    }
}
