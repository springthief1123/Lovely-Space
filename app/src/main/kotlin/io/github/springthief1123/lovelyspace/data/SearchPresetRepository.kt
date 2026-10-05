package io.github.springthief1123.lovelyspace.data

import androidx.room.*
import io.github.springthief1123.lovelyspace.core.*
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Entity(tableName = "search_presets")
data class SearchPreset(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val label: String,
    val genreKey: String,
    @Embedded(prefix = "criteria_") val criteria: RoomSearchCriteria,
)

class SearchPresetConverters {
    @TypeConverter fun keywordMode(value: String): KeywordMode = KeywordMode.valueOf(value)
    @TypeConverter fun keywordMode(value: KeywordMode): String = value.name
    @TypeConverter fun gender(value: String?): Gender? = value?.let(Gender::valueOf)
    @TypeConverter fun gender(value: Gender?): String? = value?.name
    @TypeConverter fun sort(value: String): RoomSort = RoomSort.valueOf(value)
    @TypeConverter fun sort(value: RoomSort): String = value.name
}

@Dao
interface SearchPresetDao {
    @Query("SELECT * FROM search_presets ORDER BY label, id")
    fun presets(): Flow<List<SearchPreset>>
    @Upsert suspend fun put(value: SearchPreset)
    @Query("DELETE FROM search_presets WHERE id = :id") suspend fun delete(id: String)
}

interface SearchPresetStore {
    val presets: Flow<List<SearchPreset>>
    suspend fun save(value: SearchPreset)
    suspend fun delete(id: String)
}

class SearchPresetRepository(db: PresetDatabase) : SearchPresetStore {
    private val dao = db.searchPresets()
    override val presets = dao.presets()

    override suspend fun save(value: SearchPreset) {
        require(value.id.isNotBlank() && value.label.isNotBlank())
        require(Genres[value.genreKey] != null && value.criteria.isValid)
        require(value.criteria.area == null || value.criteria.area in Prefectures.names)
        dao.put(value.copy(label = value.label.trim()))
    }

    override suspend fun delete(id: String) = dao.delete(id)
}
