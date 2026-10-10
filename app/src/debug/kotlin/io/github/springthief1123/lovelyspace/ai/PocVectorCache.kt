package io.github.springthief1123.lovelyspace.ai

import android.content.Context
import android.os.SystemClock
import java.io.File

/**
 * debug PoCの架空文書キャッシュ。メモリと検証用内部ファイルのみ。
 * 生の募集文・検索文はディスクへ保存しない。
 */
internal object PocVectorCache {
    internal data class LoadResult(val restoredDocuments: Int, val loadMs: Long)

    private var modelIdentity: String? = null
    private var modelSha: String? = null
    private var corpusSha: String? = null
    private val documentVectors = LinkedHashMap<String, FloatArray>(100)

    private fun diskFile(context: Context) = File(File(context.filesDir, "ai-poc"), "poc-vectors-v1.bin")

    /** メモリだけ消す（テスト、次回プロセス起動のシミュレーションに使用）。 */
    @Synchronized
    fun clear() {
        modelIdentity = null
        modelSha = null
        corpusSha = null
        documentVectors.clear()
    }

    /** ディスク上のキャッシュも消す。モデル変更・利用者の明示消去時に使う。 */
    @Synchronized
    fun clearAll(context: Context): Boolean {
        clear()
        return PocDiskCache.erase(diskFile(context))
    }

    fun onDiskBytes(context: Context): Long = diskFile(context).takeIf { it.isFile }?.length() ?: 0

    /**
     * ファイルSHA-256を検証し、モデルまたは合成コーパスの変更で全て無効化。
     * ファイルの全量ハッシュはプロセスごとの初回prepare時にだけ計算。
     * 必ずIOスレッドから呼ぶ。
     */
    @Synchronized
    fun prepare(context: Context, model: File, restoreDisk: Boolean): LoadResult {
        val key = "${model.absolutePath}:${model.length()}:${model.lastModified()}"
        if (modelIdentity == key) return LoadResult(0, 0L)

        val start = SystemClock.elapsedRealtime()
        documentVectors.clear()
        modelIdentity = null
        val sha = PocDiskCache.hashFile(model)
        val corpus = PocDiskCache.hashString(
            PocCorpus.all.withIndex().joinToString("\u0000") { (i, s) -> i.toString() + ":" + s },
        )
        modelSha = sha
        corpusSha = corpus
        if (restoreDisk) {
            val entries = PocDiskCache.read(diskFile(context), sha, corpus)
            for (text in PocCorpus.all) {
                entries[PocDiskCache.hashString(text)]?.let { documentVectors[text] = it }
            }
        }
        modelIdentity = key
        return LoadResult(documentVectors.size, SystemClock.elapsedRealtime() - start)
    }

    @Synchronized
    fun persist(context: Context) {
        val sha = modelSha ?: return
        val corpus = corpusSha ?: return
        val entries = LinkedHashMap<String, FloatArray>(documentVectors.size)
        for ((text, vec) in documentVectors) entries[PocDiskCache.hashString(text)] = vec
        PocDiskCache.write(diskFile(context), sha, corpus, entries)
    }

    @Synchronized
    fun get(document: String): FloatArray? = documentVectors[document]

    @Synchronized
    fun put(document: String, vector: FloatArray) {
        require(document in PocCorpus.all) { "合成文書以外はキャッシュしません" }
        documentVectors[document] = vector.copyOf()
    }

    @Synchronized
    fun size(): Int = documentVectors.size
}
