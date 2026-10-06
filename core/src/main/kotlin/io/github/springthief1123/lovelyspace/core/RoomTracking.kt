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
