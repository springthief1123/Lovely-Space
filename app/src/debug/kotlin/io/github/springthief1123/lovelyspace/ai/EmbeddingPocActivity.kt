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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
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
import io.github.springthief1123.lovelyspace.ui.components.QuietPanel
import io.github.springthief1123.lovelyspace.ui.components.QuietTopBar
import io.github.springthief1123.lovelyspace.ui.theme.LovelySpaceTheme
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** debug APKのみに存在する実機向け検証画面。通信・通知・実部屋データは使用しない。 */
class EmbeddingPocActivity : ComponentActivity() {
    override fun onStop() {
        super.onStop()
        PocModelSession.releaseAsync()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) PocModelSession.releaseAsync()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LovelySpaceTheme { EmbeddingPocScreen(onClose = ::finish) }
        }
    }
}

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
    val corpusSize: Int,
    val keywordMatches: Int,
    val requireAll: Boolean,
    val initializationMs: Long,
    val embeddingMs: Long,
    val rankingMs: Long,
    val cacheHits: Int,
    val cacheMisses: Int,
    val restoredFromDisk: Int,
    val diskLoadMs: Long,
    val engineReused: Boolean,
    val diskSaveError: String?,
    val resources: PocResourceReport,
    val loggingError: String?,
)

private data class PocWork(
    val results: List<SemanticRanking.Result>,
    val initializationMs: Long,
    val embeddingMs: Long,
    val rankingMs: Long,
    val cacheHits: Int,
    val cacheMisses: Int,
    val diskLoaded: PocVectorCache.LoadResult,
    val engineReused: Boolean,
    val diskSaveError: String?,
)

/**
 * LiteRT-LM 0.18.0 は Kotlin 2.4 でビルドされており、既存の Kotlin 2.1 コンパイラで
 * SDKのクラスを直接参照するとメタデータ互換性エラーになる。PoCはdebugRuntimeOnlyと
 * リフレクションで型付きAPI境界をこのクラス内に隔離する。本番採用時はツールチェーンを更新して置き換える。
 */
internal class DebugEmbeddingEngine(modelPath: String) : AutoCloseable {
    private val engineClass = Class.forName("com.google.ai.edge.litertlm.EmbeddingEngine")
    private val inputTextClass = Class.forName("com.google.ai.edge.litertlm.InputData" + '$' + "Text")
    private val engine: Any

    init {
        val configClass = Class.forName("com.google.ai.edge.litertlm.EmbeddingEngineConfig")
        // @JvmOverloadsによりモデルパスだけのコンストラクタが提供され、backendはCPUが既定値。
        val config = configClass.getConstructor(String::class.java).newInstance(modelPath)
        engine = engineClass.getConstructor(configClass).newInstance(config)
        try {
            engineClass.getMethod("initialize").invoke(engine)
        } catch (e: Exception) {
            // initialize()未成功時はSDK側のclose()が例外になるので呼ばない。
            throw unwrap(e)
        }
    }

    fun embed(text: String): FloatArray {
        val input = inputTextClass.getConstructor(String::class.java).newInstance(text)
        val response = try {
            engineClass.getMethod("computeEmbedding", java.util.List::class.java)
                .invoke(engine, listOf(input))
        } catch (e: Exception) {
            throw unwrap(e)
        }
        return response.javaClass.getMethod("getEmbedding").invoke(response) as FloatArray
    }

    override fun close() {
        engineClass.getMethod("close").invoke(engine)
    }

    private fun unwrap(e: Exception): Exception =
        (e as? java.lang.reflect.InvocationTargetException)?.targetException?.let {
            IllegalStateException(it.message ?: "ネイティブ推論に失敗しました", it)
        } ?: e
}

