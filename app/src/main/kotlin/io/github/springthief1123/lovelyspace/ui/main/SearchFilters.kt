package io.github.springthief1123.lovelyspace.ui.main

import io.github.springthief1123.lovelyspace.core.Gender
import io.github.springthief1123.lovelyspace.core.KeywordMode
import io.github.springthief1123.lovelyspace.core.Prefectures
import io.github.springthief1123.lovelyspace.core.RoomSearchCriteria
import io.github.springthief1123.lovelyspace.data.SearchPreset

/**
 * 検索シートの年齢のチップ。押すと最低・最高年齢をその年代に合わせ、続けて押すと範囲を広げる。
 * 「〜19」は下限なし（本家は 18 歳から）、「50〜」は上限なし。
 */
internal enum class AgeBand(val label: String, val min: Int?, val max: Int?) {
    TEENS("〜19", null, 19),
    TWENTIES("20代", 20, 29),
    THIRTIES("30代", 30, 39),
    FORTIES("40代", 40, 49),
    FIFTIES_UP("50〜", 50, null),
}

/**
 * いまの最低・最高年齢をチップで表すと、どの年代が選ばれているか。年齢を指定していなければ空。
 * 年代の境目にそろっていない（「25〜33歳」など）ときは null で、数字の入力欄を出す。
 */
internal fun ageBandSelection(min: Int?, max: Int?): List<AgeBand>? {
    if (min == null && max == null) return emptyList()
    val bands = AgeBand.entries
    val first = if (min == null) 0 else bands.indexOfFirst { it.min == min }
    val last = if (max == null) bands.lastIndex else bands.indexOfFirst { it.max == max }
    if (first < 0 || last < 0 || first > last) return null
    return bands.subList(first, last + 1)
}

/**
 * 年代のチップ [band] を押した後の最低・最高年齢。チップは押すたびに選択・解除を切り替える。
 * 条件は「最低〜最高」の 1 つの範囲なので、選んだ年代はいつも続いた並びになる。
 * - 選んでいない年代を押すと、そこまで範囲を広げる（間の年代も選ぶ）。
 * - 選んでいる年代を押すと、その年代を外す。範囲の真ん中なら、外した年代で分かれた両側のうち多いほう（同じなら若いほう）を残す。
 */
internal fun tapAgeBand(min: Int?, max: Int?, band: AgeBand): Pair<Int?, Int?> {
    val selected = ageBandSelection(min, max).orEmpty()
    val range = { from: AgeBand, to: AgeBand -> from.min to to.max }
    if (band !in selected) {
        if (selected.isEmpty()) return range(band, band)
        val all = selected + band
        return range(all.minBy { it.ordinal }, all.maxBy { it.ordinal })
    }
    val lower = selected.filter { it.ordinal < band.ordinal }
    val upper = selected.filter { it.ordinal > band.ordinal }
    val kept = if (upper.size > lower.size) upper else lower
    return if (kept.isEmpty()) null to null else range(kept.first(), kept.last())
}

/** 年齢の条件を短く言う。例: 「20〜29歳」「30歳以上」。 */
internal fun ageSummary(min: Int?, max: Int?): String = when {
    min == null && max == null -> "指定なし"
    min == null -> "${max}歳以下"
    max == null -> "${min}歳以上"
    else -> "${min}〜${max}歳"
}

/** 一覧の上に出す、指定中の条件の種類。✕ で 1 つずつ外せる。 */
internal enum class ActiveFilterKind { TEXT, NAME, MESSAGE, GENDER, WAITING, PUBLIC, AGE, UNKNOWN_AGE, AREA, EXCLUDED }

internal data class ActiveFilter(val kind: ActiveFilterKind, val label: String) {
    /** この条件を直す場所が検索シートのどのタブか。上のチップ・検索欄で直すものは null。 */
    val tab: SearchSheetTab? get() = when (kind) {
        ActiveFilterKind.AGE, ActiveFilterKind.UNKNOWN_AGE, ActiveFilterKind.AREA -> SearchSheetTab.AGE_AREA
        ActiveFilterKind.EXCLUDED -> SearchSheetTab.KEYWORD
        else -> null
    }
}

