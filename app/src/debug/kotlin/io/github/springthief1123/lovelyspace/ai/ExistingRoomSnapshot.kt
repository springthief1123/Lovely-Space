package io.github.springthief1123.lovelyspace.ai

import io.github.springthief1123.lovelyspace.core.Genre
import io.github.springthief1123.lovelyspace.core.Room
import io.github.springthief1123.lovelyspace.data.ObservedRoomPage
import java.text.Normalizer
import java.util.Locale

/**
 * プロセス内で取得済みの公開募集文だけを扱う。HTTP・DB・ログ・通知には依存しない。
 * 名前・部屋ID・属性はAIに渡さず、ジャンルは取得済み候補の範囲選択だけに用いる。
 */
internal object ExistingRoomSnapshot {
    const val MAX_DOCUMENTS = 100
    const val MAX_MESSAGE_LENGTH = 240
    const val MAX_QUERY_LENGTH = 120
    const val MAX_KEYWORD_LENGTH = 40
    const val MAX_AGE_MS = 10 * 60 * 1_000L

    /** 最新の取得済みカテゴリーを先頭にする。ここでは新規取得しない。 */
    fun availableGenres(pages: Collection<ObservedRoomPage>, nowMs: Long): List<Genre> =
        pages.asSequence()
            .filter { (nowMs - it.confirmedAt) in 0..MAX_AGE_MS }
            .filter { page ->
                page.page.rooms.any { !it.isFull && it.message.isNotBlank() && it.genreKey == page.query.genre.key }
            }
            .sortedByDescending { it.confirmedAt }
            .map { it.query.genre }
            .distinctBy { it.key }
            .toList()

    /** 取得済みの直近10分・非満室の募集文。genreKeyを指定すると他カテゴリーを完全除外する。 */
    fun select(pages: Collection<ObservedRoomPage>, nowMs: Long, genreKey: String? = null): List<String> {
        val roomPages = pages.asSequence()
            .filter { page ->
                val age = nowMs - page.confirmedAt
                age in 0..MAX_AGE_MS && (genreKey == null || page.query.genre.key == genreKey)
            }
            .sortedByDescending { it.confirmedAt }
            .flatMap { it.page.rooms.asSequence() }
        val seen = HashSet<Pair<String, Long>>()
        return roomPages
            .filter { it.isVisiblePublicMessage() }
            .filter { genreKey == null || it.genreKey == genreKey }
            .filter { seen.add(it.genreKey to it.id) }
            .map { it.message.trim().take(MAX_MESSAGE_LENGTH) }
            .take(MAX_DOCUMENTS)
            .toList()
    }

    private fun Room.isVisiblePublicMessage(): Boolean = !isFull && message.isNotBlank()

    fun validateQuery(query: String): String {
        val normalized = query.trim()
        require(normalized.isNotBlank()) { "検索文を入力してください" }
        require(normalized.length <= MAX_QUERY_LENGTH) { "検索文は120文字までです" }
        return normalized
    }

    /**
     * 希望文が12文字以下の単語ならそのまま文字列比較にも使う。
     * 長い自然文は自動的に分かち書きしない（誤った一致を作らないため）。
     */
    fun comparisonKeyword(query: String, explicit: String): String? {
        val trimmed = explicit.trim()
        require(trimmed.length <= MAX_KEYWORD_LENGTH) { "照合語句は40文字までです" }
        val candidate = trimmed.ifEmpty {
            query.trim().takeIf { it.length <= 12 && it.none(Char::isWhitespace) } ?: ""
        }
        return candidate.takeIf { it.isNotBlank() }
    }

    /** 文字列の部分一致。表記ゆれ辞書や意味的な一致とは異なる。 */
    fun containsKeyword(text: String, keyword: String): Boolean =
        normalize(text).contains(normalize(keyword))

    private fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)

    data class VectorDiagnostics(val dimensions: Int, val distinctDocumentFingerprints: Int)

    /**
     * 正規の埋め込み次元・有限値・非ゼロを検査する。ハッシュの種類数は参考であり、
     * 同じ文章やハッシュ衝突を含み得るため検索品質の判定には使用しない。
     */
    fun inspectVectors(query: FloatArray, documents: List<Pair<String, FloatArray>>): VectorDiagnostics {
        require(query.isNotEmpty() && query.all { it.isFinite() } && query.any { it != 0f }) {
            "検索ベクトルが不正です"
        }
        documents.forEach { (_, vector) ->
            require(vector.size == query.size && vector.all { it.isFinite() } && vector.any { it != 0f }) {
                "募集文ベクトルが不正です"
            }
        }
        return VectorDiagnostics(
            dimensions = query.size,
            distinctDocumentFingerprints = documents.map { it.second.contentHashCode() }.toSet().size,
        )
    }

    data class HybridRanking(
        val keyword: String?,
        val confirmed: List<SemanticRanking.Result>,
        val aiOnly: List<SemanticRanking.Result>,
        val aiTop10KeywordHits: Int,
        val highestCosine: Double?,
        val lowestCosine: Double?,
    )

    /**
     * 確定=検索語句の文字列一致のみ。AI順位は別枠で参考表示し、閾値や確率と誤解させない。
     * どちらも取得済み募集文のみで判定し、文字列・順位・ベクトルを保存しない。
     */
    fun classify(ranked: List<SemanticRanking.Result>, query: String, explicitKeyword: String): HybridRanking {
        val keyword = comparisonKeyword(query, explicitKeyword)
        val confirmed = if (keyword == null) emptyList() else ranked.filter { containsKeyword(it.text, keyword) }
        val aiOnly = if (keyword == null) ranked else ranked.filterNot { containsKeyword(it.text, keyword) }
        val topHits = if (keyword == null) 0 else ranked.take(10).count { containsKeyword(it.text, keyword) }
        return HybridRanking(
            keyword = keyword,
            confirmed = confirmed,
            aiOnly = aiOnly,
            aiTop10KeywordHits = topHits,
            highestCosine = ranked.firstOrNull()?.similarity,
            lowestCosine = ranked.lastOrNull()?.similarity,
        )
    }
}
