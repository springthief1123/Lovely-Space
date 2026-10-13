package io.github.springthief1123.lovelyspace.ai

/**
 * 実募集文の共有下書き用。通信も保存もしない。
 * 固有の言い回し・氏名・地名等の検出は保証できないため、必ず利用者が編集して確認する。
 */
internal object ExistingRoomShareDraft {
    private val url = Regex("""(?i)(?:https?://|www\.)[^\s<「」]+""")
    private val email = Regex("""(?i)[a-z0-9._%+\-]+@[a-z0-9.\-]+\.[a-z]{2,}""")
    private val siteSecrets = Regex("""(?i)\b(?:room_id|pwd|cookie|session_id)\s*[:=]\s*[^\s,;　]+""")
    private val account = Regex(
        """(?i)(LINE|ライン|カカオ(?:トーク)?|Kakao(?:Talk)?|Discord|ディスコード)\s*(?:の)?\s*(?:ID|ＩＤ|ユーザー名|アカウント|垢)\s*[:：=／/]?\s*([a-z0-9_.\-]{3,})""",
    )
    private val handle = Regex("""(?<![\w.])@[a-zA-Z0-9_.\-]{3,}""")
    private val phone = Regex("""(?<!\d)(?:\+?\d[\d \-()]{8,}\d)(?!\d)""")

    /** 機械的な仮伏字。本人の手動確認を省略する目的では使用しない。 */
    fun prepare(message: String): String = message.trim()
        .replace(siteSecrets, "[認証情報削除]")
        .replace(url, "[URL削除]")
        .replace(email, "[メール削除]")
        .replace(account) { m -> m.groupValues[1] + " ID [アカウント削除]" }
        .replace(handle, "[アカウント削除]")
        .replace(phone, "[電話番号削除]")
        .take(240)

    /** 明白な識別子を含む下書きをコピーしないための最低限の再点検。 */
    fun hasRecognizableIdentifiers(message: String): Boolean =
        listOf(url, email, siteSecrets, account, handle, phone).any { it.containsMatchIn(message) }

    fun canCopy(message: String, reviewed: Boolean): Boolean =
        reviewed && message.isNotBlank() && message.length <= 240 && !hasRecognizableIdentifiers(message)
}
