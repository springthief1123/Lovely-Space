package io.github.springthief1123.lovelyspace.data

import io.github.springthief1123.lovelyspace.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** 端末内に保存するレーダー。手動巡回のみ。入室・背景通信は行わない。 */
data class TrackedRoom(val room: Room, val confirmedAt: Long? = null, val evidence: RoomIdentityEvidence = RoomIdentityEvidence.NOT_OBSERVED, val identity: Room = room)
data class RadarEvent(val at: Long, val text: String)
data class RadarState(
    val plans: Set<String> = emptySet(), val targets: List<TrackedRoom> = emptyList(),
    val events: List<RadarEvent> = emptyList(), val running: Boolean = false,
    val loaded: Boolean = false, val error: String? = null,
    val scopes: Map<String, String> = emptyMap(),
)

class RadarRepository(
    private val dao: PresetDao,
    private val lists: ObservedRoomListSource,
    private val searches: SearchPresetStore,
    private val preferences: RoomPreferenceStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(RadarState())
    val state = _state.asStateFlow()
    private val seen = mutableMapOf<RoomQuery, Long>()
    private val baselines = mutableMapOf<String, Set<String>>()
    private val knownMatches = mutableMapOf<String, MutableSet<String>>()
    private val nextPages = mutableMapOf<String, Int>()
    init {
        scope.launch {
            try {
                mutex.withLock { dao.state(KEY)?.let(::restore); _state.update { it.copy(loaded = true) } }
                lists.observations.collect { observations ->
                    mutex.withLock {
                        val presets = searches.presets.first().filter { it.id in _state.value.plans }
                        observations.values.sortedBy { it.revision }.forEach { observation ->
                            if (seen[observation.query] == observation.revision) return@forEach
                            process(observation, presets)
                            seen[observation.query] = observation.revision
                        }
                        persist()
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(loaded = true, error = "レーダーの読み込みに失敗しました。画面を開き直すかアプリを再起動してください。") } }
        }
    }
    suspend fun setPlan(id: String, enabled: Boolean) = edit {
        baselines.keys.removeAll { it.startsWith("$id/") }
        knownMatches.keys.removeAll { it.startsWith("$id/") }
        nextPages.remove(id)
        it.copy(plans = if (enabled) it.plans + id else it.plans - id)
    }
    suspend fun track(room: Room) = edit {
        require(room.name != null) { "プロフィールを確認できる部屋を選んでください。" }
        val key = roomIdentity(room)
        it.copy(targets = it.targets.filterNot { t -> roomIdentity(t.room) == key } + TrackedRoom(room))
    }
    suspend fun removeTarget(room: Room) = edit { it.copy(targets = it.targets.filterNot { t -> roomIdentity(t.room) == roomIdentity(room) }) }
    private suspend fun edit(block: (RadarState) -> RadarState) = mutex.withLock {
        check(_state.value.loaded) { "読み込み中です。" }
        val old = _state.value
        _state.value = block(old).copy(error = null)
        try { persist() } catch (e: Exception) { _state.value = old; throw e }
    }
    suspend fun scan() {
        mutex.withLock {
            check(_state.value.loaded) { "読み込み中です。" }
            if (_state.value.running) return
            _state.update { it.copy(running = true, error = null) }
        }
        try {
            val selected = searches.presets.first().filter { it.id in _state.value.plans }
            // 同じジャンル・ページを複数の計画が要求しても、通信は1回だけ。
            val queries = mutex.withLock {
                selected.mapNotNull { p -> Genres[p.genreKey]?.let { RoomQuery(it, page = nextPages[p.id] ?: 1) } } +
                    _state.value.targets.filter { it.evidence != RoomIdentityEvidence.REUSED }.mapNotNull { t -> Genres[t.room.genreKey]?.let { RoomQuery(it) } }
            }.distinct()
            for (query in queries) {
                lists.fetch(query, force = true)
                val observation = lists.observation(query) ?: continue
                mutex.withLock {
                    if (seen[query] != observation.revision) { process(observation, selected); seen[query] = observation.revision }
                    selected.filter { it.genreKey == query.genre.key && (nextPages[it.id] ?: 1) == query.page }.forEach {
                        nextPages[it.id] = if (observation.page.hasNextPage) observation.page.page + 1 else 1
                    }
                    persist()
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { _state.update { it.copy(error = "巡回できませんでした。通信状況を確認して、もう一度お試しください。確認済みの情報は保持しています。") } }
        finally { _state.update { it.copy(running = false) } }
    }
    private suspend fun process(o: ObservedRoomPage, presets: List<SearchPreset>) {
        preferences.observe(o.page.rooms)
        val hidden = preferences.preferences.first().filter { it.hidden }
        val current = _state.value
        val events = mutableListOf<RadarEvent>()
        val targets = current.targets.map { target ->
            if (target.room.genreKey != o.query.genre.key || target.evidence == RoomIdentityEvidence.REUSED) return@map target
            if (target.confirmedAt != null && target.confirmedAt > o.confirmedAt) return@map target
            val room = o.page.rooms.firstOrNull { roomIdentity(it) == roomIdentity(target.room) }
            val evidence = roomIdentityEvidence(target.identity, room)
            if (evidence == RoomIdentityEvidence.NOT_OBSERVED) return@map target // 1ページに無いだけで不在とは判断しない。
            if (evidence == RoomIdentityEvidence.MATCH && room != null) {
                if (target.confirmedAt != null && target.room.status != room.status) events += RadarEvent(o.confirmedAt, "${room.name}：${statusName(room.status)}を確認")
                target.copy(room = room, confirmedAt = o.confirmedAt, evidence = evidence)
            } else {
                if (evidence == RoomIdentityEvidence.REUSED) events += RadarEvent(o.confirmedAt, "${target.room.name}：同じIDに異なるプロフィール。追跡を停止しました")
                target.copy(evidence = evidence)
            }
        }
        val scopes = current.scopes.toMutableMap()
        presets.filter { it.genreKey == o.query.genre.key }.forEach { preset ->
            // フィルタ済みの通信結果を無条件一覧の巡回基準には使わない。
            if (o.query != RoomQuery(o.query.genre, page = o.query.page)) return@forEach
            val key = "${preset.id}/${preset.criteria.hashCode()}/${o.query.page}"
            val matches = searchRooms(o.page.rooms.filterNot { room -> hidden.any { it.appliesTo(room) } }, preset.criteria)
            val identities = matches.map { "${roomIdentity(it)}/${it.name}/${it.gender}/${it.age}" }.toSet()
            val scopeKey = "${preset.id}/${preset.criteria.hashCode()}"
            val known = knownMatches.getOrPut(scopeKey) { mutableSetOf() }
            val previous = baselines[key]
            if (previous != null) {
                val count = (identities - previous - known).size
                if (count > 0) events += RadarEvent(o.confirmedAt, "${preset.label}：${o.page.page}ページで新しい一致を${count}件確認")
            }
            known.addAll(identities)
            baselines[key] = identities
            scopes[preset.id] = "${formatObservationTime(o.confirmedAt)} · ${o.page.page}/${o.page.lastPage}ページ · 一致${matches.size}件"
        }
        _state.value = current.copy(targets = targets, events = (events + current.events).take(100), scopes = scopes)
    }
    private suspend fun persist() {
        val s = _state.value
        val json = JSONObject().put("plans", JSONArray(s.plans.toList()))
            .put("targets", JSONArray(s.targets.map { t -> JSONObject().put("room", roomJson(t.room)).put("identity", roomJson(t.identity)).put("at", t.confirmedAt).put("evidence", t.evidence.name) }))
            .put("events", JSONArray(s.events.map { JSONObject().put("at", it.at).put("text", it.text) }))
        // ページ単位の基準・巡回位置は再起動時に捨て、初回大量通知を避ける。
        dao.put(LocalState(KEY, json.toString()))
    }
    private fun restore(value: String) {
        val json = JSONObject(value)
        val plans = json.optJSONArray("plans") ?: JSONArray()
        val targets = json.optJSONArray("targets") ?: JSONArray()
        val events = json.optJSONArray("events") ?: JSONArray()
        _state.value = RadarState(plans = (0 until plans.length()).map { plans.getString(it) }.toSet(),
            targets = (0 until targets.length()).map { i -> val t = targets.getJSONObject(i); TrackedRoom(readRoom(t.getJSONObject("room")), if (t.isNull("at")) null else t.getLong("at"), RoomIdentityEvidence.valueOf(t.getString("evidence")), if (t.has("identity")) readRoom(t.getJSONObject("identity")) else readRoom(t.getJSONObject("room"))) },
            events = (0 until events.length()).map { i -> events.getJSONObject(i).let { RadarEvent(it.getLong("at"), it.getString("text")) } })
    }
    private fun roomJson(r: Room) = JSONObject().put("id", r.id).put("genre", r.genreKey).put("status", r.status.name).put("action", r.action.name)
        .put("name", r.name).put("gender", r.gender.name).put("age", r.age).put("area", r.area).put("message", r.message)
    private fun readRoom(j: JSONObject) = Room(j.getLong("id"), j.getString("genre"), RoomStatus.valueOf(j.getString("status")), RoomAction.valueOf(j.getString("action")), null,
        if (j.isNull("name")) null else j.getString("name"), Gender.valueOf(j.getString("gender")), if (j.isNull("age")) null else j.getInt("age"), if (j.isNull("area")) null else j.getString("area"), j.getString("message"))
    private companion object { const val KEY = "radar_v1" }
}

fun formatObservationTime(at: Long): String = java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.JAPAN).format(java.util.Date(at))
fun statusName(status: RoomStatus) = when (status) { RoomStatus.WAITING -> "待機中"; RoomStatus.PUBLIC_WAITING -> "公開待機"; RoomStatus.FULL -> "満室" }
