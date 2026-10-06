package io.github.springthief1123.lovelyspace.core.chat

import org.jsoup.Jsoup

data class PublicRoomPage(val title: String, val lines: List<ChatLine>, val information: String)

/** 公開ログだけを読む。入室用トークン・発言フォーム・サイト全体の本文は取得しない。 */
object PublicRoomParser {
    fun parse(html: String, url: String): PublicRoomPage {
        val doc = Jsoup.parse(html, url)
        val area = doc.selectFirst("#chatarea")
            ?: throw PublicRoomUnavailableException("公開ルームを表示できません。閉鎖・非公開への変更、またはサイトでの認証が必要な可能性があります。")
        return PublicRoomPage(
            doc.title().substringBefore(" - ").trim(),
            area.select("div.hello").mapNotNull { ChatLogParser.parse(it) },
            doc.selectFirst("#information")?.text().orEmpty(),
        )
    }
}

class PublicRoomUnavailableException(message: String) : java.io.IOException(message)
