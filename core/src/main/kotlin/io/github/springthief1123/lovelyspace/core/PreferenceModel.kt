package io.github.springthief1123.lovelyspace.core

import kotlin.math.ln
import kotlin.time.Duration

/**
 * 利用者が保存した部屋と非表示にした部屋から好みを学び、部屋を好みに近い順に並べる小さな分類器（おすすめ順）。
 * 名前と募集文を [KeywordFolding] でそろえた文字 2-gram の多項ナイーブベイズ。端末内で一瞬で学習・判定でき、外部のモデルや通信は使わない。
 *
 * 好みでない側は、非表示にした部屋に加えて、いま一覧にある部屋（保存・非表示のものを除く）を弱い重み [BACKGROUND_WEIGHT] で使う。
 * 「一覧によくある募集文」と「保存した募集文」の違いを学ぶので、非表示が少なくても並べられる。
 */
class PreferenceModel private constructor(
    private val weights: Map<String, Double>,
    private val unseen: Double,
    /** 学習に使った保存した部屋の数。 */
    val likedCount: Int,
    /** 学習に使った非表示にした部屋の数。 */
    val dislikedCount: Int,
) {
    /** 好みに近いほど大きい値。文字 2-gram ごとの対数オッズの平均（募集文の長さで差が出ないようにする）。 */
    fun score(room: Room): Double {
        val grams = grams(room)
        if (grams.isEmpty()) return 0.0
        return grams.sumOf { weights[it] ?: unseen } / grams.size
    }

    /**
     * [rooms] を好みに近い順に並べる。ただし直近 [recentAge] に立った待機中の部屋（巡回が優先するのと同じ境目）を先にし、
     * その中を好みの順に、古い部屋と満室の部屋はその後ろで好みの順にする。好みに近くても何時間も前の部屋が上に来ないようにするため。
     * 経過時間の分からない部屋は新しい側に含める。同じ値なら元の順（本家の順）のまま。
     */
    fun rank(rooms: List<Room>, recentAge: Duration = RECENT_ROOM_AGE): List<Room> {
        val scores = rooms.associate { roomIdentity(it) to score(it) }
        val (recent, older) = rooms.partition { !it.isFull && it.elapsed.let { e -> e == null || e <= recentAge } }
        return recent.sortedByDescending { scores.getValue(roomIdentity(it)) } +
            older.sortedByDescending { scores.getValue(roomIdentity(it)) }
    }

    companion object {
        /** 学習に要る保存した部屋の数。少なすぎると偶然の語に引っ張られる。 */
        const val MIN_LIKED = 3
        /** 一覧にある部屋を、好みでない側に数える重み。非表示にした部屋は 1。 */
        const val BACKGROUND_WEIGHT = 0.2
        private const val ALPHA = 1.0

        /** 保存した部屋 [liked]（非表示にしたものを除く）が [MIN_LIKED] に満たなければ null（並べ替えない）。 */
        fun train(liked: List<Room>, disliked: List<Room>, background: List<Room>): PreferenceModel? {
            // 保存したまま非表示にした部屋は、非表示を優先して好みでない側だけに数える。
            val dislikedRooms = disliked.distinctBy(::roomIdentity)
            val dislikedIds = dislikedRooms.map(::roomIdentity).toSet()
            val likedRooms = liked.distinctBy(::roomIdentity).filter { roomIdentity(it) !in dislikedIds }
            if (likedRooms.size < MIN_LIKED) return null
            val labeled = (likedRooms + dislikedRooms).map(::roomIdentity).toSet()
            val positive = HashMap<String, Double>()
            val negative = HashMap<String, Double>()
            likedRooms.forEach { count(positive, it, 1.0) }
            dislikedRooms.forEach { count(negative, it, 1.0) }
            background.distinctBy(::roomIdentity).filter { roomIdentity(it) !in labeled }.forEach { count(negative, it, BACKGROUND_WEIGHT) }
            val vocabulary = positive.keys + negative.keys
            val positiveTotal = positive.values.sum() + ALPHA * (vocabulary.size + 1)
            val negativeTotal = negative.values.sum() + ALPHA * (vocabulary.size + 1)
            fun logOdds(p: Double, n: Double) = ln((p + ALPHA) / positiveTotal) - ln((n + ALPHA) / negativeTotal)
            return PreferenceModel(
                weights = vocabulary.associateWith { logOdds(positive[it] ?: 0.0, negative[it] ?: 0.0) },
                unseen = logOdds(0.0, 0.0),
                likedCount = likedRooms.size,
                dislikedCount = dislikedRooms.size,
            )
        }

        private fun count(counts: HashMap<String, Double>, room: Room, weight: Double) =
            grams(room).forEach { counts[it] = (counts[it] ?: 0.0) + weight }

        /** 名前と募集文をそろえた文字 2-gram。空白をまたぐものは数えない。 */
        internal fun grams(room: Room): List<String> =
            KeywordFolding.fold("${room.name.orEmpty()}\n${room.message}").windowed(2).filter { gram -> gram.none(Char::isWhitespace) }
    }
}
