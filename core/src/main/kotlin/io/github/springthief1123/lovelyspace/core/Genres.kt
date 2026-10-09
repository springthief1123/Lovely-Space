package io.github.springthief1123.lovelyspace.core

enum class GenreGroup(val label: String) {
    AREA("地域"),
    ADULT("カテゴリ"),
    GENERAL("一般"),
}

/** ジャンルはサブドメインごとに分かれている（chat. / 2shot.chat. / lr.chat.）。 */
data class Genre(
    val key: String,
    val label: String,
    val host: String,
    val group: GenreGroup,
)

object Genres {
    private const val CHAT = "chat.shalove.net"
    private const val TWO_SHOT = "2shot.chat.shalove.net"
    private const val LR = "lr.chat.shalove.net"

    val all: List<Genre> = listOf(
        Genre("zenkoku", "全国", CHAT, GenreGroup.AREA),
        Genre("hokkaido", "北海道", CHAT, GenreGroup.AREA),
        Genre("tohoku", "東北", CHAT, GenreGroup.AREA),
        Genre("kanto", "関東", CHAT, GenreGroup.AREA),
        Genre("chubu", "中部", CHAT, GenreGroup.AREA),
        Genre("kinki", "近畿", CHAT, GenreGroup.AREA),
        Genre("chugoku", "中国・四国", CHAT, GenreGroup.AREA),
        Genre("kyushu", "九州・沖縄", CHAT, GenreGroup.AREA),
        Genre("imacha", "イメチャ", TWO_SHOT, GenreGroup.ADULT),
        Genre("chah", "チャH", TWO_SHOT, GenreGroup.ADULT),
        Genre("gazo", "画像", CHAT, GenreGroup.ADULT),
        Genre("tel", "通話", TWO_SHOT, GenreGroup.ADULT),
        Genre("kikon", "既婚", TWO_SHOT, GenreGroup.ADULT),
        Genre("jukunen", "熟年", TWO_SHOT, GenreGroup.ADULT),
        Genre("sm", "SM", TWO_SHOT, GenreGroup.ADULT),
        Genre("feti", "フェチ", TWO_SHOT, GenreGroup.ADULT),
        Genre("pocha", "ぽっちゃり", TWO_SHOT, GenreGroup.ADULT),
        Genre("dose", "同性", TWO_SHOT, GenreGroup.ADULT),
        Genre("talk", "雑談", LR, GenreGroup.GENERAL),
        Genre("nonadult", "ノンアダルト", LR, GenreGroup.GENERAL),
        Genre("cosplay", "コスプレ", LR, GenreGroup.GENERAL),
        Genre("game", "ゲーム", LR, GenreGroup.GENERAL),
        Genre("wait", "待ち合わせ", LR, GenreGroup.GENERAL),
    )

    private val byKey = all.associateBy { it.key }

    operator fun get(key: String): Genre? = byKey[key]

    val default: Genre = all.first()
}

/** サイト側の絞り込み（一覧 URL のクエリ）。null は指定なし。 */
data class RoomQuery(
    val genre: Genre,
    val sex: Gender? = null,
    /** 1〜47 都道府県, 48 海外 */
    val prefecture: Int? = null,
    /** "-19", "20-29", ..., "60-" */
    val ageBand: String? = null,
    /** true=公開のみ, false=非公開のみ */
    val publicOnly: Boolean? = null,
    /** true=待機中のみ, false=満室のみ */
    val waitingOnly: Boolean? = null,
    val name: String? = null,
    val message: String? = null,
    val page: Int = 1,
) {
    /** 本家の一覧に渡す絞り込み（パラメータ名と値）。空の条件は含めない。 */
    val siteParams: List<Pair<String, String>> get() = buildList {
        when (sex) {
            Gender.MALE -> add("vsex" to "1")
            Gender.FEMALE -> add("vsex" to "2")
            else -> Unit
        }
        prefecture?.let { add("vpref" to it.toString()) }
        ageBand?.let { add("vyears" to it) }
        publicOnly?.let { add("vnonpub" to if (it) "2" else "1") }
        waitingOnly?.let { add("vwait" to if (it) "1" else "2") }
        name?.takeIf { it.isNotBlank() }?.let { add("srchname" to it) }
        message?.takeIf { it.isNotBlank() }?.let { add("srchmsg" to it) }
    }

    fun toUrl(): String {
        val params = siteParams
        // サイトは Shift_JIS のため、検索語も Shift_JIS でエンコードする。Charset を受け取る版は Android 13（API 33）からなので、名前で渡す。
        fun encode(v: String) = java.net.URLEncoder.encode(v, SITE_CHARSET.name())
        val root = "https://${genre.host}/g/${genre.key}/"
        // 1 ページ目は本家の絞り込みフォームと同じクエリの形。2 ページ目以降は本家のページャと同じく
        // 条件をパスに入れる（`/g/hokkaido/vsex/1/pageID/2/`）。実際の取得では、一覧に載っていた
        // ページャのリンクがあればそちらを使う（[ShaloveClient.fetchRoomList]）。
        if (page > 1) return root + params.joinToString("") { (k, v) -> "$k/${encode(v)}/" } + "pageID/$page/"
        if (params.isEmpty()) return root
        return root + "?" + params.joinToString("&") { (k, v) -> k + "=" + encode(v) }
    }

    /**
     * 本家のページャのリンク（`/g/<genre>/vsex/1/pageID/2/`）が、この一覧のこのページを指しているか。
     * パスの条件の並び順は問わず、条件の組み合わせとページ番号が一致するものだけを認める。
     */
    fun matchesPagerUrl(url: String): Boolean {
        val path = runCatching { java.net.URI(url).rawPath }.getOrNull() ?: return false
        val prefix = "/g/${genre.key}/"
        if (!path.startsWith(prefix)) return false
        val segments = path.removePrefix(prefix).trimEnd('/').split('/')
        if (segments.size < 2 || segments.size % 2 != 0) return false
        val pairs = segments.chunked(2).map { (k, v) ->
            k to (runCatching { java.net.URLDecoder.decode(v, SITE_CHARSET.name()) }.getOrNull() ?: return false)
        }
        val (pageKey, pageValue) = pairs.last()
        if (pageKey != "pageID" || pageValue != page.toString()) return false
        val filters = pairs.dropLast(1)
        return filters.size == filters.toMap().size && filters.toSet() == siteParams.toSet()
    }

    /** 本家側の絞り込み（ページ以外）が同じ一覧。ページャのリンクを共有する単位。 */
    val firstPage: RoomQuery get() = if (page == 1) this else copy(page = 1)
}

internal val SITE_CHARSET: java.nio.charset.Charset = java.nio.charset.Charset.forName("windows-31j")
