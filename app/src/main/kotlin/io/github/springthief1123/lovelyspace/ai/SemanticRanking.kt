package io.github.springthief1123.lovelyspace.ai

import kotlin.math.sqrt

/** 埋め込みモデルに依存しない、意味検索のスコア計算。検索・通信・通知への副作用は持たない。 */
object SemanticRanking {
    data class Result(val text: String, val similarity: Double, val originalIndex: Int)

    /** コサイン類似度。類似度は成功確率ではなく、比較対象間だけの相対的な値。 */
    fun rank(query: FloatArray, documents: List<Pair<String, FloatArray>>): List<Result> {
        val queryNorm = norm(query)
        return documents.mapIndexed { index, (text, vector) ->
            require(vector.size == query.size) { "埋め込みベクトルの次元が一致しません" }
            val documentNorm = norm(vector)
            val dot = query.indices.sumOf { i -> query[i].toDouble() * vector[i].toDouble() }
            Result(text, (dot / (queryNorm * documentNorm)).coerceIn(-1.0, 1.0), index)
        }.sortedWith(compareByDescending<Result> { it.similarity }.thenBy { it.originalIndex })
    }

    private fun norm(vector: FloatArray): Double {
        require(vector.isNotEmpty()) { "埋め込みベクトルが空です" }
        require(vector.all { it.isFinite() }) { "埋め込みベクトルに無効な値があります" }
        val length = sqrt(vector.sumOf { it.toDouble() * it.toDouble() })
        require(length > 0.0 && length.isFinite()) { "埋め込みベクトルの長さが不正です" }
        return length
    }
}
