package io.github.springthief1123.lovelyspace.data

import io.github.springthief1123.lovelyspace.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** 端末内に保存するレーダー。手動巡回のみ。入室・背景通信は行わない。 */
data class TrackedRoom(val room: Room, val confirmedAt: Long? = null, val evidence: RoomIdentityEvidence = RoomIdentityEvidence.NOT_OBSERVED, val identity: Room = room, val observedAt: Long? = null,
    /** このRoomListRepository内だけの取得順序。再起動時は0から照合し直すため永続化しない。 */
    val observationRevision: Long = 0,
    val observedPage: Int = 1,
)
data class RadarEvent(val at: Long, val text: String, val id: String = java.util.UUID.randomUUID().toString(),
    val rooms: List<Room> = emptyList(), val page: Int? = null, val blocked: Boolean = false)
data class RadarResult(val presetId: String, val at: Long, val page: Int, val lastPage: Int, val rooms: List<Room>, val genreKey: String, val criteria: RoomSearchCriteria)
data class RadarState(
    val plans: Set<String> = emptySet(), val targets: List<TrackedRoom> = emptyList(),
    val events: List<RadarEvent> = emptyList(), val running: Boolean = false,
    val loaded: Boolean = false, val error: String? = null,
    val scopes: Map<String, String> = emptyMap(),
    val results: Map<String, RadarResult> = emptyMap(),
    val nextPages: Map<String, Int> = emptyMap(),
) {
    fun resultFor(preset: SearchPreset): RadarResult? = results[preset.id]?.takeIf { it.genreKey == preset.genreKey && it.criteria == preset.criteria }
}

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
    private val planKeys = mutableMapOf<String, String>()
    private var observationJob: Job? = null
    init { reload() }
    fun reload() {
        if (_state.value.running) return
        observationJob?.cancel()
        _state.update { it.copy(loaded = false, error = null) }
        observationJob = scope.launch {
            try {
                mutex.withLock {
                    dao.state(KEY)?.let(::restore)
                    seen.clear(); baselines.clear(); knownMatches.clear(); nextPages.clear(); planKeys.clear()
                    _state.update { it.copy(loaded = true, scopes = emptyMap(), results = emptyMap()) }
                }
                combine(lists.observations, searches.presets) { observations, saved -> observations to saved }.collect { (observations, saved) ->
                    mutex.withLock {
                        reconcilePlans(saved)
                        val presets = saved.filter { it.id in _state.value.plans }
                        observations.values.sortedBy { it.revision }.forEach { observation ->
                            if (seen[observation.query] == observation.revision) return@forEach
                            process(observation, presets)
                            seen[observation.query] = observation.revision
                        }
                        persist()
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(loaded = false, error = "レーダーの読み込み・保存に失敗しました。再試行してください。保存済みデータは保持しています。") } }
        }
    }
    suspend fun setPlan(id: String, enabled: Boolean) {
        if (enabled) require(searches.presets.first().any { it.id == id }) { "保存した条件が見つかりません。" }
        edit {
            resetPlan(id)
            it.copy(plans = if (enabled) it.plans + id else it.plans - id, nextPages = it.nextPages - id)
        }
    }
    /** 実行中の通信は完了させ、以降の計画リクエストは送らない。部屋追跡は別に保持する。 */
    suspend fun pauseAllPlans() = edit {
        it.plans.forEach(::resetPlan)
        it.copy(plans = emptySet(), nextPages = emptyMap())
    }
    private fun resetPlan(id: String) {
        baselines.keys.removeAll { it.startsWith("$id/") }
        knownMatches.keys.removeAll { it.startsWith("$id/") }
        nextPages.keys.removeAll { it.startsWith("$id/") }
    }
    private fun reconcilePlans(saved: List<SearchPreset>) {
        val keys = saved.associate { it.id to planKey(it) }
        val changed = planKeys.keys.filter { keys[it] != planKeys[it] }.toSet()
        changed.forEach(::resetPlan)
        planKeys.clear(); planKeys.putAll(keys)
        _state.update { it.copy(plans = it.plans.intersect(keys.keys), scopes = it.scopes - changed,
            results = it.results - changed, nextPages = it.nextPages - changed) }
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
        val oldBaselines = baselines.toMap()
        val oldKnown = knownMatches.mapValues { it.value.toMutableSet() }
        val oldNext = nextPages.toMap()
        try {
            _state.value = block(old).copy(error = null)
            persist()
        } catch (e: Exception) {
            _state.value = old
            baselines.clear(); baselines.putAll(oldBaselines)
            knownMatches.clear(); knownMatches.putAll(oldKnown)
            nextPages.clear(); nextPages.putAll(oldNext)
            throw e
        }
    }
    suspend fun scan() {
        mutex.withLock {
            check(_state.value.loaded) { "読み込み中です。" }
            if (_state.value.running) return
            _state.update { it.copy(running = true, error = null) }
        }
        try {
            val saved = searches.presets.first()
            val selected = saved.filter { it.id in _state.value.plans }
            // 同じジャンル・ページを複数の計画が要求しても、通信は1回だけ。
            val scheduled = mutex.withLock {
                reconcilePlans(saved)
                val requests = linkedMapOf<RoomQuery, MutableList<SearchPreset>>()
                selected.forEach { preset ->
                    Genres[preset.genreKey]?.let { genre ->
                        val query = RoomQuery(genre, page = nextPages[planKey(preset)] ?: 1)
                        requests.getOrPut(query) { mutableListOf() }.add(preset)
                    }
                }
                _state.value.targets.filter { it.evidence != RoomIdentityEvidence.REUSED }.forEach { target ->
                    Genres[target.room.genreKey]?.let { requests.getOrPut(RoomQuery(it)) { mutableListOf() } }
                }
                requests.mapValues { it.value.toList() }
            }
            for ((query, scheduledPlans) in scheduled) {
                val stillNeeded = mutex.withLock {
                    val currentSaved = searches.presets.first()
                    scheduledPlans.any { scheduledPlan -> currentSaved.any { it.id in _state.value.plans && planKey(it) == planKey(scheduledPlan) } } ||
                        _state.value.targets.any { it.room.genreKey == query.genre.key && it.evidence != RoomIdentityEvidence.REUSED && query.page == 1 }
                }
                if (!stillNeeded) continue
                lists.fetch(query, force = true)
                val observation = lists.observation(query) ?: continue
                mutex.withLock {
                    val currentSaved = searches.presets.first()
                    reconcilePlans(currentSaved)
                    val active = currentSaved.filter { it.id in _state.value.plans }
                    if (seen[query] != observation.revision) { process(observation, active); seen[query] = observation.revision }
                    // 開始時にこのリクエストへ割り当てた計画だけを進める。
                    scheduledPlans.filter { scheduledPlan -> active.any { planKey(it) == planKey(scheduledPlan) } }.forEach { preset ->
                        nextPages[planKey(preset)] = if (observation.page.hasNextPage) observation.page.page + 1 else 1
                    }
                    _state.update { it.copy(nextPages = active.associate { p -> p.id to (nextPages[planKey(p)] ?: 1) }) }
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
            if (target.observationRevision > o.revision) return@map target
            val room = o.page.rooms.firstOrNull { roomIdentity(it) == roomIdentity(target.room) }
            val evidence = roomIdentityEvidence(target.identity, room)
            if (evidence == RoomIdentityEvidence.NOT_OBSERVED) return@map target // 1ページに無いだけで不在とは判断しない。
            if (evidence == RoomIdentityEvidence.MATCH && room != null) {
                if (target.confirmedAt != null && target.room.status != room.status) events += RadarEvent(o.confirmedAt, "${room.name}：${statusName(room.status)}を確認", rooms = listOf(room), page = o.page.page)
                target.copy(room = room, confirmedAt = o.confirmedAt, observedAt = o.confirmedAt, evidence = evidence, observationRevision = o.revision, observedPage = o.page.page)
            } else {
                if (evidence == RoomIdentityEvidence.REUSED) events += RadarEvent(o.confirmedAt, "${target.room.name}：同じIDに異なるプロフィール。追跡を停止しました", rooms = listOf(target.identity), page = o.page.page, blocked = true)
                target.copy(evidence = evidence, observedAt = o.confirmedAt, observationRevision = o.revision)
            }
        }
        val scopes = current.scopes.toMutableMap()
        val results = current.results.toMutableMap()
        presets.filter { it.genreKey == o.query.genre.key }.forEach { preset ->
            // フィルタ済みの通信結果を無条件一覧の巡回基準には使わない。
            if (o.query != RoomQuery(o.query.genre, page = o.query.page)) return@forEach
            val scopeKey = planKey(preset)
            val key = "$scopeKey/${o.query.page}"
            val matches = searchRooms(o.page.rooms.filterNot { room -> hidden.any { it.appliesTo(room) } }, preset.criteria)
            val identities = matches.map { "${roomIdentity(it)}/${it.name}/${it.gender}/${it.age}" }.toSet()
            val known = knownMatches.getOrPut(scopeKey) { mutableSetOf() }
            val previous = baselines[key]
            if (previous != null) {
                val added = identities - previous - known
                if (added.isNotEmpty()) events += RadarEvent(o.confirmedAt, "${preset.label}：${o.page.page}ページで新しい一致を${added.size}件確認",
                    rooms = matches.filter { "${roomIdentity(it)}/${it.name}/${it.gender}/${it.age}" in added }.take(20), page = o.page.page)
            }
            known.addAll(identities)
            baselines[key] = identities
            results[preset.id] = RadarResult(preset.id, o.confirmedAt, o.page.page, o.page.lastPage, matches, preset.genreKey, preset.criteria)
            scopes[preset.id] = "${formatObservationTime(o.confirmedAt)} · ${o.page.page}/${o.page.lastPage}ページ · 一致${matches.size}件"
        }
        _state.value = current.copy(targets = targets, events = (events + current.events).take(100), scopes = scopes, results = results)
    }
    private fun planKey(preset: SearchPreset): String = "${preset.id}/${preset.genreKey}/${preset.criteria}"
    private suspend fun persist() {
        val s = _state.value
        val json = JSONObject().put("plans", JSONArray(s.plans.toList()))
            .put("targets", JSONArray(s.targets.map { t -> JSONObject().put("room", roomJson(t.room)).put("identity", roomJson(t.identity)).put("at", t.confirmedAt).put("observedAt", t.observedAt).put("evidence", t.evidence.name).put("page", t.observedPage) }))
            .put("events", JSONArray(s.events.map { JSONObject().put("at", it.at).put("text", it.text).put("id", it.id).put("rooms", JSONArray(it.rooms.map(::roomJson))).put("page", it.page).put("blocked", it.blocked) }))
        // ページ単位の基準・巡回位置は再起動時に捨て、初回大量通知を避ける。
        dao.put(LocalState(KEY, json.toString()))
    }
    private fun restore(value: String) {
        val json = JSONObject(value)
        val plans = json.optJSONArray("plans") ?: JSONArray()
        val targets = json.optJSONArray("targets") ?: JSONArray()
        val events = json.optJSONArray("events") ?: JSONArray()
        _state.value = RadarState(plans = (0 until plans.length()).map { plans.getString(it) }.toSet(),
            targets = (0 until targets.length()).map { i -> val t = targets.getJSONObject(i); TrackedRoom(readRoom(t.getJSONObject("room")), if (t.isNull("at")) null else t.getLong("at"), RoomIdentityEvidence.valueOf(t.getString("evidence")), if (t.has("identity")) readRoom(t.getJSONObject("identity")) else readRoom(t.getJSONObject("room")), if (t.isNull("observedAt")) (if (t.isNull("at")) null else t.getLong("at")) else t.getLong("observedAt"), observedPage = t.optInt("page", 1).coerceAtLeast(1)) },
            events = (0 until events.length()).map { i -> events.getJSONObject(i).let { RadarEvent(it.getLong("at"), it.getString("text"), it.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                rooms = it.optJSONArray("rooms")?.let { r -> (0 until r.length()).map { i -> readRoom(r.getJSONObject(i)) } } ?: emptyList(),
                page = if (it.isNull("page")) null else it.getInt("page").coerceAtLeast(1), blocked = it.optBoolean("blocked")) } })
    }
    private fun roomJson(r: Room) = JSONObject().put("id", r.id).put("genre", r.genreKey).put("status", r.status.name).put("action", r.action.name)
        .put("name", r.name).put("gender", r.gender.name).put("age", r.age).put("area", r.area).put("message", r.message)
    private fun readRoom(j: JSONObject) = Room(j.getLong("id"), j.getString("genre"), RoomStatus.valueOf(j.getString("status")), RoomAction.valueOf(j.getString("action")), null,
        if (j.isNull("name")) null else j.getString("name"), Gender.valueOf(j.getString("gender")), if (j.isNull("age")) null else j.getInt("age"), if (j.isNull("area")) null else j.getString("area"), j.getString("message"))
    private companion object { const val KEY = "radar_v1" }
}

fun formatObservationTime(at: Long): String = java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.JAPAN).format(java.util.Date(at))
fun statusName(status: RoomStatus) = when (status) { RoomStatus.WAITING -> "待機中"; RoomStatus.PUBLIC_WAITING -> "公開待機"; RoomStatus.FULL -> "満室" }
