package io.github.springthief1123.lovelyspace.notify

import android.content.SharedPreferences
import io.github.springthief1123.lovelyspace.core.Gender
import io.github.springthief1123.lovelyspace.core.Room
import io.github.springthief1123.lovelyspace.core.RoomAction
import io.github.springthief1123.lovelyspace.core.RoomStatus
import org.json.JSONArray
import org.json.JSONObject

/** お知らせの履歴を SharedPreferences に JSON で残す。読めない記録は捨てる。 */
class NotificationInboxStore(private val prefs: SharedPreferences) : NotificationInboxPersistence {
    override fun load(): List<AppNotification> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { runCatching { decode(array.getJSONObject(it)) }.getOrNull() }
        }.getOrElse {
            prefs.edit().remove(KEY).apply()
            emptyList()
        }
    }

    override fun save(entries: List<AppNotification>) {
        val array = JSONArray()
        entries.forEach { array.put(encode(it)) }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private fun encode(n: AppNotification) = JSONObject()
        .put("id", n.id).put("kind", n.kind.name).put("title", n.title).put("text", n.text)
        .put("at", n.at).put("read", n.read).put("target", n.target.toJson())
        .apply { n.message?.let { put("message", it) } }
        .apply { n.room?.let { put("room", encodeRoom(it)) } }
        .apply { n.namedTitle?.let { put("namedTitle", it) } }

    private fun decode(o: JSONObject) = AppNotification(
        id = o.getString("id"),
        kind = NotificationKind.valueOf(o.getString("kind")),
        title = o.getString("title"),
        text = o.getString("text"),
        target = notificationTarget(o.getJSONObject("target")),
        at = o.getLong("at"),
        message = if (o.has("message")) o.getString("message") else null,
        read = o.optBoolean("read"),
        // 部屋の記録が読めなくても、お知らせ自体は残す（以前の版の記録には無い）。
        room = o.optJSONObject("room")?.let { runCatching { decodeRoom(it) }.getOrNull() },
        namedTitle = if (o.has("namedTitle")) o.getString("namedTitle") else null,
    )

    private fun encodeRoom(r: Room) = JSONObject()
        .put("id", r.id).put("genre", r.genreKey).put("status", r.status.name).put("action", r.action.name)
        .put("name", r.name).put("gender", r.gender.name).put("age", r.age).put("area", r.area).put("message", r.message)

    private fun decodeRoom(j: JSONObject) = Room(
        id = j.getLong("id"),
        genreKey = j.getString("genre"),
        status = RoomStatus.valueOf(j.getString("status")),
        action = RoomAction.valueOf(j.getString("action")),
        elapsed = null,
        name = if (j.isNull("name")) null else j.getString("name"),
        gender = Gender.valueOf(j.getString("gender")),
        age = if (j.isNull("age")) null else j.getInt("age"),
        area = if (j.isNull("area")) null else j.getString("area"),
        message = j.optString("message"),
    )

    companion object {
        const val PREFS = "notifications"
        private const val KEY = "inbox"
    }
}
