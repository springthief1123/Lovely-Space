package io.github.springthief1123.lovelyspace.data

import io.github.springthief1123.lovelyspace.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** 端末内に保存するレーダー。前面表示中に自動巡回し、利用者が選んだ計画だけ背景でも巡回する（BackgroundSync）。入室は行わない。 */
data class TrackedRoom(val room: Room, val confirmedAt: Long? = null, val evidence: RoomIdentityEvidence = RoomIdentityEvidence.NOT_OBSERVED, val identity: Room = room, val observedAt: Long? = null,
    /** このRoomListRepository内だけの取得順序。再起動時は0から照合し直すため永続化しない。 */
    val observationRevision: Long = 0,
    val observedPage: Int = 1,
    val sourceQuery: RoomQuery? = null,
    val pinned: Boolean = false,
    val note: String = "",
)
data class RadarEvent(val at: Long, val text: String, val id: String = java.util.UUID.randomUUID().toString(),
    val rooms: List<Room> = emptyList(), val page: Int? = null, val blocked: Boolean = false, val sourceQuery: RoomQuery? = null,
    val kind: RadarEventKind = RadarEventKind.LEGACY, val origin: RadarEventOrigin? = null, val read: Boolean = false,
    /** 背景の巡回で記録し、まだ端末通知に出していない。通知を出し終えるまで保存しておき、途中で止まっても次の実行で出す。 */
    val pendingNotice: Boolean = false)
data class RadarResult(val presetId: String, val at: Long, val page: Int, val lastPage: Int, val rooms: List<Room>, val genreKey: String, val criteria: RoomSearchCriteria)
private data class ScheduledRadarQuery(val plans: List<SearchPreset>, val candidates: List<CandidateRule>)
data class RadarState(
    val plans: Set<String> = emptySet(), val targets: List<TrackedRoom> = emptyList(),
    val events: List<RadarEvent> = emptyList(), val running: Boolean = false,
    val loaded: Boolean = false, val error: String? = null,
    val scopes: Map<String, String> = emptyMap(),
    val results: Map<String, RadarResult> = emptyMap(),
    val nextPages: Map<String, Int> = emptyMap(),
    val candidateRules: List<CandidateRule> = emptyList(),
    val candidateResults: Map<String, CandidateResult> = emptyMap(),
    val nextCandidatePages: Map<String, Int> = emptyMap(),
    val lastScan: RadarScanReport? = null,
    val lastConfirmedAt: Long? = null,
    val targetSort: RadarTargetSort = RadarTargetSort.LAST_CONFIRMED,
    val automatic: Boolean = true,
    val livePages: Map<String, RoomPageWindow> = emptyMap(),
    /** アプリを閉じていても巡回する計画。有効な計画（[plans]）のうち、ここにあるものだけを背景で巡回する。 */
    val backgroundPlans: Set<String> = emptySet(),
    /** 背景で巡回する間隔（分）。WorkManager の最短 15 分以上。 */
    val backgroundIntervalMinutes: Int = BACKGROUND_INTERVALS.first(),
) {
    val unreadEvents: Int get() = events.count { !it.read }
    /** 背景で巡回する計画（有効なものだけ）。 */
    val activeBackgroundPlans: Set<String> get() = plans.intersect(backgroundPlans)
    companion object {
        /** 背景で巡回する間隔の選択肢（分）。 */
        val BACKGROUND_INTERVALS = listOf(15, 30, 60)
    }
    fun resultFor(preset: SearchPreset): RadarResult? = results[preset.id]?.takeIf { it.genreKey == preset.genreKey && it.criteria == preset.criteria }
    fun resultFor(rule: CandidateRule): CandidateResult? = candidateResults[rule.id]?.takeIf { it.ruleKey == rule.key }
}

