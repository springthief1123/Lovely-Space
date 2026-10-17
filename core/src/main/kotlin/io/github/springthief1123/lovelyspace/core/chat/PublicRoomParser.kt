package io.github.springthief1123.lovelyspace.core.chat

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

data class PublicRoomPage(val title: String, val lines: List<ChatLine>, val information: String)

/** 公開ログだけを読む。入室用トークン・発言フォーム・サイト全体の本文は取得しない。 */
object PublicRoomParser {
    private const val DESCRIBE_LENGTH = 40

    /**
     * [finalUrl] は転送された後の URL（転送されなければ null）。読めなかったときの説明に、転送先のパスだけを入れる。
     */
    fun parse(html: String, url: String, finalUrl: String? = null): PublicRoomPage {
        val doc = Jsoup.parse(html, url)
        val area = doc.selectFirst("#chatarea")
        // #chatarea の無い形でも、発言の行（div.hello）があればそれを公開ログとして読む。
        val hello = (area ?: doc).select("div.hello")
        if (area == null && hello.isEmpty()) throw PublicRoomUnavailableException(
            "公開ルームを表示できません。閉鎖・非公開への変更、またはサイトでの認証が必要な可能性があります。",
            describePage(doc, url, finalUrl),
        )
        return PublicRoomPage(
            doc.title().substringBefore(" - ").trim(),
            hello.mapNotNull { ChatLogParser.parse(it) },
            doc.selectFirst("#information")?.text().orEmpty(),
        )
    }

    /**
     * 会話が枠（frame / iframe）の中にあるページなら、その枠の URL。同じホストで同じ部屋（room_id）を指す枠だけを返す。
     * ブラウザが枠を読むのと同じく、アプリもこの URL を 1 回だけ読む。広告などの別ホストの枠はたどらない。
     */
    fun chatFrameUrl(html: String, url: String, roomId: Long): String? {
        val doc = Jsoup.parse(html, url)
        if (doc.selectFirst("#chatarea, div.hello") != null) return null
        val base = url.toHttpUrlOrNull() ?: return null
        return doc.select("frame[src], iframe[src]").asSequence()
            .mapNotNull { base.resolve(it.attr("src")) }
            .firstOrNull { it.scheme == "https" && it.host == base.host && it.queryParameter("room_id") == roomId.toString() && it != base }
            ?.toString()
    }

    /**
     * 読めなかったページが何だったのかを短く表す。題名（無ければ本文の先頭）・転送先のパス・枠とフォームの数・本文の長さ。
     * 本家が別のページ（認証・閉鎖・一覧への転送など）を返したのかを、利用者の画面から見分けるため。
     */
    private fun describePage(doc: Document, url: String, finalUrl: String?): String {
        val text = doc.title().trim().ifEmpty { doc.body()?.text()?.trim().orEmpty() }
        val head = if (text.length > DESCRIBE_LENGTH) text.take(DESCRIBE_LENGTH) + "…" else text
        val requested = url.toHttpUrlOrNull()
        val moved = finalUrl?.toHttpUrlOrNull()?.takeIf { it.encodedPath != requested?.encodedPath || it.host != requested.host }
        return listOfNotNull(
            "「$head」",
            moved?.let { "転送先 ${if (it.host != requested?.host) it.host else ""}${it.encodedPath}" },
            "枠 ${doc.select("frame, iframe").size}",
            "フォーム ${doc.select("form").size}",
            "${doc.outerHtml().length} 文字",
        ).joinToString("・")
    }
}

/** [page] は本家が返したページの短い説明（[PublicRoomParser] が作る）。画面で原因を見分けるために出す。 */
class PublicRoomUnavailableException(message: String, val page: String? = null) :
    java.io.IOException(if (page == null) message else "$message\n本家の応答: $page")
