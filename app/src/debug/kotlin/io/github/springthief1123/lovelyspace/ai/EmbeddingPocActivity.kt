package io.github.springthief1123.lovelyspace.ai

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import io.github.springthief1123.lovelyspace.ui.components.QuietPanel
import io.github.springthief1123.lovelyspace.ui.components.QuietTopBar
import io.github.springthief1123.lovelyspace.ui.theme.LovelySpaceTheme
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** debug APKのみに存在する実機向け検証画面。通信・通知・実部屋データは使用しない。 */
class EmbeddingPocActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LovelySpaceTheme { EmbeddingPocScreen(onClose = ::finish) }
        }
    }
}

private val fakeMessages = listOf(
    "寝るまでのんびりお話ししませんか",
    "まったり雑談、気軽にどうぞ",
    "5分だけ暇つぶししよう",
    "ゲームの攻略情報を教え合いませんか",
    "ゆっくり映画の感想を話したいです",
)

private const val MODEL_NAME = "embeddinggemma-2-text-270m.litertlm"
private const val MIN_MODEL_BYTES = 1_000_000L
private const val MAX_MODEL_BYTES = 600_000_000L
private const val QUERY_PREFIX = "task: search query | text: "
private const val DOCUMENT_PREFIX = "task: search result | text: "

private fun modelFile(context: Context) = File(File(context.filesDir, "ai-poc"), MODEL_NAME)

private fun importModel(context: Context, uri: Uri): Long {
    val resolver = context.contentResolver
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
    require(name != null && name.endsWith(".litertlm", ignoreCase = true)) {
        "LiteRT-LM形式（.litertlm）のファイルを選択してください"
    }

    val target = modelFile(context)
    check(target.parentFile?.isDirectory == true || target.parentFile?.mkdirs() == true) {
        "モデルの保存場所を作成できません"
    }
    val temporary = File(target.parentFile, MODEL_NAME + ".partial")
    var size = 0L
    try {
        val input = resolver.openInputStream(uri) ?: error("ファイルを読み込めません")
        input.use { source ->
            temporary.outputStream().buffered().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    size += read
                    require(size <= MAX_MODEL_BYTES) { "選択したファイルが大きすぎます" }
                    output.write(buffer, 0, read)
                }
            }
        }
        require(size >= MIN_MODEL_BYTES) { "モデルファイルが小さすぎます" }
        if (target.exists()) check(target.delete()) { "以前のモデルを削除できません" }
        check(temporary.renameTo(target)) { "モデルを保存できません" }
        return size
    } finally {
        temporary.delete()
    }
}

private data class Benchmark(
    val results: List<SemanticRanking.Result>,
    val initializationMs: Long,
    val embeddingMs: Long,
)

private fun evaluate(model: File, query: String): Benchmark {
    check(model.isFile && model.length() >= MIN_MODEL_BYTES) { "先にモデルを取り込んでください" }
    val config = EmbeddingEngineConfig(modelPath = model.absolutePath, backend = Backend.CPU())
    val engine = EmbeddingEngine(config)
    try {
        val startInitialize = SystemClock.elapsedRealtime()
        engine.initialize()
        val initializationMs = SystemClock.elapsedRealtime() - startInitialize
        val startEmbeddings = SystemClock.elapsedRealtime()
        // モデルに応じて768次元等のfloatベクトルが返る。SDKにL2正規化を依頼する。
        val options = EmbeddingOptions(normalize = true)
        val queryVector = engine.computeEmbedding(
            listOf(InputData.Text(QUERY_PREFIX + query.trim())), options,
        ).embedding
        val documents = fakeMessages.map { text ->
            text to engine.computeEmbedding(
                listOf(InputData.Text(DOCUMENT_PREFIX + text.trim())), options,
            ).embedding
        }
        val embeddingMs = SystemClock.elapsedRealtime() - startEmbeddings
        return Benchmark(SemanticRanking.rank(queryVector, documents), initializationMs, embeddingMs)
    } finally {
        // initialize()失敗時はclose()が例外を投げるSDK実装なので、成功時のみ解放。
        if (engine.isInitialized()) engine.close()
    }
}

@Composable
private fun EmbeddingPocScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val model = remember(context) { modelFile(context) }
    var modelPresent by remember { mutableStateOf(model.isFile && model.length() >= MIN_MODEL_BYTES) }
    var query by rememberSaveable { mutableStateOf("落ち着いて、長めに雑談できる部屋") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("モデルは自動取得しません。公式モデルを選択してください。") }
    var benchmark by remember { mutableStateOf<Benchmark?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                val bytes = withContext(Dispatchers.IO) { importModel(context, uri) }
                modelPresent = true
                benchmark = null
                message = "モデルを取り込みました（" + (bytes / (1024 * 1024)) + " MiB）。"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "取り込みに失敗: " + (e.message ?: "ファイルを確認してください")
            } finally {
                busy = false
            }
        }
    }

    Scaffold(topBar = { QuietTopBar(title = "AI意味検索・実機検証", onBack = onClose) }) { insets ->
        Column(
            Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            QuietPanel {
                Text("debug版限定・オフライン評価", style = MaterialTheme.typography.titleMedium)
                Text(
                    "既存の部屋検索や通知とは接続していません。端末内の架空の募集文だけを評価します。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(if (modelPresent) "モデル: 端末内に保存済み" else "モデル: 未取り込み")
                OutlinedButton(
                    onClick = { launcher.launch(arrayOf("*/*")) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (modelPresent) "モデルを入れ替える" else "モデルを選んで取り込む") }
                if (modelPresent) {
                    OutlinedButton(onClick = {
                        if (model.delete()) {
                            modelPresent = false
                            benchmark = null
                            message = "アプリ内のモデルを削除しました。"
                        } else message = "モデルを削除できませんでした。"
                    }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text("モデルを削除する")
                    }
                }
            }

            QuietPanel {
                Text("探したい雰囲気", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("希望する部屋の説明") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    minLines = 2,
                    maxLines = 4,
                )
                Text("テスト用の募集文は5件。個人データは含みません。", style = MaterialTheme.typography.bodySmall)
                Button(
                    onClick = {
                        busy = true
                        benchmark = null
                        message = "CPUでモデルを読み込んで評価しています..."
                        scope.launch {
                            try {
                                val result = withContext(Dispatchers.IO) { evaluate(model, query) }
                                benchmark = result
                                message = "評価完了。通信を使用せず端末内で処理しました。"
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: LinkageError) {
                                message = "端末で推論ライブラリを読み込めません: " + (e.message ?: "互換性を確認してください")
                            } catch (e: Exception) {
                                message = "推論に失敗: " + (e.message ?: "モデルまたは端末の対応状況を確認してください")
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = modelPresent && !busy && query.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("端末内で意味検索") }
                if (busy) CircularProgressIndicator()
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            benchmark?.let { result ->
                QuietPanel {
                    Text("CPU実行結果", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "モデル初期化: " + result.initializationMs + " ms / ベクトル計算（希望文と5件）: " +
                            result.embeddingMs + " ms",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    result.results.forEachIndexed { index, item ->
                        Text(
                            (index + 1).toString() + ". " + item.text +
                                "\nコサイン類似度 " + String.format(Locale.ROOT, "%.4f", item.similarity),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Text(
                        "スコアは確率・安全性・人物の属性を示しません。日本語の順位は実機で検証してください。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
