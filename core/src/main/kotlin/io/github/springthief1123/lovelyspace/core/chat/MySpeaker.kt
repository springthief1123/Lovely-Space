package io.github.springthief1123.lovelyspace.core.chat

/**
 * ログの発言が自分のものかを見分ける。
 *
 * 基本は発言欄の前に出る自分の名前（[ChatPage.myName]）とログの名前欄の一致で判断する。
 * 名前欄は表記（前後の空白・トリップなど）が発言欄の名前と食い違うことがあるので、
 * 自分の発言の応答に含まれた同じ本文の行から、ログでの自分の名前を覚えて併用する。
 */
data class MySpeaker(val names: Set<String> = emptySet()) {
    fun isMine(line: ChatLine): Boolean = line.speaker?.let { key(it) in names } == true

    /**
     * 自分の発言 [sent] を送った応答の新着 [lines] から、ログでの自分の名前を覚える。
     * 相手の発言とたまたま同じ本文でも取り違えないよう、送信の応答に含まれた行だけを見る。
     */
    fun learnFromSent(sent: String, lines: List<ChatLine>): MySpeaker {
        val text = normalizeText(sent)
        val speaker = lines.lastOrNull { !it.isNotice && normalizeText(it.text) == text }?.speaker ?: return this
        return if (key(speaker) in names) this else copy(names = names + key(speaker))
    }

    companion object {
        fun of(myName: String?): MySpeaker = MySpeaker(setOfNotNull(myName?.let(::key)?.takeIf { it.isNotEmpty() }))

        /**
         * 名前の比較では前後の空白（ノーブレークスペースを含む）だけを無視する。
         * 名前の途中の空白は本人が入れたものなので区別する（「山田」と「山 田」は別人）。
         */
        private fun key(name: String) = name.trim { it.isWhitespace() || it == '\u00A0' }
        private fun normalizeText(text: String) = text.lines().joinToString("\n") { it.trim() }.trim()
    }
}
