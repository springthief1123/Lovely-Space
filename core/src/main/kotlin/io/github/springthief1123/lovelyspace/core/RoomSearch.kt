package io.github.springthief1123.lovelyspace.core

import java.util.Locale

enum class KeywordMode { ALL, ANY }
/** 並び順。[RECOMMENDED]（おすすめ順）は好みの学習（[PreferenceModel]）が要るので、[searchRooms] では本家の順のまま返し、画面が並べ替える。 */
enum class RoomSort { SITE, NAME, AGE, ELAPSED, RECOMMENDED }

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
    fun matches(room: Room, synonyms: SynonymDictionary = SynonymDictionary.BUILT_IN): Boolean = RoomMatcher(this, synonyms).matches(room)
}

/**
 * [RoomSearchCriteria] の判定を、検索語のそろえ（[KeywordFolding.fold]）と言い換えの展開を先に 1 回だけ済ませて行う。
 * 一覧の全ページを絞るたびに、部屋ごとに検索語をそろえ直さないため。
 */
private class RoomMatcher(private val c: RoomSearchCriteria, synonyms: SynonymDictionary) {
    private val nameTerms = terms(c.name, synonyms)
    private val messageTerms = terms(c.message, synonyms)
    private val textTerms = terms(c.text, synonyms)
    // 各欄の「-語」は、その欄で語を含む部屋を外す（「すべて含む」「どれかを含む」に関係なく）。
    private val nameExcluded = terms(c.name, synonyms, negative = true)
    private val messageExcluded = terms(c.message, synonyms, negative = true)
    private val excludedTerms = terms(c.excluded, synonyms) + terms(c.text, synonyms, negative = true)

    fun matches(room: Room): Boolean {
        if (!c.isValid) return false
        if (c.gender != null && room.gender != c.gender) return false
        if (room.age == null) {
            if (!c.includeUnknownAge) return false
        } else if ((c.minAge != null && room.age < c.minAge) || (c.maxAge != null && room.age > c.maxAge)) return false
        if (c.area != null && !Prefectures.matches(room, c.area)) return false
        if (c.waitingOnly != null && !room.isFull != c.waitingOnly) return false
        if (c.publicOnly != null && room.isPublic != c.publicOnly) return false
        if (!keywordsMatch(room.name.orEmpty(), nameTerms) || !keywordsMatch(room.message, messageTerms)) return false
        if (containsAny(room.name.orEmpty(), nameExcluded) || containsAny(room.message, messageExcluded)) return false
        // 名前と募集文をまとめた文は、共通検索欄か除外する語があるときだけそろえる。
        if (textTerms.isEmpty() && excludedTerms.isEmpty()) return true
        val allText = KeywordFolding.fold("${room.name.orEmpty()} ${room.message}")
        if (!keywordsMatch(allText, textTerms, folded = true)) return false
        return excludedTerms.none { variants -> variants.any(allText::contains) }
    }

    private fun keywordsMatch(text: String, terms: List<Set<String>>, folded: Boolean = false): Boolean {
        if (terms.isEmpty()) return true
        val target = if (folded) text else KeywordFolding.fold(text)
        val hit = { variants: Set<String> -> variants.any(target::contains) }
        return if (c.keywordMode == KeywordMode.ALL) terms.all(hit) else terms.any(hit)
    }

    private fun containsAny(text: String, terms: List<Set<String>>): Boolean {
        if (terms.isEmpty()) return false
        val target = KeywordFolding.fold(text)
        return terms.any { variants -> variants.any(target::contains) }
    }

    /**
     * 語ごとの、同じ意味として扱う語（[KeywordFolding.fold] 済み）。[negative] なら先頭が「-」の語（除外）だけ、そうでなければそれ以外。
     * 「-」はそろえると長音「ー」になるので、そろえる前の語で見分ける。「-」だけの語は使わない。
     */
    private fun terms(text: String, synonyms: SynonymDictionary, negative: Boolean = false): List<Set<String>> =
        text.split(SPACES).filter(String::isNotEmpty)
            .filter { (it.first() in MINUS) == negative }
            .map { if (negative) it.drop(1) else it }
            .map(KeywordFolding::fold).filter(String::isNotEmpty).map(synonyms::variants)

    companion object {
        private val SPACES = Regex("[\\s　]+")
        /** 除外の印。半角・全角のハイフンマイナスと数学のマイナス。長音「ー」は含めない。 */
        private const val MINUS = "-－−"
    }
}

/** hostと部屋IDで重複を除く。ジャンルを横断しても別サーバーの同じ番号は別の部屋。 */
fun roomIdentity(room: Room): String = "${Genres[room.genreKey]?.host ?: room.genreKey}/${room.id}"

fun searchRooms(rooms: List<Room>, criteria: RoomSearchCriteria, synonyms: SynonymDictionary = SynonymDictionary.BUILT_IN): List<Room> {
    val matcher = RoomMatcher(criteria, synonyms)
    val matches = rooms.distinctBy(::roomIdentity).filter(matcher::matches)
    return when (criteria.sort) {
        RoomSort.SITE, RoomSort.RECOMMENDED -> matches
        RoomSort.NAME -> matches.sortedWith(compareBy(nullsLast()) { it.name?.lowercase(Locale.ROOT) })
        RoomSort.AGE -> matches.sortedWith(compareBy(nullsLast()) { it.age })
        RoomSort.ELAPSED -> matches.sortedWith(compareBy(nullsLast()) { it.elapsed })
    }
}
