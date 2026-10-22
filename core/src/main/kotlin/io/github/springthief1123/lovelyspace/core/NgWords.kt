package io.github.springthief1123.lovelyspace.core

/** NG ワードを見る場所。 */
enum class NgTarget { NAME_AND_MESSAGE, NAME, MESSAGE }

/**
 * 常に見たくない部屋を、どの検索でも最初から外すための語のまとまり（「お金目的」など）。
 * 検索条件の「除外する語」（[RoomSearchCriteria.excluded]）はその検索だけのもので、こちらは ON の間ずっと効く。
 * [builtInKey] は最初から入れたセットの印で、「初期の語に戻す」に使う。
 */
data class NgWordSet(
    val id: String,
    val label: String,
    val words: List<String>,
    /** 「3000円」「1万円」「¥500」などの金額の表記も外すか（[NgAmount]）。 */
    val amounts: Boolean = false,
    val target: NgTarget = NgTarget.NAME_AND_MESSAGE,
    val enabled: Boolean = true,
    val builtInKey: String? = null,
) {
    companion object {
        /** 最初から入れておくセット。本家の実データではなく一般的な語。 */
        val BUILT_IN: List<NgWordSet> = listOf(
            // 語は関連するものどうしで並べる（写真・動画の販売、売り買い、お金・値段、支払い方、決済サービス）。
            // 長い語を短い語より前に置き、除外した部屋のシートに出る「当たった語」が具体的になるようにする（「写真販売」→「販売」、「PayPay」→「Pay」）。
            // 「写真」「動画」だけだと「写真交換なし」のような普通の部屋も外れるので入れない。
            NgWordSet("builtin-money", "お金目的", listOf(
                "写真販売", "画像販売", "自撮り販売", "動画販売", "サンプル",
                "販売", "購入", "売って", "買って", "実績",
                "お金", "お小遣い", "おこづかい", "投げ銭", "値段", "料金", "有料",
                "支払い", "支払", "先払い", "前払い", "後払い", "振込",
                "PayPay", "ペイペイ", "メルペイ", "ペイ", "Pay", "アマギフ"),
                amounts = true, builtInKey = "money"),
            // 「NH」は英字 2 字なので、名前や募集文に「nh」を含む部屋も外れる。「百合」は「百合子」なども外す。
            NgWordSet("builtin-newhalf", NEWHALF_LABEL, listOf(
                "ニューハーフ", "NH", "女装", "男の娘",
                "レズビアン", "レズチャ", "れずちゃ", "レズ", "ビアン", "百合"),
                builtInKey = "newhalf"),
        )

        /** 「ニューハーフ系」から改めた名前（2026-10-22、女性どうしの部屋の語も入れた）。 */
        const val NEWHALF_LABEL = "ニューハーフ・レズ系"

        fun builtIn(key: String): NgWordSet? = BUILT_IN.firstOrNull { it.builtInKey == key }

        /** 入力欄の文字列を語に分ける。空白（全角も）や改行で区切り、重複を除く。 */
        fun splitWords(text: String): List<String> = text.split(SPACES).filter(String::isNotEmpty).distinct()

        private val SPACES = Regex("[\\s　]+")
    }
}

/** 金額の表記。[KeywordFolding.fold] 後の文字列に対して見る。利用者には正規表現を書かせず、この決まった形だけを持つ。 */
object NgAmount {
    const val LABEL = "金額の表記"

    // 数字＋円（3000円・3,000円・1.5万円）、漢数字＋円（千円・五千円・一万円。「関東一円」は外さない）、円記号＋数字。
    // Shift_JIS の円記号は「\」として届くことがあるので、それも円記号として扱う。
    private val PATTERN = Regex(
        "[0-9][0-9,.]*\\s*[百千万]?\\s*円|[一二三四五六七八九十]*[百千万][一二三四五六七八九十百千万]*円|[¥\\\\]\\s*[0-9]"
    )

    /** [folded] の中の最初の金額の表記。無ければ null。 */
    fun find(folded: String): String? = PATTERN.find(folded)?.value
}

/** 部屋が NG ワードに当たった理由。[word] は当たった語（金額なら [NgAmount.LABEL]）。 */
data class NgMatch(val setLabel: String, val word: String)

/**
 * ON の [NgWordSet] で部屋を判定する。語のそろえ（[KeywordFolding.fold]）と言い換えの展開は作るときに 1 回だけ済ませ、
 * 部屋ごとには名前と募集文をそれぞれ 1 回だけそろえる。
 */
class NgWordFilter private constructor(private val sets: List<Compiled>) {
    private class Compiled(val label: String, val target: NgTarget, val amounts: Boolean, val terms: List<Pair<String, Set<String>>>)

    val isEmpty: Boolean get() = sets.isEmpty()
    /** 効いている（ON で、語か金額の表記がある）セットの数。 */
    val setCount: Int get() = sets.size

    /** [room] が当たった最初のセットと語。当たらなければ null。 */
    fun matchOf(room: Room): NgMatch? {
        if (sets.isEmpty()) return null
        val name by lazy(LazyThreadSafetyMode.NONE) { KeywordFolding.fold(room.name.orEmpty()) }
        val message by lazy(LazyThreadSafetyMode.NONE) { KeywordFolding.fold(room.message) }
        for (set in sets) {
            // 名前と募集文はつなげずに別々に見る。つなぎ目をまたいで語ができないように。
            val texts = when (set.target) {
                NgTarget.NAME_AND_MESSAGE -> listOf(name, message)
                NgTarget.NAME -> listOf(name)
                NgTarget.MESSAGE -> listOf(message)
            }
            set.terms.firstOrNull { (_, variants) -> texts.any { text -> variants.any(text::contains) } }
                ?.let { return NgMatch(set.label, it.first) }
            if (set.amounts && texts.any { NgAmount.find(it) != null }) return NgMatch(set.label, NgAmount.LABEL)
        }
        return null
    }

    fun matches(room: Room): Boolean = matchOf(room) != null

    companion object {
        val NONE = NgWordFilter(emptyList())

        fun of(sets: List<NgWordSet>, synonyms: SynonymDictionary = SynonymDictionary.BUILT_IN): NgWordFilter =
            NgWordFilter(sets.filter { it.enabled }.mapNotNull { set ->
                val terms = set.words.map(String::trim).filter(String::isNotEmpty).distinct()
                    .map { it to synonyms.variants(it) }.filter { (_, v) -> v.none(String::isEmpty) }
                if (terms.isEmpty() && !set.amounts) null else Compiled(set.label, set.target, set.amounts, terms)
            }).takeIf { !it.isEmpty } ?: NONE
    }
}

/** NG ワードで外した部屋と、その理由。 */
data class NgExcluded(val room: Room, val match: NgMatch)

/** [rooms] を、NG ワードに当たらない部屋と当たった部屋に分ける。順番はそれぞれ元のまま。 */
fun partitionNg(rooms: List<Room>, filter: NgWordFilter): Pair<List<Room>, List<NgExcluded>> {
    if (filter.isEmpty) return rooms to emptyList()
    val kept = ArrayList<Room>(rooms.size)
    val excluded = ArrayList<NgExcluded>()
    for (room in rooms) {
        val match = filter.matchOf(room)
        if (match == null) kept += room else excluded += NgExcluded(room, match)
    }
    return kept to excluded
}
