package io.github.springthief1123.lovelyspace.ai

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.os.SystemClock
import android.widget.Toast
import android.view.WindowManager
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
        // 一覧由来の公開募集文も最近のアプリ一覧・スクリーンショットへ残さない。
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            LovelySpaceTheme { ExistingSnapshotSearchScreen(::finish) }
        }
    }
}

private const val QUERY_PREFIX = "task: search query | text: "
private const val DOCUMENT_PREFIX = "task: search result | text: "
private const val MODEL_FILE = "embeddinggemma-2-text-270m.litertlm"

// 利用者が選んだ取得済み公開募集文だけを、原文のまま端末内でコピーする。
// 追加通信・ログ・ファイル保存・GitHubへの投稿は行わない。
internal fun copyPublicMessage(context: Context, text: String) {
    val clip = ClipData.newPlainText("募集文", text)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(clip)
    Toast.makeText(context, "募集文をコピーしました", Toast.LENGTH_SHORT).show()
}

private data class ExistingSnapshotResult(
    val assessment: ExistingRoomSnapshot.HybridRanking,
    val vectorDiagnostics: ExistingRoomSnapshot.VectorDiagnostics,
    val corpusSize: Int,
    val initializationMs: Long,
    val embeddingMs: Long,
    val totalMs: Long,
)

