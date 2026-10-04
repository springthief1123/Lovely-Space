package io.github.springthief1123.lovelyspace.core

import kotlin.time.Duration

enum class Gender { MALE, FEMALE, UNKNOWN }

/** 一覧の「利用状況」列。 */
enum class RoomStatus {
    /** 待機中（非公開ルーム） */
    WAITING,

    /** 公開待機中 */
    PUBLIC_WAITING,

    /** 満室 */
    FULL,
}

/** 一覧の「入室」列のボタンが何をするか。 */
enum class RoomAction {
    /** `/PreEnterRoom` の「入室」 */
    ENTER,

    /** 満室の公開ルームを `/PublicRoom` で覗く */
    PEEK,

    /** 満室の非公開ルーム（「秘密」、押せない） */
    NONE,
}

data class Room(
    val id: Long,
    val genreKey: String,
    val status: RoomStatus,
    val action: RoomAction,
    /** 待機または会話の経過時間。 */
    val elapsed: Duration?,
    /** 満室の部屋では一覧に名前が出ないため null。 */
    val name: String?,
    val gender: Gender,
    val age: Int?,
    /** 募集文の先頭に付く地域タグ（例: 大阪、海外）。 */
    val area: String?,
    /** 募集文。満室の部屋では一覧上は隠されている本来の募集文。 */
    val message: String,
) {
    val isFull: Boolean get() = status == RoomStatus.FULL

    /** 公開ルームかどうか。満室の非公開ルームは false。 */
    val isPublic: Boolean
        get() = when (status) {
            RoomStatus.PUBLIC_WAITING -> true
            RoomStatus.WAITING -> false
            RoomStatus.FULL -> action == RoomAction.PEEK
        }
}

data class RoomListPage(
    val genreKey: String,
    val rooms: List<Room>,
    /** ジャンル全体の待機中の部屋数（ページ単位ではない）。 */
    val waitingCount: Int?,
    /** ジャンル全体の満室の部屋数。 */
    val fullCount: Int?,
    val page: Int,
    val lastPage: Int,
    /** 画面のジャンル一覧に出ている各ジャンルの部屋数（genreKey → 件数）。 */
    val genreCounts: Map<String, Int>,
    val totalRooms: Int?,
) {
    val hasNextPage: Boolean get() = page < lastPage
}
