package io.github.springthief1123.lovelyspace.ai

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * debugの架空文章だけを対象とした、内容検証付きのベクトル永続キャッシュ。
 * 文章そのもの／検索文／URL等は一切書き込まない。将来の本番データには流用しない。
 */
internal object PocDiskCache {
    private const val MAGIC = "LOVELY_POC_V1"
    private const val LIMIT_BYTES = 2_000_000L
    private const val MAX_DOCUMENTS = 100
    private const val MAX_DIMENSIONS = 2048
    private const val DIGEST_BYTES = 32

    fun hashFile(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val chunk = ByteArray(128 * 1024)
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                md.update(chunk, 0, n)
            }
        }
        return md.digest().toHex()
    }

    fun hashString(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).toHex()

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /**
     * 有効なデータだけ返す。壊れたファイル・別モデル・別文章セットは安全にキャッシュミス扱い。
     * フォーマットの上限を検査し、任意の大きな入力をヒープへ割り当てない。
     */
    fun read(file: File, modelSha: String, corpusSha: String): Map<String, FloatArray> {
        if (!file.isFile || file.length() !in (DIGEST_BYTES + 1L)..LIMIT_BYTES) return emptyMap()
        return try {
            val bytes = file.readBytes()
            val payload = bytes.copyOfRange(0, bytes.size - DIGEST_BYTES)
            val expected = bytes.copyOfRange(bytes.size - DIGEST_BYTES, bytes.size)
            val actual = MessageDigest.getInstance("SHA-256").digest(payload)
            if (!MessageDigest.isEqual(expected, actual)) return emptyMap()
            DataInputStream(ByteArrayInputStream(payload)).use { input ->
                if (input.readUTF() != MAGIC ||
                    input.readUTF() != modelSha ||
                    input.readUTF() != corpusSha
                ) return emptyMap()
                val count = input.readInt()
                if (count !in 0..MAX_DOCUMENTS) return emptyMap()
                val result = LinkedHashMap<String, FloatArray>(count)
                var dim = 0
                repeat(count) {
                    val docHash = input.readUTF()
                    if (docHash.length != 64 || !docHash.all { c -> c in '0'..'9' || c in 'a'..'f' })
                        return emptyMap()
                    val n = input.readInt()
                    if (n !in 1..MAX_DIMENSIONS || (dim != 0 && n != dim)) return emptyMap()
                    dim = n
                    val vector = FloatArray(n) { input.readFloat() }
                    if (!vector.all { it.isFinite() } || result.put(docHash, vector) != null)
                        return emptyMap()
                }
                if (input.available() != 0) return emptyMap()
                result
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun write(file: File, modelSha: String, corpusSha: String, vectors: Map<String, FloatArray>) {
        require(vectors.size <= MAX_DOCUMENTS)
        require(modelSha.length == 64 && corpusSha.length == 64)
        val dim = vectors.values.firstOrNull()?.size
        require(vectors.values.all {
            it.size in 1..MAX_DIMENSIONS && it.size == dim && it.all(Float::isFinite)
        })
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeUTF(MAGIC)
            output.writeUTF(modelSha)
            output.writeUTF(corpusSha)
            output.writeInt(vectors.size)
            for ((hash, vector) in vectors) {
                require(hash.length == 64 && hash.all { c -> c in '0'..'9' || c in 'a'..'f' })
                output.writeUTF(hash)
                output.writeInt(vector.size)
                vector.forEach(output::writeFloat)
            }
        }
        val body = bytes.toByteArray()
        val checksum = MessageDigest.getInstance("SHA-256").digest(body)
        require(body.size + checksum.size <= LIMIT_BYTES) { "キャッシュが上限を超えました" }
        check(file.parentFile?.isDirectory == true || file.parentFile?.mkdirs() == true)
        val tmp = File(file.parentFile, file.name + ".part")
        try {
            FileOutputStream(tmp).use { stream ->
                stream.write(body)
                stream.write(checksum)
                stream.fd.sync()
            }
            Files.move(
                tmp.toPath(), file.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            tmp.delete()
        }
    }

    fun erase(file: File): Boolean = !file.exists() || file.delete()
}
