package io.github.springthief1123.lovelyspace.core

/** 一覧で確認できる特徴だけを照合する。本人認証ではない。 */
enum class RoomIdentityEvidence { MATCH, AMBIGUOUS, REUSED, NOT_OBSERVED }

fun roomIdentityEvidence(target: Room, observed: Room?): RoomIdentityEvidence {
    if (observed == null || roomIdentity(target) != roomIdentity(observed) || target.genreKey != observed.genreKey) return RoomIdentityEvidence.NOT_OBSERVED
    if (target.name != null && observed.name != null && target.name != observed.name) return RoomIdentityEvidence.REUSED
    if (target.gender != Gender.UNKNOWN && observed.gender != Gender.UNKNOWN && target.gender != observed.gender) return RoomIdentityEvidence.REUSED
    if (target.age != null && observed.age != null && target.age != observed.age) return RoomIdentityEvidence.REUSED
    if (target.name == null || observed.name == null) return RoomIdentityEvidence.AMBIGUOUS
    return RoomIdentityEvidence.MATCH
}

/**
 * [new] が [old] の立て直し（同じ人が部屋を閉じ、同じ名前・募集文で作り直した部屋。ID は新しくなる）に見えるか。本人確認ではない。
 *
 * 名前と募集文は表記の揺れ（[KeywordFolding]）をそろえて完全一致を見る。性別・年齢・地域タグは両方に出ていれば一致すること。
 * 満室の部屋は一覧に名前が出ないので、どちらかの名前が分からなければ、募集文が [REBUILD_MESSAGE_ONLY_MIN] 文字以上のときだけ募集文で照合する
 * （短い定型文は別の人と重なりやすい）。新しい部屋は後から立つので、ID が [old] より大きいこと。
 */
fun isRebuildOf(old: Room, new: Room): Boolean {
    if (new.id <= old.id || new.genreKey != old.genreKey) return false
    val message = KeywordFolding.fold(old.message).trim()
    if (message.isEmpty() || message != KeywordFolding.fold(new.message).trim()) return false
    if (old.area != null && new.area != null && old.area != new.area) return false
    if (old.gender != Gender.UNKNOWN && new.gender != Gender.UNKNOWN && old.gender != new.gender) return false
    if (old.age != null && new.age != null && old.age != new.age) return false
    val oldName = old.name?.let { KeywordFolding.fold(it).trim() }?.ifEmpty { null }
    val newName = new.name?.let { KeywordFolding.fold(it).trim() }?.ifEmpty { null }
    return if (oldName != null && newName != null) oldName == newName else message.length >= REBUILD_MESSAGE_ONLY_MIN
}

/** 名前が分からないとき、募集文だけで立て直しと見る最短の長さ（[KeywordFolding.fold] 後の文字数）。 */
const val REBUILD_MESSAGE_ONLY_MIN = 10
