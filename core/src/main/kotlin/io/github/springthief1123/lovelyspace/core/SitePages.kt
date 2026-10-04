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
}

/**
 * アプリ内のブラウザ画面（WebView）で開く本家のページ。
 * ロボット確認が必須の部屋作成や、まだ解析していない覗き画面に使う。
 */
object SitePages {
    /** 部屋作成画面。`kct` はサイトのリンクと同じく現在の UNIX 秒。 */
    fun makeRoom(genre: Genre, nowEpochSeconds: Long): String =
        "https://${genre.host}/PreMakeRoom?genre_key=${genre.key}&kct=$nowEpochSeconds"

    fun preEnter(host: String, genreKey: String, roomId: Long): String =
        "https://$host/PreEnterRoom?room_id=$roomId&genre_key=$genreKey"

    /** 満室の公開ルームを覗く画面。 */
    fun publicRoom(host: String, genreKey: String, roomId: Long): String =
        "https://$host/PublicRoom?room_id=$roomId&genre_key=$genreKey"
}
