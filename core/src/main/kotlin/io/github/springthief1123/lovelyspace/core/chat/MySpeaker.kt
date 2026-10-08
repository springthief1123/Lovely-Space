package io.github.springthief1123.lovelyspace.core.chat

/**
 * ログの発言が自分のものかを見分ける。
 *
 * まだ発言していないうちは、発言欄の前に出る自分の名前（[ChatPage.myName]）とログの名前欄の一致で判断する。
 * 名前欄は表記（前後の空白・トリップなど）が発言欄の名前と食い違うことがあるので、
 * 自分の発言の応答に含まれた同じ本文の行から、ログでの自分の名前を覚える。
 * 覚えた後は覚えた名前だけを使う。発言欄の名前（例「タロウ」）のままでは、
 * トリップ付きの自分（「タロウ◆…」）と同名でトリップなしの相手を区別できないため。
 */
data class MySpeaker(val formName: String? = null, val learned: Set<String> = emptySet()) {
    fun isMine(line: ChatLine): Boolean {
        val speaker = line.speaker?.let(::key) ?: return false
        return if (learned.isNotEmpty()) speaker in learned else speaker == formName
    }

    /**
     * 自分の発言 [sent] を送った応答の新着 [lines] から、ログでの自分の名前を覚える。
     * 相手の発言とたまたま同じ本文でも取り違えないよう、送信の応答に含まれた行だけを見る。
     */
    fun learnFromSent(sent: String, lines: List<ChatLine>): MySpeaker {
        val text = normalizeText(sent)
        val speaker = lines.lastOrNull { !it.isNotice && normalizeText(it.text) == text }?.speaker ?: return this
        return if (key(speaker) in learned) this else copy(learned = learned + key(speaker))
    }

    /** 開き直したページの発言欄の名前 [myName] に置き換え、覚えたログでの名前は引き継ぐ。 */
    fun withFormName(myName: String?): MySpeaker = copy(formName = of(myName).formName)

    companion object {
        fun of(myName: String?): MySpeaker = MySpeaker(myName?.let(::key)?.takeIf { it.isNotEmpty() })

        /**
         * 名前の比較では前後の空白（ノーブレークスペースを含む）だけを無視する。
         * 名前の途中の空白は本人が入れたものなので区別する（「山田」と「山 田」は別人）。
         */
        private fun key(name: String) = name.trim { it.isWhitespace() || it == ' ' }
        private fun normalizeText(text: String) = text.lines().joinToString("\n") { it.trim() }.trim()
    }
}
