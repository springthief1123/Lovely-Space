package io.github.springthief1123.lovelyspace.core

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * ジャンル別の部屋一覧ページ（`/g/<genre>/`）を解析する。
 *
 * PC 版レイアウト（`table.rooms` > `tr.roomcol`）を前提にしている。
 * 構造の詳細はプロジェクトのサイト構造メモを参照。
 */
object RoomListParser {

    fun parse(html: String, genreKey: String, page: Int = 1, baseUri: String = ""): RoomListPage {
        val doc = Jsoup.parse(html, baseUri)
        return RoomListPage(
            genreKey = genreKey,
            rooms = parseRooms(doc, genreKey),
            waitingCount = headerCount(doc, "wait"),
            fullCount = headerCount(doc, "fill"),
            page = page,
            lastPage = maxOf(page, lastPage(doc)),
            genreCounts = genreCounts(doc),
            totalRooms = TOTAL_ROOMS.find(doc.text())?.groupValues?.get(1)?.toIntOrNull(),
        )
    }

    private fun parseRooms(doc: Document, genreKey: String): List<Room> =
        doc.select("table.rooms tr.roomcol").mapNotNull { parseRow(it, genreKey) }

    private fun parseRow(row: Element, genreKey: String): Room? {
        val cells = row.children().filter { it.tagName() == "td" }
        if (cells.size < 6) return null
        val (statusCell, actionCell, nameCell, genderCell, ageCell) = cells
        val messageCell = cells[5]

        val form = actionCell.selectFirst("form") ?: return null
        val id = form.selectFirst("input[name=room_id]")?.attr("value")?.toLongOrNull() ?: return null
        val rowGenre = form.selectFirst("input[name=genre_key]")?.attr("value")?.ifBlank { null } ?: genreKey

        val statusText = statusCell.selectFirst("span")?.text()?.trim().orEmpty()
        val status = when {
            statusText.contains("満室") -> RoomStatus.FULL
            statusText.contains("公開") -> RoomStatus.PUBLIC_WAITING
            statusText.contains("待機") -> RoomStatus.WAITING
            else -> return null
        }
        val submit = form.selectFirst("input[type=submit]")
        val action = when {
            submit == null || submit.hasAttr("disabled") -> RoomAction.NONE
            form.attr("action").contains("PublicRoom") -> RoomAction.PEEK
            else -> RoomAction.ENTER
        }

        // 満室の部屋は名前欄が「2ショットチャット中」になる。
        val name = if (nameCell.selectFirst(".heartmark") != null) null else nameCell.text().trim().ifEmpty { null }

        return Room(
            id = id,
            genreKey = rowGenre,
            status = status,
            action = action,
            elapsed = statusCell.selectFirst(".room_time")?.text()?.let(::parseElapsed),
            name = name,
            gender = parseGender(genderCell.text()),
            age = ageCell.text().trim().toIntOrNull(),
            area = areaTag(messageCell),
            message = message(messageCell),
        )
    }

    private fun parseGender(text: String): Gender = when (text.trim()) {
        "男" -> Gender.MALE
        "女" -> Gender.FEMALE
        else -> Gender.UNKNOWN
    }

    /** 募集文の先頭の緑色の地域タグ。 */
    private fun areaTag(cell: Element): String? {
        val container = hiddenMessage(cell) ?: cell
        return container.children().firstOrNull { it.tagName() == "font" && it.hasClass("small") }
            ?.text()?.trim()?.ifEmpty { null }
    }

    private fun message(cell: Element): String {
        val container = (hiddenMessage(cell) ?: cell).clone()
        container.children().firstOrNull { it.tagName() == "font" && it.hasClass("small") }?.remove()
        container.select("span.ad, a.ad").remove()
        return container.text().stripInvisible().trim()
    }

    /** 満室の部屋は広告が表示され、本来の募集文は非表示の span に入っている。 */
    private fun hiddenMessage(cell: Element): Element? =
        cell.select("span.hearttext").firstOrNull { it.attr("style").replace(" ", "").contains("display:none") }

    internal fun parseElapsed(text: String): Duration? {
        val parts = text.trim().split(':').map { it.toIntOrNull() ?: return null }
        return when (parts.size) {
            3 -> parts[0].hours + parts[1].minutes + parts[2].seconds
            2 -> parts[0].minutes + parts[1].seconds
            else -> null
        }
    }

    /** 見出しの「待機中 18」「満室 3」。 */
    private fun headerCount(doc: Document, cls: String): Int? =
        doc.select("nobr").firstNotNullOfOrNull { nobr ->
            if (nobr.selectFirst("span.$cls") == null) return@firstNotNullOfOrNull null
            nobr.ownText().trim().toIntOrNull()
        }

    private fun lastPage(doc: Document): Int =
        doc.select("a[href*=/pageID/]").mapNotNull { PAGE_ID.find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull() ?: 1

    private fun genreCounts(doc: Document): Map<String, Int> {
        val counts = linkedMapOf<String, Int>()
        for (a in doc.select("a[href*=/g/]")) {
            val key = GENRE_KEY.find(a.attr("href"))?.groupValues?.get(1) ?: continue
            val sub = a.nextElementSibling()?.takeIf { it.hasClass("sub") } ?: continue
            val n = COUNT.find(sub.text())?.groupValues?.get(1)?.toIntOrNull() ?: continue
            counts.putIfAbsent(key, n)
        }
        return counts
    }

    private val TOTAL_ROOMS = Regex("""総部屋数\s*(\d+)""")
    private val PAGE_ID = Regex("""/pageID/(\d+)/""")
    private val GENRE_KEY = Regex("""/g/([a-z]+)/?$""")
    private val COUNT = Regex("""\((\d+)\)""")
}

/** 募集文に混ざるゼロ幅文字などを取り除く。 */
internal fun String.stripInvisible(): String =
    replace(Regex("[​-‏⁠﻿]"), "")
