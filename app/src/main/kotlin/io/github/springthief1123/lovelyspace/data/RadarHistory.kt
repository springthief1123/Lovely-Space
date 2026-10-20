package io.github.springthief1123.lovelyspace.data

import org.json.JSONObject

/** 一致の記録の種類。ROOM_STATUS・IDENTITY_WARNING・PROFILE_UNCONFIRMED と ROOM は以前の追跡の記録で、保存済みの記録を読むためだけに残す。 */
enum class RadarEventKind { SEARCH_MATCH, CANDIDATE_MATCH, ROOM_STATUS, IDENTITY_WARNING, PROFILE_UNCONFIRMED, LEGACY }
enum class RadarOriginType { PLAN, CANDIDATE, ROOM }
data class RadarEventOrigin(val type: RadarOriginType, val id: String, val label: String, val description: String = "") {
    val key: String get() = "${type.name}/$id"
}

internal fun radarOriginJson(origin: RadarEventOrigin?): JSONObject? = origin?.let {
    JSONObject().put("type", it.type.name).put("id", it.id).put("label", it.label).put("description", it.description)
}
internal fun readRadarOrigin(json: JSONObject?): RadarEventOrigin? {
    json ?: return null
    val type = RadarOriginType.entries.firstOrNull { it.name == json.optString("type") } ?: return null
    val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
    return RadarEventOrigin(type, id, json.optString("label"), json.optString("description"))
}
