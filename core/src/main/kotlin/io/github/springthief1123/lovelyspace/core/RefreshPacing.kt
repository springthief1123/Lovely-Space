package io.github.springthief1123.lovelyspace.core

/**
 * 本家への自動の取り直しの間隔（ミリ秒）。利用者が設定画面で選び、[ShaloveClient] と各画面の巡回が通信のたびに読む。
 *
 * 空き枠・新しい部屋は秒単位で埋まるので、本家で更新ボタンを押しながら待つのと同じ程度まで縮められるようにする
 * （Yuya の決定、2026-10-09）。ただし 1 秒に 5 回以上の再読み込みで本家から一時的にアクセスを止められたことがあるので、
 * リクエスト同士の間隔は [FLOOR_MIN_INTERVAL_MS]（1 秒）より短くしない。各機能の間隔もこの最小間隔を下回っては効かない。
 */
data class RefreshPacing(
    /** すべてのリクエスト同士の最小間隔。毎分のリクエスト数の上限を決める。 */
    val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
    /** 見つける画面で、新しい部屋が出る 1 ページ目を取り直す間隔。 */
    val searchHeadMs: Long = DEFAULT_SEARCH_HEAD_MS,
    /** レーダーの自動巡回（前面）で、1 ページ目を取り直す間隔。 */
    val radarHeadMs: Long = DEFAULT_RADAR_HEAD_MS,
    /** アプリが前面の間に、順番待ちの部屋のページを確認する間隔。 */
    val waitlistMs: Long = DEFAULT_WAITLIST_MS,
    /** 公開ルームを覗いている間に、新しい発言を読み直す間隔。 */
    val publicRoomMs: Long = DEFAULT_PUBLIC_ROOM_MS,
) {
    /**
     * 一覧のキャッシュ期間。同じページを複数の機能が続けて取りに行くのを 1 回にまとめるためのもので、
     * 一覧を自動で取り直す間隔のうち最も短いものに合わせる（[MAX_LIST_CACHE_MS] を超えない）。
     */
    val listCacheTtlMs: Long get() = minOf(searchHeadMs, radarHeadMs, waitlistMs, MAX_LIST_CACHE_MS)

    /** 下限を割る値（古い保存値・不正な値）を下限に揃える。 */
    fun sanitized(): RefreshPacing = copy(
        minIntervalMs = minIntervalMs.coerceAtLeast(FLOOR_MIN_INTERVAL_MS),
        searchHeadMs = searchHeadMs.coerceAtLeast(FLOOR_REFRESH_MS),
        radarHeadMs = radarHeadMs.coerceAtLeast(FLOOR_REFRESH_MS),
        waitlistMs = waitlistMs.coerceAtLeast(FLOOR_REFRESH_MS),
        publicRoomMs = publicRoomMs.coerceAtLeast(FLOOR_REFRESH_MS),
    )

    companion object {
        /** リクエスト同士の間隔の下限。これより短い値は選べず、保存されていても使わない。 */
        const val FLOOR_MIN_INTERVAL_MS = 1_000L
        /** 各機能の取り直しの間隔の下限。 */
        const val FLOOR_REFRESH_MS = 2_000L
        const val MAX_LIST_CACHE_MS = 20_000L

        const val DEFAULT_MIN_INTERVAL_MS = 3_000L
        /** Chrome 拡張と同じ 4 秒（Yuya の決定、2026-10-09）。 */
        const val DEFAULT_SEARCH_HEAD_MS = 4_000L
        const val DEFAULT_RADAR_HEAD_MS = 4_000L
        const val DEFAULT_WAITLIST_MS = 5_000L
        const val DEFAULT_PUBLIC_ROOM_MS = 20_000L

        val MIN_INTERVAL_CHOICES = listOf(1_000L, 1_500L, 2_000L, 3_000L)
        val LIST_CHOICES = listOf(2_000L, 3_000L, 4_000L, 6_000L, 10_000L, 20_000L)
        val WAITLIST_CHOICES = listOf(2_000L, 3_000L, 5_000L, 10_000L, 20_000L, 30_000L)
        val PUBLIC_ROOM_CHOICES = listOf(2_000L, 3_000L, 5_000L, 10_000L, 20_000L, 30_000L)
    }
}
