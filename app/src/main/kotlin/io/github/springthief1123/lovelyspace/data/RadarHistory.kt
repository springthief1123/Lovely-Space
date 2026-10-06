package io.github.springthief1123.lovelyspace.data

import org.json.JSONObject

enum class RadarEventKind { SEARCH_MATCH, CANDIDATE_MATCH, ROOM_STATUS, IDENTITY_WARNING, PROFILE_UNCONFIRMED, LEGACY }
enum class RadarOriginType { PLAN, CANDIDATE, ROOM }
data class RadarEventOrigin(val type: RadarOriginType, val id: String, val label: String) {
    val key: String get() = "${type.name}/$id"
}
data class RadarHistoryFilter(val unreadOnly: Boolean = false, val kind: RadarEventKind? = null, val originKey: String? = null)

fun RadarEvent.matches(filter: RadarHistoryFilter): Boolean = (!filter.unreadOnly || !read) &&
    (filter.kind == null || kind == filter.kind) && (filter.originKey == null || origin?.key == filter.originKey)

internal fun radarOriginJson(origin: RadarEventOrigin?): JSONObject? = origin?.let {
    JSONObject().put("type", it.type.name).put("id", it.id).put("label", it.label)
}
internal fun readRadarOrigin(json: JSONObject?): RadarEventOrigin? {
    json ?: return null
    val type = RadarOriginType.entries.firstOrNull { it.name == json.optString("type") } ?: return null
    val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
    return RadarEventOrigin(type, id, json.optString("label"))
}