private suspend fun evaluateExistingSnapshot(
    model: File,
    texts: List<String>,
    rawQuery: String,
    rawKeyword: String,
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
        val diagnostics = ExistingRoomSnapshot.inspectVectors(queryVector, vectors)
        val ranked = SemanticRanking.rank(queryVector, vectors)
        Triple(ExistingRoomSnapshot.classify(ranked, query, rawKeyword), embeddingMs, diagnostics)
    }
    ExistingSnapshotResult(
        assessment = session.value.first,
        vectorDiagnostics = session.value.third,
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
    // 独立したdebugアイコンから通常のアプリロックを迂回して一覧を見ることを防ぐ。
    // MainActivityを背面化すると即時ロックされ得るため、有効時は解除済みでも使用しない。
    val lockState by app.appLock.state.collectAsStateWithLifecycle()
    if (lockState.config.enabled) {
        Scaffold(topBar = { QuietTopBar(title = "取得済み一覧AI検証", onBack = onClose) }) { inset ->
            Column(Modifier.fillMaxSize().padding(inset).padding(16.dp)) {
                QuietPanel {
                    Text("アプリロックが有効なため、この検証は利用できません。")
                    Text(
                        "実データの表示を伴うdebug画面には独立の認証がありません。アプリロックを有効にしたまま、この検証を行うことはできません。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        return
    }
    val scope = rememberCoroutineScope()
    val model = remember(context) {
        File(File(context.filesDir, "ai-poc"), MODEL_FILE)
    }
    var query by remember { mutableStateOf("") }
    var keyword by remember { mutableStateOf("") }
    var availableGenres by remember {
        mutableStateOf(ExistingRoomSnapshot.availableGenres(app.roomLists.observations.value.values, System.currentTimeMillis()))
    }
    var genreKey by remember { mutableStateOf(availableGenres.firstOrNull()?.key) }
    var genreExpanded by remember { mutableStateOf(false) }
    var currentCount by remember {
        mutableStateOf(genreKey?.let {
            ExistingRoomSnapshot.select(app.roomLists.observations.value.values, System.currentTimeMillis(), it).size
        } ?: 0)
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
                Text("対象カテゴリーを選択（取得済み一覧のみ）")
                TextButton(onClick = { genreExpanded = true }, enabled = !busy && availableGenres.isNotEmpty()) {
                    Text(availableGenres.firstOrNull { it.key == genreKey }?.label ?: "取得済みカテゴリーなし")
                }
                DropdownMenu(expanded = genreExpanded, onDismissRequest = { genreExpanded = false }) {
                    availableGenres.forEach { genre ->
                        DropdownMenuItem(
                            text = { Text(genre.label) },
                            onClick = {
                                genreExpanded = false
                                genreKey = genre.key
                                currentCount = ExistingRoomSnapshot.select(
                                    app.roomLists.observations.value.values, System.currentTimeMillis(), genre.key,
                                ).size
                                result = null
                            },
                        )
                    }
                }
                Text("対象の募集文: " + currentCount + "件（最大100件）")
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    onClick = {
                        val pages = app.roomLists.observations.value.values
                        val now = System.currentTimeMillis()
                        availableGenres = ExistingRoomSnapshot.availableGenres(pages, now)
                        if (availableGenres.none { it.key == genreKey }) genreKey = availableGenres.firstOrNull()?.key
                        currentCount = genreKey?.let { ExistingRoomSnapshot.select(pages, now, it).size } ?: 0
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
                    onClick = { context.startActivity(Intent(context, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        }) },
                ) { Text("通常のLovely Spaceを開く") }
            }

            QuietPanel {
                Text("日本語で希望を入力", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it.take(ExistingRoomSnapshot.MAX_QUERY_LENGTH)
                        result = null
                    },
                    label = { Text("希望する雰囲気（最大120文字）") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    minLines = 2,
                    maxLines = 3,
                )
                OutlinedTextField(
                    value = keyword,
                    onValueChange = {
                        keyword = it.take(ExistingRoomSnapshot.MAX_KEYWORD_LENGTH)
                        result = null
                    },
                    label = { Text("文字列照合の語句（任意・最大40文字）") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    singleLine = true,
                )
                Text(
                    "空欄なら12文字以内・空白なしの希望文を比較語句に使用します。語句は募集文のみを文字列照合し、名前やタイトルは対象外です。",
                    style = MaterialTheme.typography.bodySmall,
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
                    enabled = !busy && modelReady && currentCount > 0 && genreKey != null && query.isNotBlank(),
                    onClick = {
                        busy = true
                        result = null
                        message = "端末内で検索しています。データの保存や外部送信は行いません。"
                        val input = query
                        val inputKeyword = keyword
                        val selectedGenre = requireNotNull(genreKey)
                        scope.launch {
                            try {
                                // 押した瞬間に取得済みページをコピーし、古い一覧は使用しない。
                                val texts = ExistingRoomSnapshot.select(
                                    app.roomLists.observations.value.values, System.currentTimeMillis(), selectedGenre,
                                )
                                currentCount = texts.size
                                val evaluated = evaluateExistingSnapshot(model, texts, input, inputKeyword)
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
                    "検索文・募集文・ベクトルをファイルやログには保存しません。コピーした募集文だけは端末のクリップボードに残ります。入室・通知・巡回には反映しません。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            result?.let { value ->
                QuietPanel {
                    val assessment = value.assessment
                    Text("照合結果とAI参考順位", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "対象 " + value.corpusSize + "件 / モデル初期化 " +
                            value.initializationMs + "ms / ベクトル計算 " +
                            value.embeddingMs + "ms / 全体 " + value.totalMs + "ms",
                    )
                    Text("埋め込み次元 " + value.vectorDiagnostics.dimensions +
                        " / 文書ベクトル指紋の種類 " + value.vectorDiagnostics.distinctDocumentFingerprints +
                        " / " + value.corpusSize + "件")
                    if (value.corpusSize > 1 && value.vectorDiagnostics.distinctDocumentFingerprints == 1) {
                        Text("注意: 文書ベクトルの指紋が全件同じです。モデル／文章の重複を確認してください。")
                    }
                    Text("文字列照合: " + (assessment.keyword ?: "未設定（希望文が長いため自動設定なし）"))
                    if (assessment.keyword != null) {
                        Text(
                            "募集文の文字列一致 " + assessment.confirmed.size +
                                "件 / AI上位10件に含まれた文字列一致 " + assessment.aiTop10KeywordHits + "件",
                        )
                        if (assessment.confirmed.isEmpty()) {
                            Text("文字列一致する募集文はありません。AIだけの候補を一致結果として扱いません。")
                        } else {
                            Text("文字列一致した募集文（AIスコア順・最大10件）", style = MaterialTheme.typography.titleSmall)
                            assessment.confirmed.take(10).forEachIndexed { index, ranked ->
                                Text(
                                    (index + 1).toString() + ". " + ranked.text +
                                        "\nコサイン類似度 " + String.format(Locale.ROOT, "%.4f", ranked.similarity),
                                )
                                OutlinedButton(
                                    onClick = { copyPublicMessage(context, ranked.text) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("募集文をコピー") }
                            }
                        }
                    } else {
                        Text("文字列照合なし。意味検索の順位のみを参考表示します。")
                    }
                    Text(
                        "AI類似度の範囲: " +
                            String.format(Locale.ROOT, "%.4f", assessment.lowestCosine ?: 0.0) +
                            " ～ " + String.format(Locale.ROOT, "%.4f", assessment.highestCosine ?: 0.0),
                    )
                    Text(
                        if (assessment.keyword == null) "AI順位（参考・一致判定なし）"
                        else "文字列不一致のAI候補（参考・関連性未確認）",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    assessment.aiOnly.take(5).forEachIndexed { index, ranked ->
                        Text(
                            (index + 1).toString() + ". " + ranked.text +
                                "\nコサイン類似度 " + String.format(Locale.ROOT, "%.4f", ranked.similarity),
                        )
                        OutlinedButton(
                            onClick = { copyPublicMessage(context, ranked.text) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("募集文をコピー") }
                    }
                    Text(
                        "募集文のコピーは原文そのままです。連絡先等が含まれる場合があるため、共有先はご自身で選んでください。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "コサイン類似度は確率ではありません。参考候補を自動通知・入室判断に使用しません。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
