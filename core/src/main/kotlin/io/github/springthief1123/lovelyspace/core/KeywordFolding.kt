package io.github.springthief1123.lovelyspace.core

import java.text.Normalizer
import java.util.Locale

/**
 * 検索語と部屋の名前・募集文の表記の揺れをそろえる。両方を [fold] してから部分一致を見る。
 *
 * - 全角・半角（NFKC。半角カナ・全角英数字など）
 * - 大文字・小文字
 * - ひらがな・カタカナ（カタカナをひらがなにする）
 * - 長音・波線・ハイフンの類（すべて「ー」にする。「まったり〜」と「まったりー」を同じにする）
 */
object KeywordFolding {
    fun fold(text: String): String {
        // 一覧の全ページの部屋ごとに呼ぶので、文字ごとの List を作らずに 1 つの StringBuilder で組み立てる。
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val out = StringBuilder(normalized.length)
        for (c in normalized) out.append(when {
            c in KATAKANA -> c - KANA_OFFSET
            DASHES.indexOf(c) >= 0 -> 'ー'
            else -> c
        })
        return out.toString()
    }

    private val KATAKANA = 'ァ'..'ヶ'
    private const val KANA_OFFSET = 0x60
    private const val DASHES = "-‐‑‒–—―−~〜"
}

/**
 * 同じ意味として扱う語のまとまり（「チャH」と「ちゃえっち」など）。検索語がまとまりの語のどれかと同じなら、
 * まとまりのどの語が含まれていても一致にする。語はまとまりの語と完全に同じとき（[KeywordFolding.fold] 後）だけ広げる。
 */
class SynonymDictionary(groups: List<List<String>>) {
    private val byWord: Map<String, Set<String>> = groups
        .map { g -> g.map(KeywordFolding::fold).map(String::trim).filter(String::isNotEmpty).toSet() }
        .filter { it.size > 1 }
        .flatMap { g -> g.map { it to g } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, gs) -> gs.flatten().toSet() }

    /** [term] と同じ意味として扱う語（[KeywordFolding.fold] 済み。[term] 自身を含む）。 */
    fun variants(term: String): Set<String> = KeywordFolding.fold(term).let { byWord[it] ?: setOf(it) }

    fun hasSynonyms(term: String): Boolean = KeywordFolding.fold(term) in byWord

    companion object {
        val NONE = SynonymDictionary(emptyList())

        /** 最初から入れておく、表記の揺れでは吸収できない言い換え。本家の実データではなく一般的な語。 */
        val BUILT_IN = SynonymDictionary(listOf(
            listOf("チャH", "ちゃえっち", "チャットエッチ", "チャットH"),
            listOf("寝落ち", "ねおち"),
            listOf("癒し", "癒やし", "いやし"),
            listOf("甘々", "あまあま", "甘あま"),
            listOf("暇つぶし", "暇潰し", "ひまつぶし"),
        ))
    }
}