/** 指定中の条件を、一覧の上に並べる順（検索シートの上から順）で返す。並び順は別に出しているので含めない。 */
internal fun activeFilters(c: RoomSearchCriteria): List<ActiveFilter> {
    // 「どれかを含む」は語の条件の付け足しなので、語のチップに添える。
    val any = if (c.keywordMode == KeywordMode.ANY) "（どれか）" else ""
    return listOfNotNull(
        c.text.takeIf { it.isNotBlank() }?.let { ActiveFilter(ActiveFilterKind.TEXT, "「${it.trim()}」$any") },
        c.name.takeIf { it.isNotBlank() }?.let { ActiveFilter(ActiveFilterKind.NAME, "名前「${it.trim()}」$any") },
        c.message.takeIf { it.isNotBlank() }?.let { ActiveFilter(ActiveFilterKind.MESSAGE, "待機「${it.trim()}」$any") },
        c.gender?.let { ActiveFilter(ActiveFilterKind.GENDER, if (it == Gender.FEMALE) "女性" else "男性") },
        c.waitingOnly?.let { ActiveFilter(ActiveFilterKind.WAITING, if (it) "待機中" else "満室") },
        c.publicOnly?.let { ActiveFilter(ActiveFilterKind.PUBLIC, if (it) "公開" else "非公開") },
        if (c.minAge != null || c.maxAge != null) ActiveFilter(ActiveFilterKind.AGE, ageSummary(c.minAge, c.maxAge)) else null,
        if (!c.includeUnknownAge) ActiveFilter(ActiveFilterKind.UNKNOWN_AGE, "年齢が秘密の部屋を除く") else null,
        c.area?.let { ActiveFilter(ActiveFilterKind.AREA, it) },
        c.excluded.takeIf { it.isNotBlank() }?.let { ActiveFilter(ActiveFilterKind.EXCLUDED, "除外「${it.trim()}」") },
    )
}

/** [kind] の条件だけを外した条件。 */
internal fun RoomSearchCriteria.without(kind: ActiveFilterKind): RoomSearchCriteria = when (kind) {
    ActiveFilterKind.TEXT -> copy(text = "")
    ActiveFilterKind.NAME -> copy(name = "")
    ActiveFilterKind.MESSAGE -> copy(message = "")
    ActiveFilterKind.GENDER -> copy(gender = null)
    ActiveFilterKind.WAITING -> copy(waitingOnly = null)
    ActiveFilterKind.PUBLIC -> copy(publicOnly = null)
    ActiveFilterKind.AGE -> copy(minAge = null, maxAge = null)
    ActiveFilterKind.UNKNOWN_AGE -> copy(includeUnknownAge = true)
    ActiveFilterKind.AREA -> copy(area = null)
    ActiveFilterKind.EXCLUDED -> copy(excluded = "")
}

/** 保存した条件 [preset] が、いまのジャンルと条件そのものか。並び順は一覧の上で別に変えるので比べない。 */
internal fun presetMatches(preset: SearchPreset, genreKey: String, criteria: RoomSearchCriteria): Boolean =
    preset.genreKey == genreKey && preset.criteria.copy(sort = criteria.sort) == criteria

/** 地域を選ぶ画面の、地方ごとのまとまり。都道府県の並びは [Prefectures.names]（JIS の順）のまま。 */
internal val PrefectureRegions: List<Pair<String, List<String>>> = Prefectures.names.let { n ->
    listOf(
        "北海道・東北" to n.subList(0, 7),
        "関東" to n.subList(7, 14),
        "中部" to n.subList(14, 23),
        "近畿" to n.subList(23, 30),
        "中国" to n.subList(30, 35),
        "四国" to n.subList(35, 39),
        "九州・沖縄" to n.subList(39, 47),
        "海外" to n.subList(47, 48),
    )
}

/** 地域のチップに出す候補。選んでいる地域を先頭に、最近選んだ地域を続け、[limit] 件まで。 */
internal fun areaShortcuts(selected: String?, recent: List<String>, limit: Int = 4): List<String> =
    (listOfNotNull(selected) + recent).filter { it in Prefectures.names }.distinct().take(limit)
