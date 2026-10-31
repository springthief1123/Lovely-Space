package io.github.springthief1123.lovelyspace.core

/** 都道府県（サイトの `prefecture` / `vpref` の値。1〜47 は JIS の順、48 は海外）。 */
object Prefectures {
    val names: List<String> = listOf(
        "北海道", "青森", "岩手", "宮城", "秋田", "山形", "福島",
        "茨城", "栃木", "群馬", "埼玉", "千葉", "東京", "神奈川",
        "新潟", "富山", "石川", "福井", "山梨", "長野", "岐阜", "静岡", "愛知",
        "三重", "滋賀", "京都", "大阪", "兵庫", "奈良", "和歌山",
        "鳥取", "島根", "岡山", "広島", "山口",
        "徳島", "香川", "愛媛", "高知",
        "福岡", "佐賀", "長崎", "熊本", "大分", "宮崎", "鹿児島", "沖縄",
        "海外",
    )

    /** [code] は 1 始まり。範囲外なら null。 */
    fun name(code: Int): String? = names.getOrNull(code - 1)

    /**
     * [room] が地域 [area]（[names] の 1 つ）の部屋か。
     * 地域タグがあればその先頭で比べる（「東京都」「大阪府」も同じ地域）。
     * タグの無い部屋も多いので、名前・募集文に「東京住み」「大阪府」「神奈川在住」のように地域として書いてあれば含める。
     * 人の名字や普通の言葉（「大分慣れた」）と取り違えないよう、都道府県・市・住み・在住などが続くときだけ数える。
     */
    fun matches(room: Room, area: String): Boolean {
        room.area?.let { return it.trim().startsWith(area) }
        return mention(area).containsMatchIn("${room.name.orEmpty()} ${room.message}")
    }

    private val mentions = HashMap<String, Regex>()

    private fun mention(area: String): Regex = synchronized(mentions) {
        mentions.getOrPut(area) {
            // 「東京都」の中の「京都」は京都ではない。
            val before = if (area == "京都") "(?<!東)" else ""
            Regex(before + Regex.escape(area) + "(?:都|道|府|県|市|住|在住|近郊|周辺|方面|付近|エリア|民)")
        }
    }
}

/**
 * アプリ内のブラウザ画面（WebView）で開く本家のページ。
 * ロボット確認が必須の部屋作成や、まだ解析していない覗き画面に使う。
 */
object SitePages {
    /** 本家の会員ログイン・登録・会員ページ。認証は WebView に任せる。 */
    fun memberAccount(): String = "https://chat.shalove.net/member/"

    /** 本家の再入室導線。各ジャンルのホストにある本人用の再入室操作をそのまま開く。 */
    fun reenterRoom(genre: Genre): String =
        "https://${genre.host}/?submit=submit_rein2shot&genre_key=${genre.key}"

    /** 部屋作成画面。`kct` はサイトのリンクと同じく現在の UNIX 秒。 */
    fun makeRoom(genre: Genre, nowEpochSeconds: Long): String =
        "https://${genre.host}/PreMakeRoom?genre_key=${genre.key}&kct=$nowEpochSeconds"

    fun preEnter(host: String, genreKey: String, roomId: Long): String =
        "https://$host/PreEnterRoom?room_id=$roomId&genre_key=$genreKey"

    /** 満室の公開ルームを覗く画面。 */
    fun publicRoom(host: String, genreKey: String, roomId: Long): String =
        "https://$host/PublicRoom?room_id=$roomId&genre_key=$genreKey"
}