private suspend fun evaluate(
    context: Context,
    model: File,
    query: String,
    count: Int,
    keywords: List<String>,
    requireAll: Boolean,
    keepWarm: Boolean,
    persistCache: Boolean,
): Benchmark = coroutineScope {
    check(model.isFile && model.length() >= MIN_MODEL_BYTES) { "先にモデルを取り込んでください" }
    val corpus = PocCorpus.take(count)
    val baselineHits = PocKeywordBaseline.matchedCount(corpus, keywords, requireAll)
    val telemetry = PocTelemetry(context)
    telemetry.sample()
    val sampler = launch(Dispatchers.Default) {
        while (isActive) {
            delay(120)
            telemetry.sample()
        }
    }
    val work = try {
        withContext(Dispatchers.IO) {
            val loaded = PocVectorCache.prepare(context, model, persistCache)
            val session = PocModelSession.run(model, keepWarm) { engine ->
                val embedStart = SystemClock.elapsedRealtime()
                val queryVector = engine.embed(QUERY_PREFIX + query.trim())
                var hits = 0
                var misses = 0
                val documents = corpus.map { text ->
                    val vector = PocVectorCache.get(text)
                    if (vector != null) {
                        hits++
                        text to vector
                    } else {
                        val created = engine.embed(DOCUMENT_PREFIX + text.trim())
                        PocVectorCache.put(text, created)
                        misses++
                        text to created
                    }
                }
                val embedMs = SystemClock.elapsedRealtime() - embedStart
                val rankStart = SystemClock.elapsedRealtime()
                val ranked = SemanticRanking.rank(queryVector, documents)
                val rankMs = SystemClock.elapsedRealtime() - rankStart
                Triple(ranked, Triple(embedMs, rankMs, hits), misses)
            }
            val saveError = if (persistCache && session.value.third > 0) {
                runCatching { PocVectorCache.persist(context) }.exceptionOrNull()?.message
            } else null
            PocWork(
                results = session.value.first,
                initializationMs = session.initializationMs,
                embeddingMs = session.value.second.first,
                rankingMs = session.value.second.second,
                cacheHits = session.value.second.third,
                cacheMisses = session.value.third,
                diskLoaded = loaded,
                engineReused = session.reusedEngine,
                diskSaveError = saveError,
            )
        }
    } finally {
        sampler.cancelAndJoin()
        telemetry.sample()
    }
    val resources = telemetry.finish()
    val record = PocLogRecord.new(
        count, requireAll, baselineHits, work.cacheHits, work.cacheMisses,
        work.initializationMs, work.embeddingMs, work.rankingMs,
        work.results.firstOrNull()?.similarity, resources,
    )
    val logError = withContext(Dispatchers.IO) {
        val perfError = runCatching { PocLogs.append(context, record) }.exceptionOrNull()?.message
        val modeError = runCatching {
            PocRunModeLogs.append(
                context, record, keepWarm, work.engineReused, persistCache,
                work.diskLoaded.restoredDocuments, work.diskLoaded.loadMs,
                PocVectorCache.onDiskBytes(context),
            )
        }.exceptionOrNull()?.message
        listOfNotNull(perfError, modeError).joinToString("; ").ifEmpty { null }
    }
    Benchmark(
        work.results, count, baselineHits, requireAll,
        work.initializationMs, work.embeddingMs, work.rankingMs,
        work.cacheHits, work.cacheMisses, work.diskLoaded.restoredDocuments,
        work.diskLoaded.loadMs, work.engineReused, work.diskSaveError,
        resources, logError,
    )
}

/** 正解ラベルを持つ7ケースのバッチ評価。既存の100件の文書ベクトルは再利用する。 */
private suspend fun evaluateQuality(
    context: Context,
    model: File,
    keepWarm: Boolean,
    persistCache: Boolean,
): List<PocQualityResult> = withContext(Dispatchers.IO) {
    check(model.isFile && model.length() >= MIN_MODEL_BYTES)
    PocVectorCache.prepare(context, model, persistCache)
    val result = PocModelSession.run(model, keepWarm) { engine ->
        val documents = PocCorpus.all.map { text ->
            val vector = PocVectorCache.get(text) ?: engine.embed(DOCUMENT_PREFIX + text).also {
                PocVectorCache.put(text, it)
            }
            text to vector
        }
        PocQuality.cases.map { case ->
            val query = engine.embed(QUERY_PREFIX + case.query)
            val indices = SemanticRanking.rank(query, documents).map { it.originalIndex }
            PocQuality.compare(case, indices)
        }
    }.value
    if (persistCache) PocVectorCache.persist(context)
    PocQualityLogs.append(context, result)
    result
}

