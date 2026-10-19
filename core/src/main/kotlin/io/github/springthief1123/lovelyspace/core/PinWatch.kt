package io.github.springthief1123.lovelyspace.core

import java.time.Instant
import java.time.ZoneId

/** ピンした部屋の今の様子。 */
enum class PinState {
    /** 待機中（入室できる）。 */
    OPEN,
    /** 満室。 */
    FULL,
    /** 閉じた。立て直しを待つ。 */
    CLOSED,
    /** まだ一覧で確かめていない（読んでいないページにあるかもしれない）。 */
    UNSEEN,
}

/**
 * [pinned]（ピンしたときの内容）の部屋が、ジャンル全体の一覧 [this] でどうなっているか。一覧に載っていればその部屋も返す。
 * 載っていなければ、閉じたと分かるとき（[RoomPageWindow.isClosed]）だけ [PinState.CLOSED]、ほかは [PinState.UNSEEN]。
 * 同じ ID に名前・性別・年齢の違う部屋が載っていれば（ID の再利用）、元の部屋は閉じたとする。
 */
fun RoomPageWindow.pinState(pinned: Room): Pair<PinState, Room?> {
    val key = roomIdentity(pinned)
    val observed = rooms.firstOrNull { roomIdentity(it) == key }
    return when {
        observed != null && roomIdentityEvidence(pinned, observed) == RoomIdentityEvidence.REUSED -> PinState.CLOSED to null
        observed != null -> (if (observed.status == RoomStatus.FULL) PinState.FULL else PinState.OPEN) to observed
        isClosed(pinned) -> PinState.CLOSED to null
        else -> PinState.UNSEEN to null
    }
}

/**
 * 募集文の似ている度合い（0〜1）。表記の揺れ（[KeywordFolding]）と空白をそろえた 2 文字ずつの組の重なり（Dice 係数）。
 * 少し書き換えて立て直した部屋（「今夜も」を足した、絵文字を変えたなど）を見つけるのに使う。
 */
fun messageSimilarity(a: String, b: String): Double {
    val x = bigrams(a)
    val y = bigrams(b)
    if (x.isEmpty() || y.isEmpty()) return 0.0
    val counts = HashMap<String, Int>()
    y.forEach { counts.merge(it, 1, Int::plus) }
    var shared = 0
    x.forEach { g -> val n = counts[g] ?: 0; if (n > 0) { shared++; counts[g] = n - 1 } }
    return 2.0 * shared / (x.size + y.size)
}

private fun foldedMessage(text: String): String = KeywordFolding.fold(text).filterNot { it.isWhitespace() }

private fun bigrams(text: String): List<String> = foldedMessage(text).let { s -> (0 until s.length - 1).map { s.substring(it, it + 2) } }

/** 似た部屋の候補と、募集文の似ている度合い（0〜1）。 */
data class SimilarRoom(val room: Room, val similarity: Double)

/**
 * 閉じたピンの部屋 [old] に似た、新しく立った部屋。日をまたいで募集文を少し書き換えて立て直した部屋に気づくためのもの。
 * 名前と募集文が同じ立て直し（[isRebuildOf]）は [RoomPageWindow.rebuildOf] で自動で引き継ぐので、ここでは候補にしない。
 *
 * - 名前が同じ（どちらも見えている）なら、募集文の似ている度合いが [SIMILAR_SAME_NAME] 以上
 * - 名前が違う・見えないなら、募集文の似ている度合いが [SIMILAR_OTHER_NAME] 以上
 * - どちらも募集文が [SIMILAR_MIN_MESSAGE] 文字以上（短い定型文は別の人と重なりやすい）、性別・年齢・地域タグが食い違わない、ID が [old] より大きい
 *
 * 一番似た候補と二番目の差が [SIMILAR_MARGIN] 未満なら、どちらか決められないので null。[skip] の部屋（[roomIdentity]）は候補にしない。
 * 似ているだけでは同じ人とは言えないので、呼び出し側は利用者に確かめてから引き継ぐ（自動で入室しない）。
 */
fun RoomPageWindow.similarRoomOf(old: Room, skip: Set<String> = emptySet()): SimilarRoom? {
    if (foldedMessage(old.message).length < SIMILAR_MIN_MESSAGE) return null
    val oldName = old.name?.let { KeywordFolding.fold(it).trim() }?.ifEmpty { null }
    val scored = rooms.asSequence()
        .filter { it.id > old.id && it.genreKey == old.genreKey && roomIdentity(it) !in skip && !isRebuildOf(old, it) }
        .filter { foldedMessage(it.message).length >= SIMILAR_MIN_MESSAGE }
        .filter { old.gender == Gender.UNKNOWN || it.gender == Gender.UNKNOWN || old.gender == it.gender }
        .filter { old.age == null || it.age == null || old.age == it.age }
        .filter { old.area == null || it.area == null || old.area == it.area }
        .mapNotNull { room ->
            val similarity = messageSimilarity(old.message, room.message)
            val name = room.name?.let { KeywordFolding.fold(it).trim() }?.ifEmpty { null }
            val needed = if (oldName != null && name == oldName) SIMILAR_SAME_NAME else SIMILAR_OTHER_NAME
            if (similarity >= needed) SimilarRoom(room, similarity) else null
        }
        .sortedByDescending { it.similarity }
        .take(2).toList()
    val best = scored.firstOrNull() ?: return null
    val second = scored.getOrNull(1)
    return if (second != null && best.similarity - second.similarity < SIMILAR_MARGIN) null else best
}

const val SIMILAR_SAME_NAME = 0.6
const val SIMILAR_OTHER_NAME = 0.85
const val SIMILAR_MIN_MESSAGE = 8
const val SIMILAR_MARGIN = 0.05

/**
 * ピンの部屋が空いた時刻の記録から作る要約。[times] はミリ秒の時刻（古い順でなくてよい）。
 * [hours] は直近 [days] 日の、空いた回数の時間帯（0〜23 時）ごとの数。[peakHour] は 3 回以上空いた記録があるときの、いちばん多い時間帯。
 */
data class VacancyStats(val count: Int, val last: Long?, val hours: List<Int>, val peakHour: Int?) {
    companion object {
        const val DAYS = 7
        private const val DAY_MS = 24 * 60 * 60 * 1000L

        fun of(times: List<Long>, now: Long, zone: ZoneId = ZoneId.systemDefault(), days: Int = DAYS): VacancyStats {
            val hours = IntArray(24)
            times.filter { now - it in 0 until days * DAY_MS }.forEach { hours[Instant.ofEpochMilli(it).atZone(zone).hour]++ }
            val peak = if (hours.sum() >= 3) hours.indices.maxByOrNull { hours[it] } else null
            return VacancyStats(times.size, times.maxOrNull(), hours.toList(), peak)
        }
    }
}
