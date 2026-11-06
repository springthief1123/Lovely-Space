package io.github.springthief1123.lovelyspace.core.chat

import io.github.springthief1123.lovelyspace.core.Genres
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** WebViewからの引継ぎを、入室・作成・再入室で別々に検証する。 */
class ChatRoomHandover private constructor(
    val genreKey: String,
    private val expectedHost: String?,
    private val expectedRoomId: Long?,
    private val requiresObservedGenre: Boolean,
) {
    fun acceptsPage(url: String): Boolean {
        val page = url.toHttpUrlOrNull() ?: return false
        return acceptsHost(page) && page.queryParameterValues("genre_key").let { it.isEmpty() || it == listOf(genreKey) }
    }

    fun fromUrl(url: String): ChatRoomRef? {
        val page = url.toHttpUrlOrNull() ?: return null
        if (!acceptsHost(page) || page.encodedPath != "/2shot.php") return null
        val id = page.queryParameterValues("room_id").singleOrNull()?.toLongOrNull() ?: return null
        val pwd = page.queryParameterValues("pwd").singleOrNull() ?: return null
        val genres = page.queryParameterValues("genre_key")
        if (genres.size > 1) return null
        return fromForm(page.host, id, pwd, genres.singleOrNull())
    }

    internal fun fromForm(host: String, roomId: Long, pwd: String, observedGenre: String?): ChatRoomRef? {
        if (Genres[genreKey] == null || roomId <= 0 || pwd.isBlank()) return null
        if (host !in siteHosts || (expectedHost != null && host != expectedHost)) return null
        if (expectedRoomId != null && roomId != expectedRoomId) return null
        if (observedGenre != null && observedGenre != genreKey) return null
        if (requiresObservedGenre && observedGenre != genreKey) return null
        return ChatRoomRef(host, roomId, pwd, genreKey)
    }

    private fun acceptsHost(url: HttpUrl): Boolean = url.scheme == "https" && url.port == 443 &&
        url.username.isEmpty() && url.password.isEmpty() && url.host in siteHosts &&
        (expectedHost == null || url.host == expectedHost)

    companion object {
        private val siteHosts = Genres.all.map { it.host }.toSet()
        fun entry(genreKey: String, host: String, roomId: Long) = ChatRoomHandover(genreKey, host, roomId, false)
        fun create(genreKey: String) = ChatRoomHandover(genreKey, Genres[genreKey]?.host ?: "", null, false)
        // 再入室はルートホストへ転送される場合もある。ホストだけからジャンルを決めず、URLまたはフォームのgenre_keyを照合する。
        fun reentry(genreKey: String) = ChatRoomHandover(genreKey, null, null, true)
    }
}
