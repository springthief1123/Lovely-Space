package io.github.springthief1123.lovelyspace.data

import androidx.room.*
import io.github.springthief1123.lovelyspace.core.NgTarget
import io.github.springthief1123.lovelyspace.core.NgWordFilter
import io.github.springthief1123.lovelyspace.core.NgWordSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** NG ワードのセット。語は改行区切りの 1 列に持つ（並び順を保ち、語の中に空白を入れない）。 */
@Entity(tableName = "ng_word_sets")
data class NgWordSetEntity(
    @PrimaryKey val id: String,
    val label: String,
    val words: String,
    val amounts: Boolean,
    val target: String,
    val enabled: Boolean,
    val builtInKey: String?,
    val position: Int,
) {
    fun toNgWordSet() = NgWordSet(id, label, words.split('\n').filter(String::isNotEmpty), amounts,
        NgTarget.entries.firstOrNull { it.name == target } ?: NgTarget.NAME_AND_MESSAGE, enabled, builtInKey)
}

@Dao
interface NgWordDao {
    @Query("SELECT * FROM ng_word_sets ORDER BY position, id")
    fun sets(): Flow<List<NgWordSetEntity>>
    @Query("SELECT * FROM ng_word_sets WHERE id = :id")
    suspend fun set(id: String): NgWordSetEntity?
    @Query("SELECT COALESCE(MAX(position), -1) FROM ng_word_sets")
    suspend fun maxPosition(): Int
    @Upsert suspend fun put(value: NgWordSetEntity)
    @Query("UPDATE ng_word_sets SET enabled = :enabled WHERE id = :id") suspend fun setEnabled(id: String, enabled: Boolean)
    @Query("DELETE FROM ng_word_sets WHERE id = :id") suspend fun delete(id: String)
}

/** NG ワードの読み込みの状態。[Ready] のセットが 0 件なのは正常（何も外さない）、[Failed] は読めなかった。 */
sealed interface NgLoadState {
    data object Loading : NgLoadState
    data class Ready(val filter: NgWordFilter) : NgLoadState
    data class Failed(val message: String) : NgLoadState
}

