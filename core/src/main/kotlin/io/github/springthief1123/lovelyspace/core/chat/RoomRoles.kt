package io.github.springthief1123.lovelyspace.core.chat

/**
 * 公開ルームを覗くときに、部屋主（作成者）と入室者の発言を見分ける。
 *
 * ログのお知らせ「〇〇(男)さん(…)が新規部屋を作成して待機中です。」から部屋主の名前を、
 * 「〇〇(男)さん(…)が入室しましたので、…」から入室者の名前を覚える。
 * 覗き画面のログは直近の行だけなので、一度覚えた名前は読み直しても引き継ぐ。
 * どちらの名前も分からない間は見分けない（どちらとも決めつけない）。
 * お知らせが流れた後でも、一覧で待機中に見えた部屋主の名前があれば使う（[withListedOwner]）。
 * 表示の左右だけは、最初に発言した人を左、名前の違う人を右に分ける（[isRight]）。
 */
data class RoomRoles(val owner: String? = null, val guest: String? = null) {
    /** お知らせから名前を覚える。[lines] はサイトの表示と同じ新しい順で、新しいお知らせほど優先する。 */
    fun learn(lines: List<ChatLine>): RoomRoles {
        var roles = this
        for (line in lines.asReversed()) {
            if (!line.isNotice) continue
            val match = NOTICE.find(line.text) ?: continue
            val name = match.groupValues[1].let(::key).takeIf { it.isNotEmpty() } ?: continue
            roles = if (match.groupValues[2].startsWith("新規部屋を作成")) roles.copy(owner = name) else roles.copy(guest = name)
        }
        return roles
    }

    /**
     * 一覧で見た部屋主の名前（[name]、満室になる前に見えたもの）を部屋主として覚える。
     * お知らせで部屋主が分かっていればそちらを使う。部屋の ID は使い回されるので、
     * ログ（[lines]）にその名前の発言があり、入室者の名前と違うときだけ使う。
     */
    fun withListedOwner(name: String?, lines: List<ChatLine>): RoomRoles {
        if (owner != null) return this
        val listed = name?.let(::key)?.takeIf { it.isNotEmpty() } ?: return this
        if (guest != null && (matches(listed, guest) || matches(guest, listed))) return this
        val spoke = lines.any { line -> !line.isNotice && line.speaker?.let(::key)?.let { matches(it, listed) } == true }
        return if (spoke) copy(owner = listed) else this
    }

    /** 入室者の発言なら true、部屋主なら false、分からなければ null。 */
    fun isGuest(line: ChatLine): Boolean? {
        val speaker = line.speaker?.let(::key) ?: return null
        val isOwner = owner?.let { matches(speaker, it) } == true
        val isGuest = guest?.let { matches(speaker, it) } == true
        return when {
            isGuest && !isOwner -> true
            isOwner && !isGuest -> false
            // 片方だけ分かっていれば、もう片方は残りの人。
            owner != null && guest == null && !isOwner -> true
            guest != null && owner == null && !isGuest -> false
            else -> null
        }
    }

    /**
     * 吹き出しを右（入室者側）に出すか。役割が分かればそれに従う。
     * 分からなければ、[leftSpeaker]（最初に発言した人、[firstSpeaker]）と名前が違う発言を右に出す。
     */
    fun isRight(line: ChatLine, leftSpeaker: String?): Boolean {
        isGuest(line)?.let { return it }
        val speaker = line.speaker?.let(::key) ?: return false
        val left = leftSpeaker?.let(::key) ?: return false
        return !matches(speaker, left) && !matches(left, speaker)
    }

    /** [lines]（新しい順）で最初に発言した人。一度決めたら [current] を引き継ぎ、左右が入れ替わらないようにする。 */
    fun firstSpeaker(lines: List<ChatLine>, current: String?): String? =
        current ?: lines.lastOrNull { !it.isNotice }?.speaker?.let(::key)?.takeIf { it.isNotEmpty() }

    private companion object {
        /** 名前の後に任意で (年齢)・(性別) が付き、「さん」、端末と一時 ID の括弧が続く。 */
        val NOTICE = Regex("""^(.+?)(?:\(\d+\))?(?:\((?:男|女)\))?さん(?:\([^)]*\))?が(新規部屋を作成して待機中です|入室しました)""")

        fun key(name: String) = name.trim { it.isWhitespace() || it == ' ' }

        /** お知らせの名前にはトリップが付かないことがあるので、ログの「名前◆…」も同じ人とみなす。 */
        fun matches(speaker: String, name: String) = speaker == name || speaker.startsWith("$name◆")
    }
}
