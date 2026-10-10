package io.github.springthief1123.lovelyspace.ai

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.time.Instant
import java.util.UUID

/** 合成文章の分類群を使って人手で定めた「関連する文書」集合。曖昧なものは実機評価後に見直す。 */
internal data class PocQualityCase(
    val id: String,
    val query: String,
    val keywordTerms: List<String>,
    val relevantIndexes: Set<Int>,
)

internal data class PocQualityMetric(
    val precisionAt5: Double,
    val recallAt10: Double,
    val mrrAt10: Double,
)

internal data class PocQualityResult(
    val id: String,
    val semantic: PocQualityMetric,
    val literal: PocQualityMetric,
)

internal object PocQuality {
    val cases = listOf(
        PocQualityCase("Q1", "静かな雰囲気で時間をかけておしゃべりしたい", listOf("ゆっくり", "のんびり"), (setOf(0, 1) + (5..14)).toSet()),
        PocQualityCase("Q2", "あと少しだけ気軽にお話ししたい", listOf("短時間", "5分"), (setOf(2) + (15..24)).toSet()),
        PocQualityCase("Q3", "ゲームの攻略やレベル上げを相談したい", listOf("ゲーム", "攻略"), (setOf(3) + (25..34)).toSet()),
        PocQualityCase("Q4", "映画を観た感想を交換したい", listOf("映画", "感想"), (setOf(4) + (35..44)).toSet()),
        PocQualityCase("Q5", "旅行や旅先のおすすめを話したい", listOf("旅行", "旅"), (65..74).toSet()),
        PocQualityCase("Q6", "好きな音楽とライブの話がしたい", listOf("音楽", "ライブ"), (75..84).toSet()),
        PocQualityCase("Q7", "料理や食べ物のおいしい話をしたい", listOf("料理", "お菓子"), (45..54).toSet()),
    )

    init {
        require(cases.map { it.id }.distinct().size == cases.size)
        require(cases.all { c -> c.relevantIndexes.isNotEmpty() && c.relevantIndexes.all { it in PocCorpus.all.indices } })
    }

    fun metric(ranking: List<Int>, relevant: Set<Int>): PocQualityMetric {
        require(relevant.isNotEmpty())
        require(ranking.distinct().size == ranking.size)
        val hitsAt5 = ranking.take(5).count { it in relevant }
        val hitsAt10 = ranking.take(10).count { it in relevant }
        val firstRelevant = ranking.take(10).indexOfFirst { it in relevant }
        return PocQualityMetric(
            precisionAt5 = hitsAt5 / 5.0,
            recallAt10 = hitsAt10.toDouble() / relevant.size,
            mrrAt10 = if (firstRelevant < 0) 0.0 else 1.0 / (firstRelevant + 1),
        )
    }

    /** AND/OR本番検索とは異なる。単なる包含キーワードの一致数で順位を付けるベースライン。 */
    fun literalRanking(case: PocQualityCase): List<Int> = PocCorpus.all.indices.sortedWith(
        compareByDescending<Int> { index ->
            case.keywordTerms.count { term -> PocCorpus.all[index].contains(term, ignoreCase = true) }
        }.thenBy { it },
    )

    fun compare(case: PocQualityCase, semanticIndexes: List<Int>): PocQualityResult =
        PocQualityResult(
            case.id,
            metric(semanticIndexes, case.relevantIndexes),
            metric(literalRanking(case), case.relevantIndexes),
        )
}

/** CSVにはQ番号と集計数値のみ。検証クエリの文章は保存しない。 */
internal object PocQualityLogs {
    private const val FILE = "poc-quality-v1.csv"
    private const val MAX_LOG_BYTES = 512 * 1024
    private const val HEADER = "utc,run_id,case_id,precision_at5,recall_at10,mrr_at10,keyword_precision_at5,keyword_recall_at10,keyword_mrr_at10"

    private fun path(context: Context) = File(File(context.filesDir, "ai-poc"), FILE)

    fun size(context: Context): Long = path(context).takeIf { it.isFile }?.length() ?: 0
    fun delete(context: Context): Boolean = !path(context).exists() || path(context).delete()

    fun append(context: Context, results: List<PocQualityResult>) {
        require(results.size == PocQuality.cases.size)
        val timestamp = Instant.now().toString()
        val runId = UUID.randomUUID().toString()
        val content = buildString {
            for (row in results) {
                append(PocLogs.csv(listOf(
                    timestamp, runId, row.id,
                    row.semantic.precisionAt5, row.semantic.recallAt10, row.semantic.mrrAt10,
                    row.literal.precisionAt5, row.literal.recallAt10, row.literal.mrrAt10,
                )))
            }
        }
        val file = path(context)
        check(file.parentFile?.isDirectory == true || file.parentFile?.mkdirs() == true)
        check(size(context) + content.toByteArray().size + HEADER.length < MAX_LOG_BYTES)
        OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8).use { out ->
            if (file.length() == 0L) out.write(HEADER + "\n")
            out.write(content)
        }
    }

    fun export(context: Context, uri: Uri) {
        val file = path(context)
        check(file.isFile) { "品質評価の記録がありません" }
        val stream = context.contentResolver.openOutputStream(uri) ?: error("出力先を開けません")
        stream.use { dst -> file.inputStream().use { it.copyTo(dst) } }
    }
}
