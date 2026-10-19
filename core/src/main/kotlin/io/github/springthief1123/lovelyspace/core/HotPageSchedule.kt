package io.github.springthief1123.lovelyspace.core

/**
 * 新着の確認。見ているジャンルの一覧の 1 ページ目だけを、設定の間隔（[RefreshPacing.hotIntervalMs]）ごとに読み直す順番を決める。
 *
 * 一覧は部屋を立てた順（部屋の ID の大きい順）なので、新しい部屋は必ず 1 ページ目の先頭に出る。2026-10-04 の採取では関東で毎分約 16 部屋が立ち、
 * 1 ページ目（60 部屋）に約 3.6 分残るので、1 ページ目を数秒ごとに読めば新しい部屋を取りこぼさない。後ろのページ（古い部屋）は
 * 見つける画面・レーダーの周回（[RoomPageSchedule]）が、この確認の合間に読む（[ShaloveClient.fetchRoomList] の priority で先に通す）。
 *
 * ジャンルが複数あれば、読んでから最も時間の経ったジャンルから 1 つずつ読む。取得に失敗したジャンルは [RoomPageSchedule.ERROR_INTERVAL_MS] 空ける。
 */
class HotPageSchedule(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val pacing: () -> RefreshPacing = { RefreshPacing() },
) {
    private class Read(val at: Long, val failed: Boolean)
    private val reads = mutableMapOf<String, Read>()

    /** 次に読むジャンルと、それまでに待つ時間（ミリ秒）。ジャンルが無ければ null。 */
    fun next(genres: Collection<Genre>): Pair<Genre, Long>? {
        if (genres.isEmpty()) return null
        reads.keys.retainAll(genres.mapTo(HashSet()) { it.key })
        val interval = pacing().hotIntervalMs(genres.size)
        val now = clock()
        return genres.map { genre ->
            val read = reads[genre.key]
            val due = when {
                read == null -> now
                read.failed -> read.at + RoomPageSchedule.ERROR_INTERVAL_MS
                else -> read.at + interval
            }
            genre to (due - now).coerceAtLeast(0)
        }.minBy { it.second }
    }

    /** [genre] の 1 ページ目を読み終えた（[success] でなければ取得に失敗した）。 */
    fun completed(genre: Genre, success: Boolean) {
        reads[genre.key] = Read(clock(), !success)
    }
}
