package io.github.springthief1123.lovelyspace.ai

import java.io.File

/** debug PoCのプロセス内のみ。端末内の検索文や募集文ベクトルを永続化しない。 */
internal object PocVectorCache {
    private var modelIdentity: String? = null
    private val documentVectors = LinkedHashMap<String, FloatArray>(100)

    @Synchronized
    fun clear() {
        modelIdentity = null
        documentVectors.clear()
    }

    @Synchronized
    fun prepare(model: File) {
        // モデル差し替え時に古い埋め込みを再利用しない。
        val key = "${model.absolutePath}:${model.length()}:${model.lastModified()}"
        if (modelIdentity != key) {
            documentVectors.clear()
            modelIdentity = key
        }
    }

    @Synchronized
    fun get(document: String): FloatArray? = documentVectors[document]

    @Synchronized
    fun put(document: String, vector: FloatArray) {
        documentVectors[document] = vector.copyOf()
    }

    @Synchronized
    fun size(): Int = documentVectors.size
}
