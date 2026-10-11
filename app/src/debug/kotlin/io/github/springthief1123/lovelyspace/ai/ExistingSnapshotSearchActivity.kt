package io.github.springthief1123.lovelyspace.ai

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.springthief1123.lovelyspace.LovelySpaceApp
import io.github.springthief1123.lovelyspace.MainActivity
import io.github.springthief1123.lovelyspace.ui.components.QuietPanel
import io.github.springthief1123.lovelyspace.ui.components.QuietTopBar
import io.github.springthief1123.lovelyspace.ui.theme.LovelySpaceTheme
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * debug限定の手動検証。ユーザーが通常画面ですでに取得した公開待機中の募集文だけ扱う。
 * モデルにも外部のネットワークにも接続しない（モデル推論は端末内CPU）。
 * Activityを離れたら推論停止を要求し、検索文と検索結果を画面状態から破棄する。
 */
class ExistingSnapshotSearchActivity : ComponentActivity() {
    override fun onStop() {
        super.onStop()
        PocModelSession.releaseAsync()
        // 利用者が画面を離れたら、このActivityと一時表示内容を破棄する。
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LovelySpaceTheme { ExistingSnapshotSearchScreen(::finish) }
        }
    }
}

private const val QUERY_PREFIX = "task: search query | text: "
private const val DOCUMENT_PREFIX = "task: search result | text: "
private const val MODEL_FILE = "embeddinggemma-2-text-270m.litertlm"

private data class ExistingSnapshotResult(
    val ranked: List<SemanticRanking.Result>,
    val corpusSize: Int,
    val initializationMs: Long,
    val embeddingMs: Long,
    val totalMs: Long,
)

private suspend fun evaluateExistingSnapshot(
    model: File,
    texts: List<String>,
    rawQuery: String,
): ExistingSnapshotResult = withContext(Dispatchers.IO) {
    val query = ExistingRoomSnapshot.validateQuery(rawQuery)
    require(texts.isNotEmpty()) { "新しい一覧データがありません" }
    check(model.isFile && model.length() in 1_000_000L..600_000_000L) {
        "モデルがありません"
    }
    val start = SystemClock.elapsedRealtime()
    val session = PocModelSession.run(model, keepWarm = false) { engine ->
        val embedStart = SystemClock.elapsedRealtime()
        // どの募集文もメモリ内でのみ計算。PocVectorCache.persistやログ書き込みを呼ばない。
        val queryVector = engine.embed(QUERY_PREFIX + query)
        val vectors = texts.map { message ->
            message to engine.embed(DOCUMENT_PREFIX + message)
        }
        val embeddingMs = SystemClock.elapsedRealtime() - embedStart
        val ranked = SemanticRanking.rank(queryVector, vectors)
        ranked to embeddingMs
    }
    ExistingSnapshotResult(
        ranked = session.value.first,
        corpusSize = texts.size,
        initializationMs = session.initializationMs,
        embeddingMs = session.value.second,
        totalMs = SystemClock.elapsedRealtime() - start,
    )
}

@Composable
private fun ExistingSnapshotSearchScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as LovelySpaceApp
    val scope = rememberCoroutineScope()
    val model = remember(context) {
        File(File(context.filesDir, "ai-poc"), MODEL_FILE)
    }
    var query by remember { mutableStateOf("") }
    var currentCount by remember {
        mutableStateOf(ExistingRoomSnapshot.select(app.roomLists.observations.value.values, System.currentTimeMillis()).size)
    }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ExistingSnapshotResult?>(null) }
    var message by remember {
        mutableStateOf("公開待機中の取得済み募集文のみ、手動で1回だけ端末内検索します。")
    }

    Scaffold(topBar = { QuietTopBar(title = "取得済み一覧AI検証", onBack = onClose) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            QuietPanel {
                Text("debug版・手動の端末内検索", style = MaterialTheme.typography.titleMedium)
                Text(
                    "この画面は本家サイトへリクエストしません。通常の「見つける」で取得済みの直近10分以内の待機中の募集文だけを、最大100件調べます。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("対象の募集文: " + currentCount + "件（最大100件）")
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    onClick = {
                        currentCount = ExistingRoomSnapshot.select(
                            app.roomLists.observations.value.values, System.currentTimeMillis(),
                        ).size
                        result = null
                        message = if (currentCount == 0) {
                            "取得済みの待機中募集文がありません。「見つける」で一覧を取得してから戻ってください。"
                        } else "取得済み一覧を確認しました。この画面からの通信は行っていません。"
                    },
                ) { Text("取得済み一覧の件数を再確認") }
                if (currentCount == 0) {
                    Text(
                        "通常のLovely Spaceを開いて「見つける」で一覧を表示し、同じプロセスのうちにこの検証画面に戻ってください。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    onClick = { context.startActivity(Intent(context, MainActivity::class.java)) },
                ) { Text("通常のLovely Spaceを開く") }
            }

            QuietPanel {
                Text("日本語で希望を入力", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(ExistingRoomSnapshot.MAX_QUERY_LENGTH) },
                    label = { Text("希望する雰囲気（最大120文字）") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    minLines = 2,
                    maxLines = 3,
                )
                val modelReady = model.isFile && model.length() in 1_000_000L..600_000_000L
                if (!modelReady) {
                    Text("先に従来の「AI意味検索検証」からモデルを取り込んでください。")
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                        onClick = { context.startActivity(Intent(context, EmbeddingPocActivity::class.java)) },
                    ) { Text("モデル取り込み画面へ") }
                }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy && modelReady && currentCount > 0 && query.isNotBlank(),
                    onClick = {
                        busy = true
                        result = null
                        message = "端末内で検索しています。データの保存や外部送信は行いません。"
                        val input = query
                        scope.launch {
                            try {
                                // 押した瞬間に取得済みページをコピーし、古い一覧は使用しない。
                                val texts = ExistingRoomSnapshot.select(
                                    app.roomLists.observations.value.values, System.currentTimeMillis(),
                                )
                                currentCount = texts.size
                                val evaluated = evaluateExistingSnapshot(model, texts, input)
                                result = evaluated
                                message = "検索が完了しました。結果はこの画面のメモリにだけ保持します。"
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: LinkageError) {
                                message = "推論SDKを利用できません。モデルとライブラリを確認してください。"
                            } catch (_: Exception) {
                                // Exceptionの文言にはモデルパスや募集文が含まれうるため表示・記録しない。
                                message = "検索に失敗しました。取得済み一覧、モデル、端末メモリを確認してください。"
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) { Text("取得済み一覧を端末内で意味検索") }
                if (busy) CircularProgressIndicator()
                Text(message, style = MaterialTheme.typography.bodySmall)
                Text(
                    "この試験で検索文・募集文・ベクトルは保存しません。入室、通知、巡回、元の検索条件には一切反映しません。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            result?.let { value ->
                QuietPanel {
                    Text("検索結果（相対的な類似度順）", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "対象 " + value.corpusSize + "件 / モデル初期化 " +
                            value.initializationMs + "ms / ベクトル計算 " +
                            value.embeddingMs + "ms / 全体 " + value.totalMs + "ms",
                    )
                    value.ranked.take(10).forEachIndexed { index, ranked ->
                        Text(
                            (index + 1).toString() + ". " + ranked.text +
                                "\nコサイン類似度 " +
                                String.format(Locale.ROOT, "%.4f", ranked.similarity),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Text(
                        "順位とスコアは参考値です。通知条件や入室判断に使用しません。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
