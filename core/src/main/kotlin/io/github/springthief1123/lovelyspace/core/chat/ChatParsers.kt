package io.github.springthief1123.lovelyspace.core.chat

import io.github.springthief1123.lovelyspace.core.chat.JsAssignments.Value
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.time.LocalTime

/** ログ 1 行（`div.hello > table > tr > td(名前) td.usume td(本文 + small.choiusu)`）の解析。 */
object ChatLogParser {
    private const val NOTICE_NAME = "おしらせ"
    private val TIME = Regex("""\((\d{1,2}):(\d{2}):(\d{2})\)""")

    fun parseFragment(html: String, baseUri: String = ""): ChatLine? =
        parse(Jsoup.parseBodyFragment(html, baseUri).body())

    /** [root] 自身か、その中の最初の `div.hello` を 1 行として読む。 */
    fun parse(root: Element): ChatLine? {
        val row = root.selectFirst("tr") ?: return null
        val cells = row.select("> td")
        if (cells.size < 3) return null
        val nameCell = cells[0]
        val bodyCell = cells[2].clone()
        val name = nameCell.text().stripInvisible().trim()
        val time = bodyCell.selectFirst("small.choiusu")?.let { small ->
            small.remove()
            TIME.find(small.text())?.destructured?.let { (h, m, s) ->
                runCatching { LocalTime.of(h.toInt(), m.toInt(), s.toInt()) }.getOrNull()
            }
        }
        val images = bodyCell.select("img[src]").map { it.absUrl("src").ifEmpty { it.attr("src") } }
        return ChatLine(
            speaker = name.takeUnless { it == NOTICE_NAME || it.isEmpty() },
            speakerIsFemale = nameCell.selectFirst("font.chatcolor_f") != null,
            text = plainText(bodyCell).stripInvisible().trim(),
            time = time,
            imageUrls = images,
        )
    }

    /** `<br>` を改行にしたテキスト。末尾の `&nbsp;`（時刻の前の空白）は落とす。 */
    private fun plainText(el: Element): String {
        val sb = StringBuilder()
        fun walk(node: Node) {
            when (node) {
                is TextNode -> sb.append(node.wholeText)
                is Element -> if (node.normalName() == "br") sb.append('\n') else node.childNodes().forEach(::walk)
            }
        }
        el.childNodes().forEach(::walk)
        return sb.toString().replace(' ', ' ').lines().joinToString("\n") { it.trimEnd() }
    }
}

/** `2shot.php`（待機画面・チャット画面）の初回表示の解析。 */
object ChatPageParser {
    private val ROOM_VARS = Regex("""gRoomVars\s*=\s*\{(.*?)\}""", RegexOption.DOT_MATCHES_ALL)
    private val OBJECT_FIELD = Regex("""(\w+)\s*:\s*("(?:[^"\\]|\\.)*"|-?\d+|true|false|null)""")
    private val RELOAD_INI = Regex("""gReloadCountTimeIni\s*=\s*(\d+)""")
    private val RELOAD_MAX = Regex("""gMaxCountTime\s*=\s*(\d+)""")

    fun parse(html: String, room: ChatRoomRef): ChatPage {
        val doc = Jsoup.parse(html, room.pageUrl)
        val vars = ROOM_VARS.find(html)?.groupValues?.get(1)?.let { body ->
            OBJECT_FIELD.findAll(body).associate { it.groupValues[1] to it.groupValues[2].trim('"') }
        }.orEmpty()
        val state = ChatState(
            fromSize = vars["fromsize"]?.toLongOrNull() ?: 0,
            mugonLimitSeconds = vars["mugonLimit"]?.toIntOrNull(),
            roomLimitSeconds = vars["roomLimit"]?.toIntOrNull(),
            isFilledRoom = vars["isFilledRoom"] == "1",
            loadAverage = vars["loadAverage"]?.toIntOrNull() ?: 0,
            reloadIntervalInitialSeconds = RELOAD_INI.findAll(html).lastOrNull()?.groupValues?.get(1)?.toIntOrNull() ?: 5,
            reloadIntervalMaxSeconds = RELOAD_MAX.find(html)?.groupValues?.get(1)?.toIntOrNull() ?: 600,
        )
        val lines = doc.select("#chatarea > div.hello").mapNotNull { ChatLogParser.parse(it) }
        // 発言欄の前の「<b>名前</b> &gt;」が自分の名前。
        val myName = doc.selectFirst("form#chatForm > b")?.text()?.trim()?.takeIf { it.isNotEmpty() }
        val waitingMessage = doc.allElements
            .firstOrNull { it.ownText().trim().startsWith("待機メッセージ") }
            ?.let { it.text().substringAfter("待機メッセージ").trimStart(':', '：', ' ').trim() }
            ?.takeIf { it.isNotEmpty() }
        return ChatPage(
            room = room,
            title = doc.title().substringBefore(" - ").trim(),
            myName = myName,
            // _auth が無い場合は、作成者だけに出る「部屋を閉鎖」フォームで判断する。
            isOwner = vars["_auth"]?.let { it == "owner" }
                ?: (doc.selectFirst("input[name=shotact][value=close]") != null),
            isPublic = vars["_is_public"] == "1",
            lines = lines,
            state = state,
            waitingMessage = waitingMessage,
        )
    }
}

