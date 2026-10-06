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