@Composable
private fun EmbeddingPocScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val model = remember(context) { modelFile(context) }
    var modelPresent by remember { mutableStateOf(model.isFile && model.length() >= MIN_MODEL_BYTES) }
    var query by rememberSaveable { mutableStateOf("落ち着いて、長めに雑談できる部屋") }
    var sampleCount by rememberSaveable { mutableStateOf(5) }
    var keywords by rememberSaveable { mutableStateOf("まったり,のんびり") }
    var requireAll by rememberSaveable { mutableStateOf(false) }
    var keepWarm by rememberSaveable { mutableStateOf(false) }
    var persistVectors by rememberSaveable { mutableStateOf(false) }
    var qualityResults by remember { mutableStateOf<List<PocQualityResult>?>(null) }
    var qualityLogSize by remember { mutableStateOf(PocQualityLogs.size(context)) }
    var modeLogSize by remember { mutableStateOf(PocRunModeLogs.size(context)) }
    var diskSize by remember { mutableStateOf(PocVectorCache.onDiskBytes(context)) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("モデルを選択してください。計測値を端末内のCSVに保存します。") }
    var benchmark by remember { mutableStateOf<Benchmark?>(null) }
    var logSize by remember { mutableStateOf(PocLogs.size(context)) }
    var confirmDelete by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                val bytes = withContext(Dispatchers.IO) {
                    PocModelSession.releaseNow()
                    val copied = importModel(context, uri)
                    check(PocVectorCache.clearAll(context)) { "旧モデルのキャッシュを削除できません" }
                    copied
                }
                diskSize = PocVectorCache.onDiskBytes(context)
                modelPresent = true
                benchmark = null
                qualityResults = null
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
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) { PocLogs.export(context, uri) }
                message = "CSVを書き出しました。検索文や本家のデータは含みません。"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "CSV書き出しに失敗: " + (e.message ?: "保存先を確認してください")
            } finally {
                busy = false
            }
        }
    }

    val modeExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) { PocRunModeLogs.export(context, uri) }
                message = "実行条件CSVを書き出しました。CPU/PSS CSVとrun_idで結合できます。"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                message = "実行条件CSVの書き出しに失敗: " + (e.message ?: "保存先を確認してください")
            } finally { busy = false }
        }
    }

    val qualityExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) { PocQualityLogs.export(context, uri) }
                message = "品質比較のCSVを書き出しました。検索文は保存していません。"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "品質CSVの書き出しに失敗: " + (e.message ?: "保存先を確認してください")
            } finally { busy = false }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("計測ログを削除しますか？") },
            text = { Text("端末内に保存されたCSV記録を削除します。先に書き出しておくことをおすすめします。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    val done = PocLogs.delete(context) && PocRunModeLogs.delete(context)
                    logSize = PocLogs.size(context)
                    modeLogSize = PocRunModeLogs.size(context)
                    message = if (done) "計測ログと実行条件ログを削除しました。" else "計測ログの削除に失敗しました。"
                }) { Text("削除する") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("やめる") }
            },
        )
    }

    Scaffold(topBar = { QuietTopBar(title = "AI意味検索・実機検証", onBack = onClose) }) { insets ->
        Column(
            Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            QuietPanel {
                Text("debug版限定・オフライン評価", style = MaterialTheme.typography.titleMedium)
                Text(
                    "実際の部屋・通信・通知には未接続です。100件すべて架空の募集文です。",
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
                        busy = true
                        scope.launch {
                            try {
                                val deleted = withContext(Dispatchers.IO) {
                                    PocModelSession.releaseNow()
                                    val success = model.delete()
                                    if (success) check(PocVectorCache.clearAll(context))
                                    success
                                }
                                if (deleted) {
                                    modelPresent = false
                                    benchmark = null
                                    qualityResults = null
                                    diskSize = 0L
                                    message = "モデルと旧モデルのキャッシュを削除しました。"
                                } else message = "モデルを削除できませんでした。"
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { message = "削除に失敗: " + e.message }
                            finally { busy = false }
                        }
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
                    label = { Text("希望する部屋の説明（CSVには保存しません）") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    minLines = 2,
                    maxLines = 4,
                )
                Text("検証する架空の募集文の件数", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (size in listOf(5, 30, 100)) {
                        FilterChip(
                            selected = sampleCount == size,
                            onClick = { sampleCount = size },
                            label = { Text(size.toString() + "件") },
                            enabled = !busy,
                        )
                    }
                }
                OutlinedTextField(
                    value = keywords,
                    onValueChange = { keywords = it },
                    label = { Text("参考比較キーワード（カンマ区切り）") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    singleLine = true,
                )
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(
                        checked = requireAll,
                        onCheckedChange = { requireAll = it },
                        enabled = !busy,
                    )
                    Text("すべて含む（AND）・OFFはOR", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "AND/ORは文字列の単純包含による参考比較です。本番の検索エンジンと同一ではありません。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(
                        checked = keepWarm,
                        onCheckedChange = { checked ->
                            keepWarm = checked
                            if (!checked) PocModelSession.releaseAsync()
                        },
                        enabled = !busy,
                    )
                    Text("画面内でCPUモデルを一時再利用する", style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(
                        checked = persistVectors,
                        onCheckedChange = {
                            persistVectors = it
                            // ON/OFF切替時には旧メモリの復元状態をリセットし、次回に正しく読み直す。
                            PocVectorCache.clear()
                        },
                        enabled = !busy,
                    )
                    Text("架空文のベクトルを再起動後も再利用する", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "モデルは背景移行・メモリ不足時に解放します。永続キャッシュは合成文のみで、検索文は保存しません。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("永続キャッシュ: " + (diskSize / 1024) + " KiB", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(
                    onClick = {
                        PocModelSession.releaseAsync()
                        message = "CPUモデルの解放を要求しました。"
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("読み込み済みモデルを解放する") }
                Button(
                    onClick = {
                        val queryNow = query
                        val countNow = sampleCount
                        val termsNow = PocKeywordBaseline.terms(keywords)
                        val allNow = requireAll
                        busy = true
                        benchmark = null
                        message = "CPUで評価中です。100件の初回は時間がかかる場合があります。"
                        scope.launch {
                            try {
                                val warmNow = keepWarm
                                val diskNow = persistVectors
                                val result = evaluate(context, model, queryNow, countNow, termsNow, allNow, warmNow, diskNow)
                                benchmark = result
                                diskSize = PocVectorCache.onDiskBytes(context)
                                logSize = PocLogs.size(context)
                                modeLogSize = PocRunModeLogs.size(context)
                                val warning = listOfNotNull(result.loggingError, result.diskSaveError).joinToString("; ")
                                message = if (warning.isEmpty()) {
                                    "端末内で評価し、計測ログをCSVへ保存しました。"
                                } else {
                                    "評価は成功しましたが、一部の保存に失敗: " + warning
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: LinkageError) {
                                message = "推論ライブラリに問題があります: " + (e.message ?: "互換性を確認してください")
                            } catch (e: Exception) {
                                message = "推論に失敗: " + (e.message ?: "モデルとメモリを確認してください")
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = modelPresent && !busy && query.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("端末内で意味検索・負荷測定") }
                if (busy) CircularProgressIndicator()
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            benchmark?.let { result ->
                QuietPanel {
                    Text("CPU実行・端末負荷", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "初期化 " + result.initializationMs + " ms / ベクトル計算 " + result.embeddingMs +
                            " ms / 並べ替え " + result.rankingMs + " ms / 合計 " +
                            result.resources.elapsedMs + " ms",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "文書ベクトルキャッシュ: " + result.cacheHits + "件再利用 / " +
                            result.cacheMisses + "件新規",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "モデル再利用: " + (if (result.engineReused) "あり（初期化0ms）" else "なし") +
                            " / ディスクから復元 " + result.restoredFromDisk +
                            "件（復元準備 " + result.diskLoadMs + "ms）",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "プロセスCPU時間: " + result.resources.processCpuMs +
                            " ms / 平均コア換算負荷 " +
                            String.format(Locale.ROOT, "%.1f", result.resources.averageCpuCorePercent) + "%",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "アプリPSSメモリ: 終了時 " + (result.resources.finalPssKb / 1024) +
                            " MiB / 観測ピーク " + (result.resources.peakSampledPssKb / 1024) +
                            " MiB / Javaヒープ観測ピーク " +
                            (result.resources.peakSampledHeapKb / 1024) + " MiB",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    val battery = result.resources
                    Text(
                        "バッテリー残量: " + (battery.batteryPercent?.toString() ?: "取得不可") +
                            "% / 電池温度: " + (battery.batteryTemperatureC?.toString() ?: "取得不可") +
                            " ℃（CPU温度ではありません）",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "文字列包含 " + (if (result.requireAll) "AND" else "OR") +
                            " 一致: " + result.keywordMatches + "/" + result.corpusSize + "件",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "意味検索の上位 " + minOf(10, result.results.size) + "件",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    result.results.take(10).forEachIndexed { index, item ->
                        Text(
                            (index + 1).toString() + ". " + item.text +
                                "\nコサイン類似度 " + String.format(Locale.ROOT, "%.4f", item.similarity),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Text(
                        "PSSは120msごとの近似測定。CPU負荷は本アプリのコア換算で、100%を超える場合があります。スコアは確率ではありません。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            QuietPanel {
                Text("日本語検索の品質検証", style = MaterialTheme.typography.titleMedium)
                Text(
                    "7つの固定した架空の検索文で、意味検索と文字列包含の参考ベースラインを比較します。正解ラベルは合成文の分類に基づく暫定値です。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    enabled = modelPresent && !busy,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val warmNow = keepWarm
                        val diskNow = persistVectors
                        busy = true
                        qualityResults = null
                        message = "7つの日本語検索ケースを評価中です..."
                        scope.launch {
                            try {
                                val rows = evaluateQuality(context, model, warmNow, diskNow)
                                qualityResults = rows
                                qualityLogSize = PocQualityLogs.size(context)
                                diskSize = PocVectorCache.onDiskBytes(context)
                                message = "品質検証を完了し、匿名CSVに記録しました。"
                            } catch (e: CancellationException) { throw e }
                            catch (e: LinkageError) {
                                message = "品質評価の推論ライブラリでエラー: " + e.message
                            } catch (e: Exception) {
                                message = "品質評価に失敗: " + e.message
                            } finally { busy = false }
                        }
                    },
                ) { Text("日本語品質を7ケースで評価") }
                qualityResults?.let { rows ->
                    for (row in rows) {
                        Text(row.id + "  意味 P@5 " +
                            String.format(Locale.ROOT, "%.2f", row.semantic.precisionAt5) +
                            " / R@10 " + String.format(Locale.ROOT, "%.2f", row.semantic.recallAt10) +
                            " / MRR@10 " + String.format(Locale.ROOT, "%.2f", row.semantic.mrrAt10),
                            style = MaterialTheme.typography.bodySmall)
                        Text("        文字列 P@5 " +
                            String.format(Locale.ROOT, "%.2f", row.literal.precisionAt5) +
                            " / R@10 " + String.format(Locale.ROOT, "%.2f", row.literal.recallAt10) +
                            " / MRR@10 " + String.format(Locale.ROOT, "%.2f", row.literal.mrrAt10),
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Text("数字は今回の架空セットでの比較指標です。本番の通知判定や確率には使用しません。",
                        style = MaterialTheme.typography.bodySmall)
                }
                Text("品質評価の保存済みCSV: " + (qualityLogSize / 1024) + " KiB",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(
                    onClick = { qualityExportLauncher.launch("lovely-ai-quality.csv") },
                    enabled = !busy && qualityLogSize > 0,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("品質評価のCSVを書き出す") }
            }

            QuietPanel {
                Text("計測ログ", style = MaterialTheme.typography.titleMedium)
                Text(
                    "保存済み: " + (logSize / 1024) + " KiB / アプリ専用領域。実行ごとの要約と約120ms間隔のサンプルです。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "CSVには処理時間、アプリPSS/ヒープ、CPU時間、電池状態、キャッシュ件数だけを記録。検索文・募集文・認証情報・個体識別情報は含みません。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { exportLauncher.launch("lovely-ai-benchmark.csv") },
                    enabled = !busy && logSize > 0,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("計測ログをCSVに書き出す") }
                Text(
                    "実行条件ログ " + (modeLogSize / 1024) +
                        " KiB（モデル再利用・ディスク復元）。性能CSVのrun_idと対応します。",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    onClick = { modeExportLauncher.launch("lovely-ai-run-modes.csv") },
                    enabled = !busy && modeLogSize > 0,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("実行条件ログをCSVに書き出す") }
                OutlinedButton(
                    onClick = {
                        PocVectorCache.clear()
                        message = "メモリ内のベクトルを消去しました。永続キャッシュは残っています。"
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("メモリ内のキャッシュだけを消去") }
                OutlinedButton(
                    onClick = {
                        busy = true
                        scope.launch {
                            try {
                                val success = withContext(Dispatchers.IO) { PocVectorCache.clearAll(context) }
                                diskSize = PocVectorCache.onDiskBytes(context)
                                message = if (success) "メモリ・端末内のベクトルキャッシュを消去しました。" else "永続キャッシュの削除に失敗しました。"
                            } catch (e: Exception) { message = "削除に失敗: " + e.message }
                            finally { busy = false }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("永続キャッシュも含めて消去") }
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    enabled = !busy && logSize > 0,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("保存した計測ログを削除") }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