/** `ajax.php` の応答の解析。 */
object ChatUpdateParser {
    fun parse(body: String, previous: ChatState, baseUri: String = ""): ChatUpdate {
        val v = JsAssignments.parse(body)
        val state = previous.copy(
            fromSize = v["gRoomVars.fromsize"].asLong() ?: v["size"].asLong() ?: previous.fromSize,
            mugonLimitSeconds = v["gRoomVars.mugonLimit"].asInt() ?: previous.mugonLimitSeconds,
            roomLimitSeconds = v["gRoomVars.roomLimit"].asInt() ?: previous.roomLimitSeconds,
            isFilledRoom = v["gRoomVars.isFilledRoom"].asFlag() ?: previous.isFilledRoom,
            loadAverage = v["gRoomVars.loadAverage"].asInt() ?: previous.loadAverage,
            reloadIntervalInitialSeconds = v["gReloadCountTimeIni"].asInt() ?: previous.reloadIntervalInitialSeconds,
            reloadIntervalMaxSeconds = v["gMaxCountTime"].asInt() ?: previous.reloadIntervalMaxSeconds,
        )
        // サイトは配列の順に画面の先頭へ積むので、配列は古い順。空要素は「表示中のログを全部消す」合図。
        val raw = (v["loglines"] as? Value.Arr)?.items.orEmpty().map { it.asString().orEmpty() }
        val clearAt = raw.indexOfLast { it.isEmpty() }
        val lines = raw.drop(clearAt + 1).mapNotNull { ChatLogParser.parseFragment(it, baseUri) }
        val notShowRom = v["not_show_rom"].asFlag()
        return ChatUpdate(
            state = state,
            newLines = lines,
            clearLog = clearAt >= 0,
            someoneEntered = v["aj_alert_in"].asFlag() ?: false,
            guestLeft = v["aj_guest_off"].asFlag() ?: false,
            canBanGuest = v["aj_can_ban_guest"].asFlag() ?: false,
            endMessage = v["die_msg"].asString()?.let { Jsoup.parse(it).text().trim() }?.takeIf { it.isNotEmpty() },
            information = v["information"].asString().orEmpty(),
            romCount = if (notShowRom == true) null else v["rom_count"].asInt(),
            isPublic = v["aj_is_public"].asFlag(),
        )
    }
}

/** 入室前画面（`/PreEnterRoom`）の解析。 */
object EntryFormParser {
    fun parse(html: String, host: String, baseUri: String): EntryForm? {
        val doc = Jsoup.parse(html, baseUri)
        val form = doc.selectFirst("form[name=entry]") ?: doc.selectFirst("form:has(input[name=shotact][value=entry])") ?: return null
        fun hidden(name: String) = form.selectFirst("input[name=$name]")?.attr("value").orEmpty()
        val roomId = hidden("room_id").toLongOrNull() ?: return null
        val pwd = hidden("pwd").takeIf { it.isNotEmpty() } ?: return null
        // 紹介文は「が 待機中 です」を含む div、募集文はその次の div。
        val intro = form.select("div").firstOrNull { it.ownText().contains("が") && it.text().contains("待機中") }
        val message = intro?.nextElementSibling()?.takeIf { it.normalName() == "div" }?.text().orEmpty()
        val captcha = doc.selectFirst(".cf-turnstile, .h-captcha, .g-recaptcha, [data-sitekey]") != null ||
            html.contains("challenges.cloudflare.com/turnstile") || html.contains("hcaptcha.com")
        return EntryForm(
            host = host,
            roomId = roomId,
            genreKey = hidden("genre_key"),
            pwd = pwd,
            hostDescription = intro?.text()?.stripInvisible()?.trim().orEmpty(),
            waitingMessage = message.stripInvisible().trim(),
            requiresCaptcha = captcha,
            defaultName = form.selectFirst("input[name=name]")?.attr("value")?.trim().orEmpty(),
        )
    }

    /** 入室に失敗して画面が返ってきたときの、利用者向けの説明文。 */
    fun errorMessage(html: String): String {
        val doc = Jsoup.parse(html)
        val candidates = doc.select(".error, .alert, .caution, font[color=red], font[color=#ff0000], span.red")
        return candidates.firstOrNull { it.text().isNotBlank() }?.text()?.trim()
            ?: doc.title().ifBlank { "入室できませんでした" }
    }
}

private val INVISIBLE = Regex("[​-‍⁠﻿]")
internal fun String.stripInvisible(): String = replace(INVISIBLE, "")
