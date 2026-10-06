package io.github.springthief1123.lovelyspace.data

import io.github.springthief1123.lovelyspace.core.*
import org.json.JSONObject

/** 照合元のページとサイト側の絞り込みを、再起動後にも保持する。 */
internal fun radarQueryJson(query: RoomQuery?): JSONObject? = query?.let {
    JSONObject().put("genre", it.genre.key).put("page", it.page).put("sex", it.sex?.name)
        .put("prefecture", it.prefecture).put("ageBand", it.ageBand).put("publicOnly", it.publicOnly)
        .put("waitingOnly", it.waitingOnly).put("name", it.name).put("message", it.message)
}
internal fun readRadarQuery(json: JSONObject?): RoomQuery? {
    json ?: return null
    val genre = Genres[json.optString("genre")] ?: return null
    fun text(key: String) = if (json.isNull(key)) null else json.getString(key)
    fun flag(key: String) = if (json.isNull(key)) null else json.getBoolean(key)
    return RoomQuery(genre, sex = text("sex")?.let { Gender.valueOf(it) },
        prefecture = if (json.isNull("prefecture")) null else json.getInt("prefecture"),
        ageBand = text("ageBand"), publicOnly = flag("publicOnly"), waitingOnly = flag("waitingOnly"),
        name = text("name"), message = text("message"), page = json.optInt("page", 1).coerceAtLeast(1))
}
internal fun TrackedRoom.sourceQueryOrLegacy(): RoomQuery? = sourceQuery?.takeIf { it.genre.key == room.genreKey }
    ?: Genres[room.genreKey]?.let { RoomQuery(it, page = observedPage) }
