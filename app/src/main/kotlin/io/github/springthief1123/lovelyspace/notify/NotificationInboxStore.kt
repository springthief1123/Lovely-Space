package io.github.springthief1123.lovelyspace.notify

import android.content.SharedPreferences
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
        .put("at", n.at).put("read", n.read).put("target", encodeTarget(n.target))
        .apply { n.message?.let { put("message", it) } }

    private fun decode(o: JSONObject) = AppNotification(
        id = o.getString("id"),
        kind = NotificationKind.valueOf(o.getString("kind")),
        title = o.getString("title"),
        text = o.getString("text"),
        target = decodeTarget(o.getJSONObject("target")),
        at = o.getLong("at"),
        message = if (o.has("message")) o.getString("message") else null,
        read = o.optBoolean("read"),
    )

    private fun encodeTarget(target: NotificationTarget) = when (target) {
        is NotificationTarget.Room -> JSONObject().put("type", "room")
            .put("host", target.host).put("genre", target.genreKey).put("roomId", target.roomId)
        NotificationTarget.ActiveChat -> JSONObject().put("type", "chat")
        NotificationTarget.Radar -> JSONObject().put("type", "radar")
        NotificationTarget.Rooms -> JSONObject().put("type", "rooms")
    }

    private fun decodeTarget(o: JSONObject): NotificationTarget = when (val type = o.getString("type")) {
        "room" -> NotificationTarget.Room(o.getString("host"), o.getString("genre"), o.getLong("roomId"))
        "chat" -> NotificationTarget.ActiveChat
        "radar" -> NotificationTarget.Radar
        "rooms" -> NotificationTarget.Rooms
        else -> error("Unknown target: $type")
    }

    companion object {
        const val PREFS = "notifications"
        private const val KEY = "inbox"
    }
}
