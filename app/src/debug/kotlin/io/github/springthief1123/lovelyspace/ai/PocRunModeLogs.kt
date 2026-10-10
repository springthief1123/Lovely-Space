package io.github.springthief1123.lovelyspace.ai

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

/**
 * 旧PSS/CPU性能CSVを壊さず、同じrun_idで結合できる実行モードCSV。
 * 希望文・募集文・モデルパス・端末個体識別情報は含まない。
 */
internal object PocRunModeLogs {
    private const val FILE_NAME = "poc-run-modes-v1.csv"
    private const val HEADER = "utc,run_id,keep_warm,engine_reused,persist_vectors,disk_restored_documents,disk_load_ms,persisted_cache_bytes"
    private const val MAX_BYTES = 512 * 1024L

    private fun file(context: Context) = File(File(context.filesDir, "ai-poc"), FILE_NAME)

    fun size(context: Context): Long = file(context).takeIf { it.isFile }?.length() ?: 0L
    fun delete(context: Context): Boolean = !file(context).exists() || file(context).delete()

    fun append(
        context: Context,
        record: PocLogRecord,
        keepWarm: Boolean,
        reused: Boolean,
        persistVectors: Boolean,
        restoredDocuments: Int,
        diskLoadMs: Long,
        cacheFileBytes: Long,
    ) {
        val row = PocLogs.csv(listOf(
            record.timestampUtc, record.id, keepWarm, reused, persistVectors,
            restoredDocuments, diskLoadMs, cacheFileBytes,
        ))
        val target = file(context)
        check(target.parentFile?.isDirectory == true || target.parentFile?.mkdirs() == true)
        check(size(context) + row.toByteArray().size + HEADER.length <= MAX_BYTES) {
            "実行条件ログが上限に達しました"
        }
        OutputStreamWriter(FileOutputStream(target, true), Charsets.UTF_8).use { stream ->
            if (target.length() == 0L) stream.write(HEADER + "\n")
            stream.write(row)
        }
    }

    fun export(context: Context, uri: Uri) {
        val source = file(context)
        check(source.isFile) { "保存済みの実行条件ログがありません" }
        val out = context.contentResolver.openOutputStream(uri) ?: error("保存先を開けません")
        out.use { target -> source.inputStream().use { it.copyTo(target) } }
    }
}