/**
 * NG ワードのセットの保存と、ON のセットから作った判定（[filter]）。最初からのセットは 1 度だけ入れ、
 * 全部消しても入れ直さない（[PresetRepository.initialize] の取り込みと同じ考え方）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NgWordRepository(private val db: PresetDatabase, scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
    private val dao = db.ngWords()
    private val seedLock = Mutex()

    val sets: Flow<List<NgWordSet>> = flow { initialize(); emitAll(dao.sets()) }.map { values -> values.map { it.toNgWordSet() } }

    /**
     * 保存を読み終えてからの判定。語のそろえは保存が変わったときだけ行う。読み込みに失敗したら例外のまま流す。
     * レーダーはこれを待ってから巡回し、失敗したら巡回を止める（「何も外さない」で NG に当たる部屋を新着として知らせないため）。
     */
    val readyFilter: Flow<NgWordFilter> = sets.map { NgWordFilter.of(it) }.flowOn(Dispatchers.Default)

    private val attempt = MutableStateFlow(0)

    /** 見つける画面で使う読み込みの状態。失敗と「セットなし」を分け、[retry] で読み直せる。 */
    val state: StateFlow<NgLoadState> = attempt
        .flatMapLatest {
            readyFilter.map<NgWordFilter, NgLoadState> { NgLoadState.Ready(it) }
                .onStart { emit(NgLoadState.Loading) }
                .catch { emit(NgLoadState.Failed(it.message ?: it.javaClass.simpleName)) }
        }
        .stateIn(scope, SharingStarted.Eagerly, NgLoadState.Loading)

    /** 見つける画面で使う判定。読み込むまで・読めなかったときは何も外さず、画面はすぐ出す（読めないことは [state] で知らせる）。 */
    val filter: StateFlow<NgWordFilter> = state
        .map { (it as? NgLoadState.Ready)?.filter ?: NgWordFilter.NONE }
        .stateIn(scope, SharingStarted.Eagerly, NgWordFilter.NONE)

    /** 読み込みに失敗したあと、もう一度読む。 */
    fun retry() = attempt.update { it + 1 }

    /**
     * 最初からのセットのうち、まだ入れていないものを末尾に足す。入れたセットの [NgWordSet.builtInKey] を覚えておき、
     * 後から増えたセットだけを入れる（利用者が消したセットは入れ直さない）。
     */
    suspend fun initialize() = seedLock.withLock {
        val presets = db.presets()
        val seeded = seededKeys(presets.state(SEED_KEY))
        val missing = NgWordSet.BUILT_IN.filter { it.builtInKey !in seeded }
        if (missing.isNotEmpty()) db.withTransaction {
            var position = dao.maxPosition() + 1
            missing.forEach { dao.put(it.toEntity(position++)) }
            presets.put(LocalState(SEED_KEY, (seeded + missing.mapNotNull { it.builtInKey }).joinToString(",")))
        }
        val upgraded = presets.state(UPGRADE_KEY)?.toIntOrNull() ?: 0
        if (upgraded < UPGRADE_VERSION) db.withTransaction {
            if (upgraded < 1) mergeMediaAndAddWords()
            presets.put(LocalState(UPGRADE_KEY, UPGRADE_VERSION.toString()))
        }
    }

    /**
     * 2026-10-22: 「写真・動画目的」を「お金目的」にまとめ、「ニューハーフ系」を「ニューハーフ・レズ系」にする。
     * 保存済みの最初からのセットに今の初期の語のうち無いものを 1 度だけ足し、初期の並びにそろえる（自分で足した語は後ろに残す）。
     */
    private suspend fun mergeMediaAndAddWords() {
        val media = dao.set(MEDIA_ID)
        // 削除できた前の版で「お金目的」だけを消していた端末は、「写真・動画目的」を「お金目的」に置き換える。
        val money = dao.set(MONEY_ID) ?: media?.let { NgWordSet.builtIn("money")!!.toEntity(it.position).copy(words = "", enabled = it.enabled) }
        if (money != null) {
            dao.put(money.withBuiltInWords("money", extra = media?.wordList().orEmpty()))
            if (media != null) dao.delete(MEDIA_ID)
        }
        dao.set(NEWHALF_ID)?.let { newhalf ->
            val label = if (newhalf.label == OLD_NEWHALF_LABEL) NgWordSet.NEWHALF_LABEL else newhalf.label
            dao.put(newhalf.withBuiltInWords("newhalf").copy(label = label))
        }
    }

    private fun NgWordSetEntity.wordList() = words.split('\n').filter(String::isNotEmpty)

    private fun NgWordSetEntity.withBuiltInWords(key: String, extra: List<String> = emptyList()): NgWordSetEntity {
        val order = NgWordSet.builtIn(key)!!.words
        val all = (wordList() + extra + order).distinct()
        val (known, mine) = all.partition { it in order }
        // まとめても保存できるよう、1 セットの上限に収める（初期の語を先に残す）。
        return copy(words = (known.sortedBy(order::indexOf) + mine).take(MAX_WORDS).joinToString("\n"))
    }

    suspend fun save(set: NgWordSet) {
        val label = set.label.trim()
        val words = set.words.map(String::trim).filter(String::isNotEmpty).distinct()
        require(set.id.isNotBlank() && label.isNotEmpty()) { "セットの名前を入れてください。" }
        require(label.length <= MAX_LABEL) { "セットの名前は${MAX_LABEL}文字までです。" }
        require(words.isNotEmpty() || set.amounts) { "語を 1 つ以上入れるか、金額の表記を外すをオンにしてください。" }
        require(words.size <= MAX_WORDS) { "語は 1 セット${MAX_WORDS}個までです。" }
        initialize()
        db.withTransaction {
            val position = dao.set(set.id)?.position ?: (dao.maxPosition() + 1)
            dao.put(set.copy(label = label, words = words).toEntity(position))
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = dao.setEnabled(id, enabled)
    /** 最初から入っているセットは削除できない（使わないときはオフにする）。 */
    suspend fun delete(id: String) {
        require(dao.set(id)?.builtInKey == null) { "最初から入っているセットは削除できません。" }
        dao.delete(id)
    }

    // 「done」は最初の版（お金目的・写真・動画目的の 2 セットだけ）で入れた印。
    private fun seededKeys(value: String?): Set<String> = when (value) {
        null -> emptySet()
        "done" -> setOf("money", "media")
        else -> value.split(',').filter(String::isNotEmpty).toSet()
    }

    private fun NgWordSet.toEntity(position: Int) =
        NgWordSetEntity(id, label, words.joinToString("\n"), amounts, target.name, enabled, builtInKey, position)

    companion object {
        private const val SEED_KEY = "ng_word_seed"
        private const val UPGRADE_KEY = "ng_word_upgrade"
        private const val UPGRADE_VERSION = 1
        private const val MONEY_ID = "builtin-money"
        private const val MEDIA_ID = "builtin-media"
        private const val NEWHALF_ID = "builtin-newhalf"
        private const val OLD_NEWHALF_LABEL = "ニューハーフ系"
        const val MAX_LABEL = 40
        const val MAX_WORDS = 200
    }
}
