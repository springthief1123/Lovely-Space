package io.github.springthief1123.lovelyspace.data

import io.github.springthief1123.lovelyspace.core.*

fun interface RoomListSource {
    suspend fun fetch(query: RoomQuery, force: Boolean): RoomListPage
}

/** 全タブが同じ通信・キャッシュ・間隔制限を利用する。 */
class RoomListRepository(private val client: ShaloveClient) : RoomListSource {
    override suspend fun fetch(query: RoomQuery, force: Boolean): RoomListPage = client.fetchRoomList(query, forceRefresh = force)
}
