package io.github.springthief1123.lovelyspace.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * スマホ版の一覧（`/?action=GenreK`・`/GenreK`）を読む。PC 版（[RoomListParser]）は満室の部屋の名前を出さないが、
 * スマホ版は公開の満室にも部屋主の名前を出すので、その名前を補うのに使う（docs/site-protocol.md「スマホ版一覧（GenreK）」）。
 *
 * 部屋ごとの入れ物は無く、`hr` で区切った兄弟ノードが 1 部屋。部屋の番号は入室・覗くリンクの `room_id` からしか分からないので、
 * リンクの無い行（満室の非公開・見出し・ページャ）は読まない。
 */
object GenreKParser {
    /** [rooms] はリンクから番号が分かった部屋（ページの順）。[nextCursor] は「次>」の `bfrid`（この番号より小さい部屋が次のページ）。 */
    data class Page(val rooms: List<Room>, val nextCursor: Long?)

    fun parse(html: String, genreKey: String, baseUri: String = ""): Page {
        val doc = Jsoup.parse(html, baseUri)
        // hr をいちばん多く直接の子に持つ要素が一覧の入れ物。
        val container = doc.select("hr").mapNotNull { it.parent() }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        val rooms = container?.let { segments(it).mapNotNull { nodes -> parseRow(nodes, genreKey) } }.orEmpty()
        val next = doc.selectFirst("a.pagernext[href]")?.attr("href")?.let(::cursor)
        return Page(rooms, next)
    }

    private fun segments(container: Element): List<List<Node>> {
        val result = mutableListOf<MutableList<Node>>(mutableListOf())
        for (node in container.childNodes()) {
            if (node is Element && node.tagName() == "hr") result += mutableListOf<Node>() else result.last() += node
        }
        return result.filter { it.isNotEmpty() }
    }

    private fun parseRow(nodes: List<Node>, genreKey: String): Room? {
        val anchorIndex = nodes.indexOfFirst { it is Element && it.tagName() == "a" && (it.isEntry() || it.isPeek()) }
        if (anchorIndex < 0) return null
        val anchor = nodes[anchorIndex] as Element
        val params = linkParams(anchor.attr("href")) ?: return null
        val id = params["room_id"]?.toLongOrNull() ?: return null
        val full = nodes.take(anchorIndex).any { it is Element && it.tagName() == "font" && it.text().trim() == "満室" }
        val (status, action) = when {
            anchor.isPeek() -> if (full) RoomStatus.FULL to RoomAction.PEEK else return null
            full -> return null
            anchor.text().trim() == "公開待機中" -> RoomStatus.PUBLIC_WAITING to RoomAction.ENTER
            anchor.text().trim() == "待機中" -> RoomStatus.WAITING to RoomAction.ENTER
            else -> return null
        }
        val rest = nodes.drop(anchorIndex + 1)
        val genderIndex = rest.indexOfFirst { it is Element && it.tagName() == "font" && (it.hasClass("male") || it.hasClass("female")) }
        if (genderIndex < 0) return null
        val genderFont = rest[genderIndex] as Element
        val profile = rest.take(genderIndex).joinToString("") { it.plainText() }.stripInvisible().trim()
        val age = AGE.find(profile)
        val name = (age?.groupValues?.get(1) ?: profile).trim().ifEmpty { return null }
        // 地域は性別の後から改行・経過時間の前まで。
        val area = rest.drop(genderIndex + 1)
            .takeWhile { !(it is Element && (it.tagName() == "br" || it.hasClass("listbtime") || it.hasClass("wrapsoft"))) }
            .joinToString("") { it.plainText() }.stripInvisible().trim().ifEmpty { null }
        val message = rest.firstNotNullOfOrNull { node ->
            (node as? Element)?.let { if (it.hasClass("wrapsoft")) it else it.selectFirst("span.wrapsoft") }
        }?.text()?.stripInvisible()?.trim().orEmpty()
        return Room(
            id = id,
            genreKey = params["genre_key"]?.ifBlank { null } ?: genreKey,
            status = status,
            action = action,
            elapsed = null,
            name = name,
            gender = when {
                genderFont.hasClass("male") -> Gender.MALE
                genderFont.hasClass("female") -> Gender.FEMALE
                else -> Gender.UNKNOWN
            },
            age = age?.groupValues?.get(2)?.toIntOrNull(),
            area = area,
            message = message,
        )
    }

    private fun Element.isEntry() = attr("href").contains("/PreEnterRoom")
    private fun Element.isPeek() = attr("href").contains("/PublicRoom")

    private fun Node.plainText(): String = when (this) {
        is TextNode -> text()
        is Element -> text()
        else -> ""
    }

    private fun linkParams(href: String): Map<String, String>? {
        val url = (if (href.startsWith("/")) "https://example.invalid$href" else href).toHttpUrlOrNull() ?: return null
        return url.queryParameterNames.associateWith { url.queryParameter(it).orEmpty() }
    }

    private fun cursor(href: String): Long? = linkParams(href)?.get("bfrid")?.toLongOrNull()

    /** 名前の末尾の「(28)」だけを年齢とみなす。名前そのものに括弧があっても切らない。 */
    private val AGE = Regex("""^(.*)\((\d{1,3})\)$""")
}
