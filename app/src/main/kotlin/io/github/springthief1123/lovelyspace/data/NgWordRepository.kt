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

/**
 * NG ワードのセットの保存と、ON のセットから作った判定（[filter]）。最初からのセットは 1 度だけ入れ、
 * 全部消しても入れ直さない（[PresetRepository.initialize] の取り込みと同じ考え方）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NgWordRepository(private val db: PresetDatabase, scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
    private val dao = db.ngWords()
    private val seedLock = Mutex()

    val sets: Flow<List<NgWordSet>> = flow { initialize(); emitAll(dao.sets()) }.map { values -> values.map { it.toNgWordSet() } }

    /** 見つける・レーダーで使う判定。語のそろえは保存が変わったときだけ行う。読み込むまでは何も外さない。 */
    val filter: StateFlow<NgWordFilter> = sets.map { NgWordFilter.of(it) }.flowOn(Dispatchers.Default)
        .catch { emit(NgWordFilter.NONE) }
        .stateIn(scope, SharingStarted.Eagerly, NgWordFilter.NONE)

    suspend fun initialize() = seedLock.withLock {
        val presets = db.presets()
        if (presets.state(SEED_KEY) != null) return@withLock
        db.withTransaction {
            NgWordSet.BUILT_IN.forEachIndexed { i, set -> dao.put(set.toEntity(i)) }
            presets.put(LocalState(SEED_KEY, "done"))
        }
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
    suspend fun delete(id: String) = dao.delete(id)

    private fun NgWordSet.toEntity(position: Int) =
        NgWordSetEntity(id, label, words.joinToString("\n"), amounts, target.name, enabled, builtInKey, position)

    companion object {
        private const val SEED_KEY = "ng_word_seed"
        const val MAX_LABEL = 40
        const val MAX_WORDS = 200
    }
}