class RadarRepository(
    private val dao: PresetDao,
    private val lists: ObservedRoomListSource,
    private val searches: SearchPresetStore,
    private val preferences: RoomPreferenceStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val sync = ListSync(lists)
    private val _state = MutableStateFlow(RadarState())
    val state = _state.asStateFlow()
    private val seen = mutableMapOf<RoomQuery, Long>()
    private val baselines = mutableMapOf<String, Set<String>>()
    /** 比較基準をページの取得で最後に作り直した時刻。保存した基準の期限と、背景で確認する順番に使う。 */
    private val baselineAt = mutableMapOf<String, Long>()
    /** 背景の巡回で各計画（planKey）の取得を最後に試した時刻。成否を問わず進め、保存して次のプロセスへ引き継ぐ。 */
    private val backgroundTriedAt = mutableMapOf<String, Long>()
    private val knownMatches = mutableMapOf<String, MutableSet<String>>()
    private val nextPages = mutableMapOf<String, Int>()
    private val planKeys = mutableMapOf<String, String>()
    private val evaluatedPlans = mutableMapOf<String, Long>()
    private val candidateBaselines = mutableMapOf<String, Set<String>>()
    private val candidateKnown = mutableMapOf<String, MutableSet<String>>()
    private val candidateNext = mutableMapOf<String, Int>()
    private val evaluatedCandidates = mutableMapOf<String, Long>()
    private var observationJob: Job? = null
    init { reload() }
    fun reload() {
        if (_state.value.running) return
        observationJob?.cancel()
        _state.update { it.copy(loaded = false, error = null) }
        observationJob = scope.launch {
            try {
                mutex.withLock {
                    val saved = dao.state(KEY)
                    saved?.let(::restore)
                    seen.clear(); baselines.clear(); baselineAt.clear(); knownMatches.clear(); nextPages.clear(); planKeys.clear(); candidateBaselines.clear(); candidateKnown.clear(); candidateNext.clear(); evaluatedPlans.clear(); evaluatedCandidates.clear()
                    backgroundTriedAt.clear()
                    saved?.let(::restoreBaselines)
                    _state.update { it.copy(loaded = true, scopes = emptyMap(), results = emptyMap(), nextPages = emptyMap(), candidateResults = emptyMap(), nextCandidatePages = emptyMap(), lastScan = null, lastConfirmedAt = null, livePages = emptyMap()) }
                }
                combine(lists.observations, searches.presets) { _, _ -> Unit }.collect {
                    mutex.withLock {
                        // ロック待ち中に更新された定義・一覧を、古いFlowの値で戻さない。
                        val saved = searches.presets.first()
                        val observations = lists.observations.value
                        reconcilePlans(saved)
                        observations.values.sortedBy { it.revision }.forEach { observation ->
                            if ((seen[observation.query] ?: 0) >= observation.revision) return@forEach
                            process(observation, emptyList())
                            seen[observation.query] = observation.revision
                            _state.update { state -> state.copy(lastConfirmedAt = maxOf(state.lastConfirmedAt ?: observation.confirmedAt, observation.confirmedAt)) }
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
            it.copy(plans = if (enabled) it.plans + id else it.plans - id, nextPages = it.nextPages - id,
                backgroundPlans = if (enabled) it.backgroundPlans else it.backgroundPlans - id)
        }
    }
    /** 計画をアプリを閉じていても巡回するか。オンにすると計画そのものも有効にする。 */
    suspend fun setPlanBackground(id: String, enabled: Boolean) {
        if (enabled) require(searches.presets.first().any { it.id == id }) { "保存した条件が見つかりません。" }
        edit { state ->
            if (enabled && id !in state.plans) resetPlan(id)
            state.copy(plans = if (enabled) state.plans + id else state.plans,
                backgroundPlans = if (enabled) state.backgroundPlans + id else state.backgroundPlans - id)
        }
    }
    suspend fun setBackgroundInterval(minutes: Int) {
        require(minutes in RadarState.BACKGROUND_INTERVALS) { "巡回の間隔を選び直してください。" }
        edit { it.copy(backgroundIntervalMinutes = minutes) }
    }
    /** 実行中の通信は完了させ、以降の計画リクエストは送らない。部屋追跡は別に保持する。 */
    suspend fun pauseAllPlans() = edit {
        it.plans.forEach(::resetPlan)
        it.candidateRules.forEach { rule -> resetCandidate(rule.id) }
        it.copy(plans = emptySet(), backgroundPlans = emptySet(), nextPages = emptyMap(), candidateRules = it.candidateRules.map { rule -> rule.copy(enabled = false) }, nextCandidatePages = emptyMap())
    }
    suspend fun saveCandidate(rule: CandidateRule) = edit { state ->
        require(rule.id.isNotBlank() && rule.label.isNotBlank() && rule.label.length <= 80 && rule.term.isNotBlank() && rule.term.length <= 160 && Genres[rule.genreKey] != null) { "候補条件の入力を確認してください。" }
        require(state.candidateRules.size < 20 || state.candidateRules.any { it.id == rule.id }) { "候補監視は20件までです。" }
        val value = rule.copy(label = rule.label.trim(), term = rule.term.trim())
        val old = state.candidateRules.firstOrNull { it.id == rule.id }
        val changed = old?.key != value.key || old?.enabled != value.enabled
        if (changed) resetCandidate(rule.id)
        state.copy(candidateRules = state.candidateRules.filterNot { it.id == value.id } + value,
            candidateResults = if (changed) state.candidateResults - value.id else state.candidateResults,
            nextCandidatePages = if (changed) state.nextCandidatePages - value.id else state.nextCandidatePages)
    }
    suspend fun setCandidateEnabled(id: String, enabled: Boolean) = edit { state ->
        require(state.candidateRules.any { it.id == id }) { "候補条件が見つかりません。" }
        resetCandidate(id)
        state.copy(candidateRules = state.candidateRules.map { if (it.id == id) it.copy(enabled = enabled) else it }, nextCandidatePages = state.nextCandidatePages - id)
    }
    suspend fun removeCandidate(id: String) = edit { state ->
        resetCandidate(id)
        state.copy(candidateRules = state.candidateRules.filterNot { it.id == id }, candidateResults = state.candidateResults - id, nextCandidatePages = state.nextCandidatePages - id)
    }
    private fun resetCandidate(id: String) {
        candidateBaselines.keys.removeAll { it.startsWith("$id/") }
        candidateKnown.keys.removeAll { it.startsWith("$id/") }
        candidateNext.keys.removeAll { it.startsWith("$id/") }
        evaluatedCandidates.keys.removeAll { it.startsWith("$id/") }
    }
    private fun resetPlan(id: String) {
        baselines.keys.removeAll { it.startsWith("$id/") }
        baselineAt.keys.removeAll { it.startsWith("$id/") }
        knownMatches.keys.removeAll { it.startsWith("$id/") }
        nextPages.keys.removeAll { it.startsWith("$id/") }
        evaluatedPlans.keys.removeAll { it.startsWith("$id/") }
    }
    private fun reconcilePlans(saved: List<SearchPreset>) {
        val keys = saved.associate { it.id to planKey(it) }
        val changed = planKeys.keys.filter { keys[it] != planKeys[it] }.toSet()
        changed.forEach(::resetPlan)
        planKeys.clear(); planKeys.putAll(keys)
        _state.update { it.copy(plans = it.plans.intersect(keys.keys), backgroundPlans = it.backgroundPlans.intersect(keys.keys), scopes = it.scopes - changed,
            results = it.results - changed, nextPages = it.nextPages - changed) }
    }
    suspend fun track(room: Room, sourceQuery: RoomQuery? = null) = edit {
        require(room.name != null) { "プロフィールを確認できる部屋を選んでください。" }
        val key = roomIdentity(room)
        require(sourceQuery == null || (sourceQuery.genre.key == room.genreKey && sourceQuery.page >= 1)) { "一覧の取得条件が一致しません。" }
        // 検索・保存画面では、共有一覧に残る同じ公開プロフィールの最新取得条件を引き継ぐ。
        val observed = lists.observations.value.values.filter { observation ->
            observation.query.genre.key == room.genreKey && observation.page.rooms.any { roomIdentity(it) == key && roomIdentityEvidence(room, it) == RoomIdentityEvidence.MATCH }
        }.maxByOrNull { it.revision }
        val query = sourceQuery ?: observed?.query
        it.copy(targets = it.targets.filterNot { t -> roomIdentity(t.room) == key } + TrackedRoom(room, observedPage = query?.page ?: 1, sourceQuery = query))
    }
    /** 呼び出し時に渡されたIDだけを既読にし、保存中に到着した新しい履歴は含めない。 */
    suspend fun markEventsRead(ids: Set<String>) = mutex.withLock {
        // 既読・削除済みの記録を開くために、不要な書き込みを要求しない。
        if (_state.value.events.none { it.id in ids && !it.read }) return@withLock
        check(_state.value.loaded) { "読み込み中です。" }
        transaction {
            _state.update { state -> state.copy(events = state.events.map { if (it.id in ids) it.copy(read = true) else it }) }
            persist()
        }
    }
    /** 背景の巡回で記録し、まだ端末通知に出していない履歴。 */
    fun pendingNotices(): List<RadarEvent> = _state.value.events.filter { it.pendingNotice }
    /** 端末通知に出し終えた履歴を記録する。 */
    suspend fun markNoticed(ids: Set<String>) = mutex.withLock {
        if (_state.value.events.none { it.id in ids && it.pendingNotice }) return@withLock
        transaction {
            _state.update { state -> state.copy(events = state.events.map { if (it.id in ids) it.copy(pendingNotice = false) else it }) }
            persist()
        }
    }
    suspend fun setTargetSort(sort: RadarTargetSort) = edit { it.copy(targetSort = sort) }
    suspend fun updateTarget(identity: Room, pinned: Boolean? = null, note: String? = null) = edit { state ->
        require(note == null || note.length <= 500) { "メモは500文字までです。" }
        require(state.targets.any { it.identity == identity }) { "追跡先が変更されています。開き直してください。" }
        state.copy(targets = state.targets.map { target -> if (target.identity == identity)
            target.copy(pinned = pinned ?: target.pinned, note = note ?: target.note) else target })
    }
    suspend fun removeTarget(room: Room) = edit { it.copy(targets = it.targets.filterNot { t -> roomIdentity(t.room) == roomIdentity(room) }) }
    private suspend fun edit(block: (RadarState) -> RadarState) = mutex.withLock {
        check(_state.value.loaded) { "読み込み中です。" }
        transaction {
            val edited = block(_state.value).copy(error = null)
            _state.update { edited.copy(automatic = it.automatic) }
            persist()
        }
    }
    /** 1ページの反映・保存をまとめ、失敗時は起動中の比較基準や取得順序も戻す。 */
    private suspend fun transaction(block: suspend () -> Unit) {
        val old = _state.value
        val oldSeen = seen.toMap()
        val oldPlanKeys = planKeys.toMap()
        val oldBaselines = baselines.toMap()
        val oldBaselineAt = baselineAt.toMap()
        val oldKnown = knownMatches.mapValues { it.value.toMutableSet() }
        val oldNext = nextPages.toMap()
        val oldCandidateBaselines = candidateBaselines.toMap()
        val oldCandidateKnown = candidateKnown.mapValues { it.value.toMutableSet() }
        val oldCandidateNext = candidateNext.toMap()
        val oldEvaluatedPlans = evaluatedPlans.toMap()
        val oldEvaluatedCandidates = evaluatedCandidates.toMap()
        try {
            block()
        } catch (e: Exception) {
            _state.update { old.copy(automatic = it.automatic) }
            seen.clear(); seen.putAll(oldSeen)
            planKeys.clear(); planKeys.putAll(oldPlanKeys)
            baselines.clear(); baselines.putAll(oldBaselines)
            baselineAt.clear(); baselineAt.putAll(oldBaselineAt)
            knownMatches.clear(); knownMatches.putAll(oldKnown)
            nextPages.clear(); nextPages.putAll(oldNext)
            candidateBaselines.clear(); candidateBaselines.putAll(oldCandidateBaselines)
            candidateKnown.clear(); candidateKnown.putAll(oldCandidateKnown)
            candidateNext.clear(); candidateNext.putAll(oldCandidateNext)
            evaluatedPlans.clear(); evaluatedPlans.putAll(oldEvaluatedPlans)
            evaluatedCandidates.clear(); evaluatedCandidates.putAll(oldEvaluatedCandidates)
            throw e
        }
    }
    fun automatic(enabled: Boolean) = _state.update { it.copy(automatic = enabled) }

    /** 新着を優先しながら、間に残りのページを取得する。画面のLifecycleが実行を管理する。 */
    suspend fun monitor() {
        var headAt: Long? = null
        while (currentCoroutineContext().isActive && _state.value.automatic) {
            val now = System.nanoTime() / 1_000_000
            if (_state.value.loaded && !_state.value.running &&
                (_state.value.plans.isNotEmpty() || _state.value.candidateRules.any { it.enabled } || _state.value.targets.any { it.evidence != RoomIdentityEvidence.REUSED })) {
                val head = headAt == null || now - headAt >= RoomPageSchedule.HEAD_INTERVAL_MS
                scan(latestFirst = head, force = false)
                if (head) headAt = System.nanoTime() / 1_000_000
            }
            delay(if (_state.value.error != null) RoomPageSchedule.HEAD_INTERVAL_MS else RoomPageSchedule.STEP_INTERVAL_MS)
        }
    }

    /**
     * [maxPages] は1回の巡回で取得するページ数の上限（背景実行で使う）。超えたページは次回に回す。
     * [backgroundOnly] では背景で巡回する計画だけを確認し、候補条件・部屋の追跡は前面でだけ確認する。
     * 戻り値はこの巡回で新しく記録した履歴。
     */
    suspend fun scan(latestFirst: Boolean = false, force: Boolean = true, maxPages: Int = Int.MAX_VALUE, backgroundOnly: Boolean = false): List<RadarEvent> {
        fun plansOf(state: RadarState) = if (backgroundOnly) state.activeBackgroundPlans else state.plans
        val previousEvents = mutex.withLock {
            check(_state.value.loaded) { "読み込み中です。" }
            if (_state.value.running) return emptyList()
            _state.update { it.copy(running = true, error = null, lastScan = null) }
            _state.value.events.map { it.id }.toSet()
        }
        var interrupted = false
        try {
            // 計画の定義と選択は同じロック区間で固定し、開始前の変更を巻き戻さない。
            val scheduled = mutex.withLock {
                val saved = searches.presets.first()
                reconcilePlans(saved)
                // 背景の実行はプロセスごと作り直されることがあるので、ページ数の上限で後回しになった計画や
                // 取得に失敗し続ける計画で順番が止まらないよう、取得を試していない（試したのが古い）計画から順に取得する。
                val selected = saved.filter { it.id in plansOf(_state.value) }
                    .let { plans -> if (backgroundOnly) plans.sortedBy { backgroundTriedAt[planKey(it)] ?: Long.MIN_VALUE } else plans }
                val requests = linkedMapOf<RoomQuery, MutableList<SearchPreset>>()
                selected.forEach { preset ->
                    Genres[preset.genreKey]?.let { genre ->
                        val query = RoomQuery(genre, page = if (latestFirst) 1 else nextPages[planKey(preset)] ?: 1)
                        requests.getOrPut(query) { mutableListOf() }.add(preset)
                    }
                }
                val candidates = if (backgroundOnly) emptyList() else _state.value.candidateRules.filter { it.enabled }
                candidates.forEach { rule ->
                    Genres[rule.genreKey]?.let { genre -> requests.getOrPut(RoomQuery(genre, page = if (latestFirst) 1 else candidateNext[rule.key] ?: 1)) { mutableListOf() } }
                }
                if (!backgroundOnly) _state.value.targets.filter { it.evidence != RoomIdentityEvidence.REUSED }.forEach { target ->
                    target.sourceQueryOrLegacy()?.let { requests.getOrPut(it) { mutableListOf() } }
                }
                _state.update { it.copy(lastScan = RadarScanReport(System.currentTimeMillis(), requests.keys.map { query -> RadarPageCheck(query) })) }
                requests.mapValues { (query, plans) -> ScheduledRadarQuery(plans.toList(), candidates.filter { it.genreKey == query.genre.key && (if (latestFirst) 1 else candidateNext[it.key] ?: 1) == query.page }) }
            }
            val before = mutableMapOf<RoomQuery, Long>()
            sync.sync(scheduled.keys, maxPages, force,
                shouldFetch = { query ->
                    val request = scheduled.getValue(query)
                    val stillNeeded = mutex.withLock {
                        val currentSaved = searches.presets.first()
                        request.plans.any { scheduledPlan -> currentSaved.any { it.id in plansOf(_state.value) && planKey(it) == planKey(scheduledPlan) } } ||
                            request.candidates.any { scheduledRule -> _state.value.candidateRules.any { it.enabled && it.key == scheduledRule.key } } ||
                            (!backgroundOnly && _state.value.targets.any { it.sourceQueryOrLegacy() == query && it.evidence != RoomIdentityEvidence.REUSED })
                    }
                    if (!stillNeeded) updateCheck(query, RadarCheckStatus.SKIPPED, message = "条件の停止・変更により取得を見送りました。")
                    else {
                        if (backgroundOnly) mutex.withLock { request.plans.forEach { backgroundTriedAt[planKey(it)] = now() } }
                        updateCheck(query, RadarCheckStatus.CHECKING)
                        before[query] = lists.observation(query)?.revision ?: 0
                    }
                    stillNeeded
                },
                onResult = { query, outcome ->
                    when (outcome) {
                        is ListSyncOutcome.Fetched -> applyFetched(query, scheduled.getValue(query), before[query] ?: 0, force, latestFirst, ::plansOf, notice = backgroundOnly)
                        is ListSyncOutcome.Failed -> updateCheck(query, RadarCheckStatus.FAILED, message = "一覧を取得できませんでした。次の巡回で同じページを確認します。")
                        ListSyncOutcome.Deferred -> updateCheck(query, RadarCheckStatus.SKIPPED, message = "1回の巡回で取得するページ数の上限に達したため、次の巡回で確認します。")
                        ListSyncOutcome.Skipped -> Unit
                    }
                })
            // 取得に失敗したページの試行順も次のプロセスへ残す（成功したページは反映時に保存済み）。
            if (backgroundOnly) mutex.withLock { persist() }
        } catch (e: CancellationException) { interrupted = true; throw e }
        catch (e: Exception) {
            interrupted = true
            _state.update { it.copy(error = "巡回を続けられませんでした。確認済みの情報は保持しています。設定・通信状況を確認してください。") }
        } finally {
            _state.update { state ->
                val report = state.lastScan?.let { run -> run.copy(finishedAt = System.currentTimeMillis(), interrupted = interrupted,
                    newEvents = state.events.count { it.id !in previousEvents },
                    pages = run.pages.map { page -> when (page.status) {
                        RadarCheckStatus.CHECKING -> page.copy(status = RadarCheckStatus.FAILED, message = "このページの確認を完了できませんでした。")
                        RadarCheckStatus.PENDING -> page.copy(status = RadarCheckStatus.SKIPPED, message = "巡回の中断により取得していません。")
                        else -> page
                    } }) }
                state.copy(running = false, lastScan = report,
                    error = state.error ?: if (report != null && report.failed > 0) "取得できなかったページがあります。確認済みの結果は保持しています。" else null)
            }
        }
        return _state.value.events.filter { it.id !in previousEvents }
    }
    /** 取得した1ページを各計画・候補・追跡先へ反映する。 */
    private suspend fun applyFetched(query: RoomQuery, request: ScheduledRadarQuery, before: Long, force: Boolean, latestFirst: Boolean, plansOf: (RadarState) -> Set<String>, notice: Boolean) {
        val observation = lists.observation(query)
        if (observation == null || observation.query != query || (force && observation.revision <= before)) {
            updateCheck(query, RadarCheckStatus.FAILED, message = "新しい一覧を確認できませんでした。次の巡回で同じページを確認します。")
            return
        }
        mutex.withLock {
            transaction {
                val currentSaved = searches.presets.first()
                reconcilePlans(currentSaved)
                val active = currentSaved.filter { it.id in _state.value.plans }
                val scheduledPlans = active.filter { preset -> preset.id in plansOf(_state.value) && request.plans.any { planKey(it) == planKey(preset) } }
                val scheduledCandidates = _state.value.candidateRules.filter { rule -> rule.enabled && request.candidates.any { it.key == rule.key } }
                process(observation, scheduledPlans, scheduledCandidates, notice)
                seen[query] = maxOf(seen[query] ?: 0, observation.revision)
                if (!latestFirst) scheduledPlans.forEach { preset ->
                    nextPages[planKey(preset)] = if (observation.page.hasNextPage) observation.page.page + 1 else 1
                }
                if (!latestFirst) scheduledCandidates.forEach { rule ->
                    candidateNext[rule.key] = if (observation.page.hasNextPage) observation.page.page + 1 else 1
                }
                val hidden = preferences.preferences.first().filter { it.hidden }
                val trackedMatches = _state.value.targets.filter { target ->
                    target.sourceQueryOrLegacy() == query &&
                        target.evidence == RoomIdentityEvidence.MATCH &&
                        target.observationRevision == observation.revision &&
                        target.confirmedAt == observation.confirmedAt &&
                        hidden.none { it.appliesTo(target.room) }
                }.map { it.room }
                val matches = (scheduledPlans.flatMap { _state.value.resultFor(it)?.rooms.orEmpty() } +
                    scheduledCandidates.flatMap { _state.value.resultFor(it)?.rooms.orEmpty() } +
                    trackedMatches).distinctBy(::roomIdentity).size
                _state.update { state -> state.copy(nextPages = active.associate { p -> p.id to (nextPages[planKey(p)] ?: 1) },
                    nextCandidatePages = state.candidateRules.filter { it.enabled }.associate { it.id to (candidateNext[it.key] ?: 1) }) }
                persist()
                updateCheck(query, RadarCheckStatus.CONFIRMED, observation.confirmedAt, matches)
            }
        }
    }
    private fun updateCheck(query: RoomQuery, status: RadarCheckStatus, at: Long? = null, matches: Int = 0, message: String? = null) {
        _state.update { state -> state.copy(lastScan = state.lastScan?.let { report ->
            report.copy(pages = report.pages.map { if (it.query == query) it.copy(status = status, confirmedAt = at, matches = matches, message = message) else it })
        }, lastConfirmedAt = if (at != null) maxOf(state.lastConfirmedAt ?: at, at) else state.lastConfirmedAt) }
    }
    private suspend fun process(o: ObservedRoomPage, presets: List<SearchPreset>, candidates: List<CandidateRule> = emptyList(), notice: Boolean = false) {
        val freshTracking = (seen[o.query] ?: 0) < o.revision
        if (freshTracking) preferences.observe(o.page.rooms)
        val hidden = preferences.preferences.first().filter { it.hidden }
        val current = _state.value
        val events = mutableListOf<RadarEvent>()
        val targets = current.targets.map { target ->
            if (!freshTracking) return@map target
            if (target.room.genreKey != o.query.genre.key || target.evidence == RoomIdentityEvidence.REUSED) return@map target
            if (target.observationRevision > o.revision) return@map target
            val room = o.page.rooms.firstOrNull { roomIdentity(it) == roomIdentity(target.room) }
            val evidence = roomIdentityEvidence(target.identity, room)
            if (evidence == RoomIdentityEvidence.NOT_OBSERVED) return@map target // 1ページに無いだけで不在とは判断しない。
            if (evidence == RoomIdentityEvidence.MATCH && room != null) {
                if (target.confirmedAt != null && target.room.status != room.status) events += RadarEvent(o.confirmedAt, "${room.name}：${statusName(room.status)}を確認", rooms = listOf(room), page = o.page.page, sourceQuery = o.query, kind = RadarEventKind.ROOM_STATUS,
                    origin = RadarEventOrigin(RadarOriginType.ROOM, roomIdentity(target.identity), target.identity.name.orEmpty()))
                target.copy(room = room, confirmedAt = o.confirmedAt, observedAt = o.confirmedAt, evidence = evidence, observationRevision = o.revision, observedPage = o.page.page, sourceQuery = o.query)
            } else {
                if (evidence == RoomIdentityEvidence.REUSED) events += RadarEvent(o.confirmedAt, "${target.room.name}：同じIDに異なるプロフィール。追跡を停止しました", rooms = listOf(target.identity), page = o.page.page, blocked = true, sourceQuery = o.query, kind = RadarEventKind.IDENTITY_WARNING,
                    origin = RadarEventOrigin(RadarOriginType.ROOM, roomIdentity(target.identity), target.identity.name.orEmpty()))
                if (evidence == RoomIdentityEvidence.AMBIGUOUS && target.evidence != RoomIdentityEvidence.AMBIGUOUS) {
                    events += RadarEvent(o.confirmedAt, "${target.identity.name}：同じIDを確認しましたが、公開プロフィールを照合できません", rooms = listOf(target.identity),
                        page = o.page.page, sourceQuery = o.query, kind = RadarEventKind.PROFILE_UNCONFIRMED,
                        origin = RadarEventOrigin(RadarOriginType.ROOM, roomIdentity(target.identity), target.identity.name.orEmpty()))
                }
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
            if ((evaluatedPlans[key] ?: 0) >= o.revision) return@forEach
            evaluatedPlans[key] = o.revision
            val matches = searchRooms(o.page.rooms.filterNot { room -> hidden.any { it.appliesTo(room) } }, preset.criteria)
            val identities = matches.map { "${roomIdentity(it)}/${it.name}/${it.gender}/${it.age}" }.toSet()
            val known = knownMatches.getOrPut(scopeKey) { mutableSetOf() }
            val previous = baselines[key]
            if (previous != null) {
                val added = identities - previous - known
                if (added.isNotEmpty()) events += RadarEvent(o.confirmedAt, "${preset.label}：${o.page.page}ページで新しい一致を${added.size}件確認",
                    rooms = matches.filter { "${roomIdentity(it)}/${it.name}/${it.gender}/${it.age}" in added }.take(20), page = o.page.page, sourceQuery = o.query, kind = RadarEventKind.SEARCH_MATCH,
                    origin = RadarEventOrigin(RadarOriginType.PLAN, preset.id, preset.label, radarPlanDescription(preset)), pendingNotice = notice)
            }
            known.addAll(identities)
            baselines[key] = identities
            baselineAt[key] = now()
            results[preset.id] = RadarResult(preset.id, o.confirmedAt, o.page.page, o.page.lastPage, matches, preset.genreKey, preset.criteria)
            scopes[preset.id] = "${formatObservationTime(o.confirmedAt)} · ${o.page.page}/${o.page.lastPage}ページ · 一致${matches.size}件"
        }
        val candidateResults = current.candidateResults.toMutableMap()
        if (o.query == RoomQuery(o.query.genre, page = o.query.page)) {
            candidates.filter { it.enabled && it.genreKey == o.query.genre.key }.forEach { rule ->
                val matches = o.page.rooms.distinctBy(::roomIdentity).filter { room -> rule.matches(room) && hidden.none { it.appliesTo(room) } }
                val identities = matches.map { "${roomIdentity(it)}/${it.name}/${it.gender}/${it.age}" }.toSet()
                val key = "${rule.key}/${o.page.page}"
                if ((evaluatedCandidates[key] ?: 0) >= o.revision) return@forEach
                evaluatedCandidates[key] = o.revision
                val previous = candidateBaselines[key]
                val known = candidateKnown.getOrPut(rule.key) { mutableSetOf() }
                if (previous != null) {
                    val added = identities - previous - known
                    if (added.isNotEmpty()) events += RadarEvent(o.confirmedAt, "候補「${rule.label}」：${o.page.page}ページで新しい表示名の一致を${added.size}件確認",
                        rooms = matches.filter { "${roomIdentity(it)}/${it.name}/${it.gender}/${it.age}" in added }.take(20), page = o.page.page, sourceQuery = o.query, kind = RadarEventKind.CANDIDATE_MATCH,
                        origin = RadarEventOrigin(RadarOriginType.CANDIDATE, rule.id, rule.label, "${Genres[rule.genreKey]?.label ?: rule.genreKey} · ${if (rule.mode == CandidateMode.EXACT_NAME) "名前一致" else "表示名"}「${rule.term}」"))
                }
                known.addAll(identities)
                candidateBaselines[key] = identities
                candidateResults[rule.id] = CandidateResult(rule.key, o.confirmedAt, o.page.page, o.page.lastPage, matches)
            }
        }
        val livePages = if (freshTracking && o.query == RoomQuery(o.query.genre, page = o.query.page))
            current.livePages + (o.query.genre.key to (current.livePages[o.query.genre.key] ?: RoomPageWindow()).observe(o.page, o.revision)) else current.livePages
        _state.update { current.copy(automatic = it.automatic, livePages = livePages, targets = targets, events = (events + current.events).take(100), scopes = scopes, results = results, candidateResults = candidateResults) }
    }
    private fun planKey(preset: SearchPreset): String = "${preset.id}/${preset.genreKey}/${preset.criteria}"
    private suspend fun persist() {
        val s = _state.value
        val json = JSONObject().put("plans", JSONArray(s.plans.toList())).put("targetSort", s.targetSort.name)
            .put("targets", JSONArray(s.targets.map { t -> JSONObject().put("room", roomJson(t.room)).put("identity", roomJson(t.identity)).put("at", t.confirmedAt).put("observedAt", t.observedAt).put("evidence", t.evidence.name).put("page", t.observedPage).put("sourceQuery", radarQueryJson(t.sourceQuery)).put("pinned", t.pinned).put("note", t.note) }))
            .put("events", JSONArray(s.events.map { JSONObject().put("at", it.at).put("text", it.text).put("id", it.id).put("rooms", JSONArray(it.rooms.map(::roomJson))).put("page", it.page).put("blocked", it.blocked).put("sourceQuery", radarQueryJson(it.sourceQuery)).put("kind", it.kind.name).put("origin", radarOriginJson(it.origin)).put("read", it.read).put("pendingNotice", it.pendingNotice) }))
        json.put("candidateRules", JSONArray(s.candidateRules.map { rule -> JSONObject().put("id", rule.id).put("label", rule.label).put("genre", rule.genreKey)
            .put("term", rule.term).put("mode", rule.mode.name).put("enabled", rule.enabled) }))
        json.put("backgroundPlans", JSONArray(s.backgroundPlans.toList())).put("backgroundInterval", s.backgroundIntervalMinutes)
        // 背景の巡回は実行ごとにプロセスが作り直されるので、背景で巡回する計画の比較基準だけを、ページを取得した時刻つきで残す。
        // 古い基準は再起動時に捨て（[BASELINE_TTL_MS]）、久しぶりの起動で大量の「新しい一致」を出さない。巡回位置は残さない。
        val background = s.activeBackgroundPlans
        val backgroundKeys = planKeys.filterKeys { it in background }.values.toSet()
        fun ownedByBackground(key: String) = backgroundKeys.any { key == it || key.startsWith("$it/") }
        json.put("backgroundTried", JSONObject().apply { backgroundTriedAt.filterKeys { it in backgroundKeys }.forEach { (key, at) -> put(key, at) } })
        json.put("baselines", JSONObject().apply {
            put("pages", JSONObject().apply { baselines.filterKeys(::ownedByBackground).forEach { (key, ids) ->
                val at = baselineAt[key] ?: return@forEach
                put(key, JSONObject().put("at", at).put("ids", JSONArray(ids.toList())))
            } })
            put("known", JSONObject().apply { knownMatches.filterKeys(::ownedByBackground).forEach { (key, ids) ->
                // 一度一致した部屋は、その計画のいずれかのページを最後に取得した時刻で期限を判断する。
                val at = baselineAt.filterKeys { it.startsWith("$key/") }.values.maxOrNull() ?: return@forEach
                put(key, JSONObject().put("at", at).put("ids", JSONArray(ids.toList().takeLast(MAX_KNOWN))))
            } })
        })
        dao.put(LocalState(KEY, json.toString()))
    }
    private fun restoreBaselines(value: String) {
        JSONObject(value).optJSONObject("backgroundTried")?.let { tried -> tried.keys().forEach { key -> backgroundTriedAt[key] = tried.getLong(key) } }
        val saved = JSONObject(value).optJSONObject("baselines") ?: return
        val current = now()
        /** 期限内のものだけを（取得時刻, ID）で返す。 */
        fun JSONObject.fresh() = keys().asSequence().mapNotNull { key ->
            val entry = optJSONObject(key) ?: return@mapNotNull null
            val at = entry.optLong("at", Long.MIN_VALUE)
            if (at == Long.MIN_VALUE || current - at !in 0..BASELINE_TTL_MS) return@mapNotNull null
            val ids = entry.getJSONArray("ids").let { a -> (0 until a.length()).map(a::getString) }
            Triple(key, at, ids)
        }
        saved.optJSONObject("pages")?.fresh()?.forEach { (key, at, ids) -> baselines[key] = ids.toSet(); baselineAt[key] = at }
        saved.optJSONObject("known")?.fresh()?.forEach { (key, _, ids) -> knownMatches[key] = ids.toMutableSet() }
    }
    private fun restore(value: String) {
        val json = JSONObject(value)
        val plans = json.optJSONArray("plans") ?: JSONArray()
        val targets = json.optJSONArray("targets") ?: JSONArray()
        val events = json.optJSONArray("events") ?: JSONArray()
        val candidates = json.optJSONArray("candidateRules") ?: JSONArray()
        val restored = RadarState(targetSort = RadarTargetSort.entries.firstOrNull { it.name == json.optString("targetSort") } ?: RadarTargetSort.LAST_CONFIRMED, plans = (0 until plans.length()).map { plans.getString(it) }.toSet(),
            targets = (0 until targets.length()).map { i -> val t = targets.getJSONObject(i); TrackedRoom(readRoom(t.getJSONObject("room")), if (t.isNull("at")) null else t.getLong("at"), RoomIdentityEvidence.valueOf(t.getString("evidence")), if (t.has("identity")) readRoom(t.getJSONObject("identity")) else readRoom(t.getJSONObject("room")), if (t.isNull("observedAt")) (if (t.isNull("at")) null else t.getLong("at")) else t.getLong("observedAt"), observedPage = t.optInt("page", 1).coerceAtLeast(1), sourceQuery = readRadarQuery(t.optJSONObject("sourceQuery")), pinned = t.optBoolean("pinned"), note = t.optString("note").take(500)) },
            events = (0 until events.length()).map { i -> events.getJSONObject(i).let { RadarEvent(it.getLong("at"), it.getString("text"), it.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                rooms = it.optJSONArray("rooms")?.let { r -> (0 until r.length()).map { i -> readRoom(r.getJSONObject(i)) } } ?: emptyList(),
                page = if (it.isNull("page")) null else it.getInt("page").coerceAtLeast(1), blocked = it.optBoolean("blocked"), sourceQuery = readRadarQuery(it.optJSONObject("sourceQuery")),
                kind = RadarEventKind.entries.firstOrNull { kind -> kind.name == it.optString("kind") } ?: RadarEventKind.LEGACY,
                origin = readRadarOrigin(it.optJSONObject("origin")), read = if (it.has("read")) it.getBoolean("read") else true, pendingNotice = it.optBoolean("pendingNotice")) } },
            candidateRules = (0 until candidates.length()).map { i -> candidates.getJSONObject(i).let { CandidateRule(it.getString("id"), it.getString("label"), it.getString("genre"), it.getString("term"), CandidateMode.valueOf(it.getString("mode")), it.optBoolean("enabled", true)) } },
            backgroundPlans = json.optJSONArray("backgroundPlans")?.let { b -> (0 until b.length()).map(b::getString).toSet() } ?: emptySet(),
            backgroundIntervalMinutes = json.optInt("backgroundInterval").takeIf { it in RadarState.BACKGROUND_INTERVALS } ?: RadarState.BACKGROUND_INTERVALS.first())
        _state.update { restored.copy(automatic = it.automatic) }
    }
    private fun roomJson(r: Room) = JSONObject().put("id", r.id).put("genre", r.genreKey).put("status", r.status.name).put("action", r.action.name)
        .put("name", r.name).put("gender", r.gender.name).put("age", r.age).put("area", r.area).put("message", r.message)
    private fun readRoom(j: JSONObject) = Room(j.getLong("id"), j.getString("genre"), RoomStatus.valueOf(j.getString("status")), RoomAction.valueOf(j.getString("action")), null,
        if (j.isNull("name")) null else j.getString("name"), Gender.valueOf(j.getString("gender")), if (j.isNull("age")) null else j.getInt("age"), if (j.isNull("area")) null else j.getString("area"), j.getString("message"))
    private companion object {
        const val KEY = "radar_v1"
        /** 保存した比較基準を使う期限。これより古ければ作り直す（その回は通知しない）。 */
        const val BASELINE_TTL_MS = 6 * 60 * 60 * 1000L
        /** 計画ごとに覚えておく、一度一致した部屋の数の上限。 */
        const val MAX_KNOWN = 300
    }
}

fun formatObservationTime(at: Long): String = java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.JAPAN).format(java.util.Date(at))
fun statusName(status: RoomStatus) = when (status) { RoomStatus.WAITING -> "待機中"; RoomStatus.PUBLIC_WAITING -> "公開待機"; RoomStatus.FULL -> "満室" }
