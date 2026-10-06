package io.github.springthief1123.lovelyspace.data

import io.github.springthief1123.lovelyspace.core.*

data class RadarRoomSnapshot(val room: Room, val at: Long?, val page: Int = 1, val blocked: Boolean = false, val sourceQuery: RoomQuery? = null)
data class RadarRoomInspection(val room: Room? = null, val message: String, val reused: Boolean = false)

/** 古い履歴から直接入室せず、指定ページを新たに取得して公開プロフィールを照合する。 */
suspend fun inspectRadarRoom(lists: RoomListSource, snapshot: RadarRoomSnapshot): RadarRoomInspection {
    if (snapshot.blocked) return RadarRoomInspection(message = "ID再利用を確認した記録です。「見つける」から部屋を選び直してください。", reused = true)
    val genre = Genres[snapshot.room.genreKey] ?: return RadarRoomInspection(message = "このジャンルは開けません。")
    val query = snapshot.sourceQuery ?: RoomQuery(genre, page = snapshot.page)
    if (query.genre != genre || query.page < 1) return RadarRoomInspection(message = "記録の取得条件を確認できません。「見つける」で確認してください。")
    val before = lists.observation(query)?.revision ?: 0
    lists.fetch(query, force = true)
    val observation = lists.observation(query)
    if (observation == null || observation.revision <= before || observation.query != query) {
        return RadarRoomInspection(message = "新しい一覧を確認できませんでした。時間をおいて再試行してください。")
    }
    val current = observation.page.rooms.firstOrNull { roomIdentity(it) == roomIdentity(snapshot.room) && it.genreKey == snapshot.room.genreKey }
    return when (roomIdentityEvidence(snapshot.room, current)) {
        RoomIdentityEvidence.MATCH -> RadarRoomInspection(current, "${formatObservationTime(observation.confirmedAt)}の一覧でプロフィールを照合しました。空き状況は入室前にも確認します。")
        RoomIdentityEvidence.REUSED -> RadarRoomInspection(message = "同じIDに異なるプロフィールを確認しました。「見つける」から選び直してください。", reused = true)
        RoomIdentityEvidence.AMBIGUOUS -> RadarRoomInspection(message = "同じIDはありますが、名前などが非表示でプロフィールを照合できません。")
        RoomIdentityEvidence.NOT_OBSERVED -> RadarRoomInspection(message = "このページでは確認できませんでした。別のページへ移った可能性もあります。「見つける」で確認してください。")
    }
}
